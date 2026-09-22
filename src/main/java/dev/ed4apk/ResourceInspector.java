package dev.ed4apk;

import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ValueItem;
import com.reandroid.arsc.value.ValueType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import static dev.ed4apk.Inspection.object;

/** Configuration-preserving resource inspection; references are never resolved to an arbitrary locale. */
final class ResourceInspector {
    static final int MAX_TABLE_BYTES = 128 * 1024 * 1024;

    static TableBlock read(ApkInspectionSession session) throws IOException {
        if (session.entries().stream().noneMatch(e -> e.getName().equals("resources.arsc"))) return null;
        byte[] bytes = session.read("resources.arsc", MAX_TABLE_BYTES);
        ManifestInspector.validateChunk(bytes, 0x0002, "resources.arsc");
        try { return TableBlock.load(new ByteArrayInputStream(bytes)); }
        catch (RuntimeException e) { throw new IOException("Cannot parse resources.arsc: " + e.getMessage(), e); }
    }

    static Map<String, Object> list(ApkInspectionSession session, String type) throws IOException {
        var table = read(session);
        List<Map<String, Object>> result = new ArrayList<>();
        if (table != null) {
            for (var resource : resources(table)) {
                if (type == null || type.equals(resource.getType())) result.add(describe(resource));
            }
        }
        return object("tablePresent", table != null, "resourceCount", result.size(), "resources", result);
    }

    static Map<String, Object> show(ApkInspectionSession session, String selector) throws IOException {
        var table = read(session);
        if (table == null) throw new IOException("APK has no resources.arsc table");
        Integer id = parseId(selector);
        String name = selector.startsWith("@") ? selector.substring(1) : selector;
        List<ResourceEntry> matches = new ArrayList<>();
        for (var resource : resources(table)) {
            if (id != null ? resource.getResourceId() == id
                    : name.equals(resource.getType() + "/" + resource.getName())
                    || name.equals(resource.getPackageName() + ":" + resource.getType() + "/" + resource.getName()))
                matches.add(resource);
        }
        if (matches.isEmpty()) throw new IOException("Resource not found: " + selector);
        if (matches.size() != 1) throw new IOException("Ambiguous resource: " + selector + "; use an ID or @package:type/name");
        return describe(matches.get(0));
    }

    static void validateSelector(String selector) {
        if (parseId(selector) != null) return;
        if (selector.matches("@?(?:[A-Za-z0-9_.]+:)?[A-Za-z0-9_]+/[A-Za-z0-9_.]+")) return;
        throw new IllegalArgumentException("Expected a 32-bit resource ID (0x7f010000 or decimal), or @type/name");
    }

    private static Integer parseId(String selector) {
        if (!selector.matches("0[xX][0-9a-fA-F]{1,8}|[0-9]{1,10}")) return null;
        try {
            long value = selector.startsWith("0x") || selector.startsWith("0X")
                    ? Long.parseLong(selector.substring(2), 16) : Long.parseLong(selector);
            return value <= 0xffffffffL ? (int) value : null;
        } catch (NumberFormatException e) { return null; }
    }

    private static List<ResourceEntry> resources(TableBlock table) {
        List<ResourceEntry> result = new ArrayList<>();
        // Iterate local packages only. Framework references are exposed as IDs, not mixed into the APK inventory.
        for (var pkg : table) {
            var resources = pkg.getResources();
            while (resources.hasNext()) {
                var resource = resources.next();
                if (resource.isDefined()) result.add(resource);
            }
        }
        result.sort(Comparator.comparingLong(resource -> Integer.toUnsignedLong(resource.getResourceId())));
        return result;
    }

    private static Map<String, Object> describe(ResourceEntry resource) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (var entry : resource) if (!entry.isNull()) entries.add(entry);
        entries.sort(Comparator.comparing(e -> e.getResConfig().getQualifiers()));
        List<Map<String, Object>> configurations = new ArrayList<>();
        for (var entry : entries) {
            Map<String, Object> value;
            if (entry.isComplex()) {
                var bag = entry.getResTableMapEntry();
                if (bag == null) throw new IOException("Unsupported complex entry: " + resource.getHexId());
                List<Map<String, Object>> items = new ArrayList<>();
                for (var item : bag) items.add(object("nameId", hex(item.getNameId()), "value", value(item)));
                value = object("type", "BAG", "parentId", hex(bag.getParentId()), "items", items);
            } else {
                if (entry.getResValue() == null) throw new IOException("Missing resource value: " + resource.getHexId());
                value = value(entry.getResValue());
            }
            configurations.add(object("config", entry.getResConfig().getQualifiers(), "complex", entry.isComplex(), "value", value));
        }
        return object("id", hex(resource.getResourceId()), "package", resource.getPackageName(),
                "type", resource.getType(), "name", resource.getName(),
                "qualifiedName", "@" + resource.getPackageName() + ":" + resource.getType() + "/" + resource.getName(),
                "configurationCount", configurations.size(), "configurations", configurations);
    }

    private static Map<String, Object> value(ValueItem value) {
        ValueType type = value.getValueType();
        var result = object("type", type == null ? "UNKNOWN" : type.name(), "data", hex(value.getData()));
        if (type == ValueType.STRING) {
            result.put("value", value.getValueAsString());
            var styled = value.getValueAsStyleDocument();
            if (styled != null) result.put("styledValue", styled.toString());
        } else if (type == ValueType.BOOLEAN) result.put("value", value.getValueAsBoolean());
        else if (type == ValueType.DEC || type == ValueType.HEX) result.put("value", value.getData());
        else if (type == ValueType.REFERENCE || type == ValueType.DYNAMIC_REFERENCE)
            result.put("reference", "@" + hex(value.getData()));
        else if (type == ValueType.ATTRIBUTE || type == ValueType.DYNAMIC_ATTRIBUTE)
            result.put("reference", "?" + hex(value.getData()));
        // decodeValue renders dimensions, colors and fractions while the type/raw data remain authoritative.
        if (type != null && type != ValueType.STRING) {
            // ARSCLib 1.4.0 renders unresolved DYNAMIC_REFERENCE with '?' instead of '@'.
            // Keep all reference displays consistent with their stored binary types.
            result.put("decoded", result.containsKey("reference") ? result.get("reference") : value.decodeValue(false));
        }
        return result;
    }

    private static String hex(int value) { return String.format("0x%08x", value); }
}
