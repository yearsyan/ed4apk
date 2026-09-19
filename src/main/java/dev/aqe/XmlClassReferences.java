package dev.aqe;

import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.*;
import com.reandroid.arsc.value.ValueType;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/** Known Android XML class slots. Ordinary strings, aliases and identifiers are not class names. */
final class XmlClassReferences {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final String entry, role, packageName;
    private final TableBlock table;
    private final Set<String> targets;
    private final Map<String, String> mapping;
    private final Consumer<ClassReferences.Hit> sink;
    private boolean changed;

    XmlClassReferences(String entry, String role, String packageName, TableBlock table,
                       Set<String> targets, Map<String, String> mapping, Consumer<ClassReferences.Hit> sink) {
        this.entry = entry; this.role = role; this.packageName = packageName; this.table = table;
        this.targets = targets; this.mapping = mapping; this.sink = sink;
    }

    boolean scan(ResXmlDocument doc) throws IOException {
        if (doc.getDocumentElement() == null) throw new IOException("XML has no root: " + entry);
        walk(doc.getDocumentElement(), "");
        return changed;
    }

    private void walk(ResXmlElement element, String parent) throws IOException {
        String tag = element.getName();
        String path = parent + "/" + tag + "[" + element.getIndex() + "]";
        if (!role.equals("manifest") && tag.contains(".") && (role.equals("layout") || role.equals("xml")))
            slot(Set.of(tag), path + " | element", element::setName, false, false);
        var attributes = element.getAttributes();
        while (attributes.hasNext()) {
            ResXmlAttribute a = attributes.next();
            String name = a.getName(false);
            String uri = a.getUri();
            boolean android = ANDROID.equals(uri), plain = uri == null || uri.isEmpty();
            boolean app = "http://schemas.android.com/apk/res-auto".equals(uri)
                    || (uri != null && uri.startsWith("http://schemas.android.com/apk/res/") && !android);
            boolean relative = false, array = false, match = false;
            if (role.equals("manifest") && android) {
                Set<String> names;
                switch (tag) {
                    case "application": names = Set.of("name", "backupAgent", "manageSpaceActivity", "appComponentFactory", "zygotePreloadName"); break;
                    case "activity": names = Set.of("name", "parentActivityName"); break;
                    case "activity-alias": names = Set.of("targetActivity", "parentActivityName"); break;
                    case "service": case "receiver": case "provider": case "instrumentation": names = Set.of("name"); break;
                    default: names = Set.of();
                }
                match = names.contains(name); relative = true;
            } else if (role.equals("layout")) {
                match = plain && name.equals("class") && (tag.equals("view") || tag.equals("fragment")
                        || tag.endsWith(".FragmentContainerView"));
                match |= android && name.equals("name") && (tag.equals("fragment") || tag.endsWith(".FragmentContainerView"));
                match |= app && (name.equals("layout_behavior") || name.equals("layoutManager"));
                relative = app;
            } else if (role.equals("navigation")) {
                match = android && name.equals("name") && Set.of("fragment", "dialog", "activity").contains(tag);
                array = app && tag.equals("argument") && name.equals("argType");
                match |= array; relative = true;
            } else if (role.equals("xml")) {
                match = (android || app) && name.equals("fragment");
                match |= android && tag.equals("intent") && name.equals("targetClass");
            }
            if (!match) continue;
            String location = path + " | " + (android ? "android:" : app ? "app:" : "") + name;
            Set<String> values;
            if (a.getValueType() == ValueType.STRING) values = Collections.singleton(a.getValueAsString());
            else if (a.getValueType() == ValueType.REFERENCE && a.getData() == 0) continue;
            else if (a.getValueType() == ValueType.REFERENCE) values = resolve(a.getData(), new HashSet<>(), location);
            else throw new IOException("Cannot resolve XML class value: " + entry + " | " + location);
            slot(values, location, a::setValueAsString, relative, array);
        }
        var children = element.getElements();
        while (children.hasNext()) walk(children.next(), path);
    }

    private Set<String> resolve(int id, Set<Integer> seen, String location) throws IOException {
        if (table == null || !seen.add(id))
            throw new IOException("Unresolved/cyclic class resource " + String.format("0x%08x", id) + " at " + entry + " | " + location);
        Set<String> result = new LinkedHashSet<>();
        var variants = table.getEntries(id);
        while (variants.hasNext()) {
            var value = variants.next();
            if (value.isComplex()) throw new IOException("Complex class resource at " + entry + " | " + location);
            if (value.getValueType() == ValueType.STRING) result.add(value.getValueAsString());
            else if (value.getValueType() == ValueType.REFERENCE)
                result.addAll(resolve(value.getResValue().getData(), seen, location));
            else throw new IOException("Non-string class resource at " + entry + " | " + location);
        }
        seen.remove(id);
        if (result.isEmpty()) throw new IOException("Missing class resource at " + entry + " | " + location);
        return result;
    }

    private void slot(Set<String> values, String location, Consumer<String> setter, boolean relative, boolean array) throws IOException {
        Set<String> rewritten = new LinkedHashSet<>();
        boolean update = false;
        for (String raw : values) {
            if (raw == null || raw.isEmpty()) { rewritten.add(raw); continue; }
            String name = raw;
            String suffix = array && name.endsWith("[]") ? "[]" : "";
            if (!suffix.isEmpty()) name = name.substring(0, name.length() - 2);
            if (relative && (name.startsWith(".") || role.equals("manifest") && !name.contains("."))
                    && (packageName == null || packageName.isEmpty()))
                throw new IOException("Missing Manifest package for relative class at " + entry + " | " + location);
            if (relative && name.startsWith(".")) name = packageName + name;
            else if (role.equals("manifest") && relative && !name.contains(".")) name = packageName + "." + name;
            String descriptor = DexEditor.descriptor(name);
            if (targets.contains(descriptor)) sink.accept(new ClassReferences.Hit(descriptor, entry, null, entry + " | " + location, false));
            String replacement = mapping.get(descriptor);
            if (replacement != null) {
                rewritten.add(replacement.substring(1, replacement.length() - 1).replace('/', '.') + suffix);
                update = true;
            } else rewritten.add(raw);
        }
        if (!update) return;
        if (rewritten.size() != 1)
            throw new IOException("Ambiguous class resource configurations at " + entry + " | " + location
                    + "; cannot inline one class without changing other configurations");
        setter.accept(rewritten.iterator().next());
        changed = true;
    }
}
