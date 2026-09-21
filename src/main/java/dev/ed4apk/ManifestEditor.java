package dev.ed4apk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.reandroid.apk.AndroidFrameworks;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.*;
import com.reandroid.arsc.value.ValueType;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Edits the binary tree in place; a DOM snapshot is used only for XPath selection. */
final class ManifestEditor {
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    static final Set<String> COMPONENTS = Set.of("activity", "activity-alias", "service", "receiver", "provider");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final AndroidManifestBlock manifest;

    ManifestEditor(AndroidManifestBlock manifest, TableBlock resources) throws IOException {
        this.manifest = manifest;
        if (manifest.getDocumentElement() == null || !"manifest".equals(manifest.getDocumentElement().getName()))
            throw new IOException("Expected a manifest root element");
        // Framework definitions supply Android attribute IDs, enums and flags, entirely offline.
        // Framework links and package context are not serialized into resources.arsc.
        if (resources == null) {
            resources = new TableBlock();
            resources.newPackage(0x7f, manifest.getPackageName() == null ? "app" : manifest.getPackageName());
        }
        resources.addFramework(AndroidFrameworks.getLatest().getTableBlock());
        var packages = resources.getPackages(manifest.getPackageName());
        var pkg = packages.hasNext() ? packages.next() : resources.size() == 1 ? resources.pickOne() : null;
        if (pkg == null) throw new IOException("Cannot select Manifest resource package");
        manifest.setPackageBlock(pkg);
    }

    static ManifestEditor open(Path apk) throws IOException {
        TableBlock table = null;
        if (com.android.zipflinger.ZipArchive.listEntries(apk).containsKey("resources.arsc"))
            table = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(apk, "resources.arsc")));
        return new ManifestEditor(ResourceEditor.manifest(apk), table);
    }

    void execute(PatchPlan.Operation op) throws IOException {
        if (op.kind().equals("manifest.add")) {
            ResXmlElement parent = unique(selectPath(op.text("parent")));
            insert(parent, parent.size(), op.node("node"));
            return;
        }
        ResXmlElement target = unique(select(op.optionalText("path", null),
                op.optionalText("component", null), op.optionalText("name", null)));
        switch (op.kind()) {
            case "manifest.update": {
                JsonNode attributes = op.node("attributes");
                List<String> remove = op.strings("removeAttributes", false);
                if ((attributes == null || attributes.isEmpty()) && remove.isEmpty())
                    throw new IOException("Specify attributes or removeAttributes");
                Set<String> seen = new HashSet<>();
                if (attributes != null) {
                    if (!attributes.isObject()) throw new IOException("attributes must be an object");
                    var names = attributes.fieldNames();
                    while (names.hasNext()) {
                        String key = names.next();
                        if (!seen.add(attributeIdentity(target, key))) throw new IOException("Repeated attribute: " + key);
                    }
                }
                for (String key : remove) {
                    if (!seen.add(attributeIdentity(target, key)))
                        throw new IOException("Repeated/conflicting attribute: " + key);
                    String[] q = attributeName(target, key);
                    var attr = target.searchAttribute(q[0], q[2]);
                    if (attr == null) throw new IOException("Attribute not found: " + key);
                    target.removeAttribute(attr);
                }
                if (attributes != null) attributes(target, attributes);
                validateElement(target);
                break;
            }
            case "manifest.replace": {
                requireNonRoot(target);
                ResXmlElement parent = target.getParentElement();
                int index = parent.indexOf(target);
                target.removeSelf();
                insert(parent, index, op.node("node"));
                break;
            }
            case "manifest.delete":
                requireNonRoot(target);
                target.removeSelf();
                break;
            default: throw new IOException("Unknown Manifest operation: " + op.kind());
        }
    }

    private static void requireNonRoot(ResXmlElement target) throws IOException {
        if (target.getParentElement() == null) throw new IOException("Cannot delete or replace the manifest root");
    }

    List<ResXmlElement> select(String path, String component, String name) throws IOException {
        if (path != null) {
            if (component != null || name != null) throw new IOException("Use path OR component and name");
            return selectPath(path);
        }
        if (component == null || name == null || name.isBlank() || !COMPONENTS.contains(component))
            throw new IOException("Specify path, or component (activity/activity-alias/service/receiver/provider) and name");
        List<ResXmlElement> matches = new ArrayList<>();
        for (ResXmlElement app : children(manifest.getDocumentElement())) {
            if (!app.getName().equals("application")) continue;
            for (ResXmlElement element : children(app)) {
                if (!element.getName().equals(component)) continue;
                var attr = element.searchAttribute(ANDROID, "name");
                if (attr != null && attr.getValueType() == ValueType.STRING
                        && fullName(attr.getValueAsString()).equals(fullName(name))) matches.add(element);
            }
        }
        return matches;
    }

    private String fullName(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IOException("Empty component name");
        if (!name.startsWith(".") && name.contains(".")) return name;
        String pkg = manifest.getPackageName();
        if (pkg == null || pkg.isBlank()) throw new IOException("Missing Manifest package for relative class name");
        return pkg + (name.startsWith(".") ? "" : ".") + name;
    }

    private static ResXmlElement unique(List<ResXmlElement> matches) throws IOException {
        if (matches.size() != 1) throw new IOException("Expected exactly one Manifest element; matched " + matches.size());
        return matches.get(0);
    }

    private List<ResXmlElement> selectPath(String path) throws IOException {
        if (path.isBlank() || !path.startsWith("/")) throw new IOException("Manifest path must be an absolute XPath");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = factory.newDocumentBuilder().newDocument(); // No XML is parsed; no external entities.
            Map<Node, ResXmlElement> elements = new IdentityHashMap<>();
            doc.appendChild(dom(doc, manifest.getDocumentElement(), elements));
            var xpathFactory = XPathFactory.newInstance();
            xpathFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XPath xpath = xpathFactory.newXPath();
            xpath.setNamespaceContext(new NamespaceContext() {
                public String getNamespaceURI(String prefix) {
                    if ("android".equals(prefix)) return ANDROID;
                    if ("xml".equals(prefix)) return XMLConstants.XML_NS_URI;
                    if ("xmlns".equals(prefix)) return XMLConstants.XMLNS_ATTRIBUTE_NS_URI;
                    var ns = manifest.getDocumentElement().getNamespaceForPrefix(prefix);
                    return ns == null ? XMLConstants.NULL_NS_URI : ns.getUri();
                }
                public String getPrefix(String uri) { return ANDROID.equals(uri) ? "android" : null; }
                public Iterator<String> getPrefixes(String uri) {
                    String prefix = getPrefix(uri);
                    return prefix == null ? Collections.emptyIterator() : List.of(prefix).iterator();
                }
            });
            NodeList nodes = (NodeList) xpath.evaluate(path, doc, XPathConstants.NODESET);
            List<ResXmlElement> result = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                ResXmlElement element = elements.get(nodes.item(i));
                if (element == null) throw new IOException("Manifest XPath must select elements, not attributes/text");
                result.add(element);
            }
            return result;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("Invalid Manifest XPath '" + path + "': " + e.getMessage(), e); }
    }

    private Node dom(Document doc, ResXmlElement source, Map<Node, ResXmlElement> elements) {
        var node = doc.createElementNS(source.getUri(), source.getName(true));
        elements.put(node, source);
        var attributes = source.getAttributes();
        while (attributes.hasNext()) {
            var a = attributes.next();
            node.setAttributeNS(a.getUri(), a.getName(true),
                    a.getValueType() == ValueType.STRING ? a.getValueAsString() : a.decodeValue());
        }
        for (ResXmlElement child : children(source)) node.appendChild(dom(doc, child, elements));
        return node;
    }

    private ResXmlElement insert(ResXmlElement parent, int index, JsonNode node) throws IOException {
        PatchPlan.fields(node, Set.of("tag", "attributes", "children", "namespaces"));
        JsonNode tag = node.get("tag");
        if (tag == null || !tag.isTextual() || !tag.textValue().matches("[A-Za-z_][A-Za-z0-9_.-]*"))
            throw new IOException("node.tag must be an unqualified XML element name");
        ResXmlElement result = parent.newElementAt(index, tag.textValue());
        if (node.has("namespaces")) {
            JsonNode namespaces = node.get("namespaces");
            if (!namespaces.isObject()) throw new IOException("namespaces must be an object of prefix: URI");
            var fields = namespaces.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String prefix = field.getKey();
                if (!prefix.matches("[A-Za-z_][A-Za-z0-9_.-]*") || prefix.startsWith("xml")
                        || !field.getValue().isTextual() || field.getValue().textValue().isBlank()
                        || prefix.equals("android") && !field.getValue().textValue().equals(ANDROID))
                    throw new IOException("Invalid namespace: " + prefix);
                result.getOrCreateNamespace(field.getValue().textValue(), prefix);
            }
        }
        if (node.has("attributes")) attributes(result, node.get("attributes"));
        if (node.has("children")) {
            JsonNode nodes = node.get("children");
            if (!nodes.isArray()) throw new IOException("node.children must be an array");
            for (JsonNode child : nodes) insert(result, result.size(), child);
        }
        validateElement(result);
        return result;
    }

    private void attributes(ResXmlElement element, JsonNode values) throws IOException {
        if (!values.isObject()) throw new IOException("attributes must be an object");
        Set<String> seen = new HashSet<>();
        var fields = values.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String key = field.getKey();
            if (!seen.add(attributeIdentity(element, key))) throw new IOException("Repeated attribute: " + key);
            String[] q = attributeName(element, key);
            // The encoder resolves Android IDs by package name; XML prefixes can be arbitrary.
            String encodePrefix = ANDROID.equals(q[0]) ? "android" : q[1];
            var attr = element.searchAttribute(q[0], q[2]);
            if (attr == null) attr = element.newAttribute();
            JsonNode value = field.getValue();
            try {
                if (value.isObject()) {
                    // Explicit representation preserves exact binary types, including literal strings.
                    String typeName = value.path("type").asText();
                    ValueType type;
                    try { type = ValueType.valueOf(typeName); }
                    catch (IllegalArgumentException e) { throw new IOException("Unknown value type: " + typeName); }
                    attr.encodeAttributeName(q[0], encodePrefix, q[2]);
                    if (type == ValueType.STRING) {
                        PatchPlan.fields(value, Set.of("type", "value"));
                        if (!value.path("value").isTextual()) throw new IOException("STRING requires value: string");
                        attr.setValueAsString(value.get("value").textValue());
                    } else {
                        PatchPlan.fields(value, Set.of("type", "data"));
                        attr.setValueType(type);
                        attr.setData(rawData(value.get("data")));
                    }
                } else {
                    if (!(value.isTextual() || value.isBoolean() || value.isNumber()))
                        throw new IOException("Expected string, boolean, number or typed value; use removeAttributes to remove");
                    if (value.isIntegralNumber() && !value.canConvertToInt())
                        throw new IOException("Integer attribute value must fit 32 bits");
                    attr.encode(q[0], encodePrefix, q[2], value.asText(), true);
                }
                if (!Objects.equals(encodePrefix, q[1])) attr.setNamespace(q[0], q[1]);
            } catch (IOException e) { throw new IOException("Attribute " + key + ": " + e.getMessage(), e); }
        }
    }

    private static int rawData(JsonNode node) throws IOException {
        if (node != null && node.isIntegralNumber() && node.canConvertToInt()) return node.intValue();
        if (node != null && node.isTextual() && node.textValue().matches("0x[0-9a-fA-F]{1,8}"))
            return (int) Long.parseLong(node.textValue().substring(2), 16);
        throw new IOException("Typed data must be a signed 32-bit integer or 0x00000000..0xffffffff");
    }

    private static String[] attributeName(ResXmlElement element, String key) throws IOException {
        if (!key.matches("[A-Za-z_][A-Za-z0-9_.-]*(:[A-Za-z_][A-Za-z0-9_.-]*)?")
                || key.equals("xmlns") || key.startsWith("xmlns:"))
            throw new IOException("Invalid attribute name: " + key);
        int colon = key.indexOf(':');
        if (colon < 0) return new String[]{null, null, key};
        String prefix = key.substring(0, colon), name = key.substring(colon + 1);
        if (prefix.equals("android")) return new String[]{ANDROID, "android", name};
        var ns = element.getNamespaceForPrefix(prefix);
        if (ns == null) throw new IOException("Unknown namespace prefix: " + prefix);
        return new String[]{ns.getUri(), prefix, name};
    }

    private static String attributeIdentity(ResXmlElement element, String key) throws IOException {
        String[] q = attributeName(element, key);
        return (q[0] == null ? "" : q[0]) + "|" + q[2];
    }

    private void validateElement(ResXmlElement element) throws IOException {
        String tag = element.getName();
        if (tag.equals("manifest") && element != manifest.getDocumentElement())
            throw new IOException("Cannot nest a manifest element");
        if (tag.equals("application")) {
            if (element.getParentElement() != manifest.getDocumentElement()
                    || children(manifest.getDocumentElement()).stream().filter(e -> e.getName().equals("application")).count() != 1)
                throw new IOException("application must be a unique direct child of manifest");
        }
        if (!COMPONENTS.contains(tag)) return;
        ResXmlElement parent = element.getParentElement();
        if (parent == null || !parent.getName().equals("application") || parent.getParentElement() != manifest.getDocumentElement())
            throw new IOException("Component must be a direct child of application: " + tag);
        var name = element.searchAttribute(ANDROID, "name");
        // XPath can edit declarations whose names are resource-dependent without inlining a configuration.
        if (name != null && name.getValueType() == ValueType.REFERENCE && name.getData() != 0) return;
        if (name == null || name.getValueType() != ValueType.STRING || name.getValueAsString().isBlank())
            throw new IOException("Edited component requires a nonempty android:name string or resource reference: " + tag);
        String fullName = fullName(name.getValueAsString());
        for (ResXmlElement sibling : children(parent)) {
            // Activity aliases share the activity name space.
            boolean sameKind = sibling.getName().equals(tag) ||
                    Set.of("activity", "activity-alias").contains(tag) && Set.of("activity", "activity-alias").contains(sibling.getName());
            if (sibling == element || !sameKind) continue;
            var other = sibling.searchAttribute(ANDROID, "name");
            if (other != null && other.getValueType() == ValueType.STRING && fullName(other.getValueAsString()).equals(fullName))
                throw new IOException("Component already exists: " + tag + " " + fullName);
        }
    }

    static List<ResXmlElement> children(ResXmlElement element) {
        List<ResXmlElement> result = new ArrayList<>();
        var iterator = element.getElements();
        while (iterator.hasNext()) result.add(iterator.next());
        return result;
    }

    List<ObjectNode> show(String path, String component, String name) throws IOException {
        List<ObjectNode> result = new ArrayList<>();
        for (var element : select(path == null && component == null && name == null ? "/manifest" : path, component, name))
            result.add(describe(element, true));
        return result;
    }

    private ObjectNode describe(ResXmlElement element, boolean includeInheritedNamespaces) throws IOException {
        var node = JSON.objectNode().put("tag", element.getName());
        var namespaces = JSON.objectNode();
        var ns = includeInheritedNamespaces ? element.getVisibleNamespaces() : element.getNamespaces();
        while (ns.hasNext()) {
            var item = ns.next();
            if (!namespaces.has(item.getPrefix())) namespaces.put(item.getPrefix(), item.getUri());
        }
        if (!namespaces.isEmpty()) node.set("namespaces", namespaces);
        var attrs = node.putObject("attributes");
        var iterator = element.getAttributes();
        while (iterator.hasNext()) {
            var a = iterator.next();
            if (a.getValueType() == null) throw new IOException("Unknown binary attribute type: " + a.getName());
            var value = attrs.putObject(a.getName(true)).put("type", a.getValueType().name());
            if (a.getValueType() == ValueType.STRING) value.put("value", a.getValueAsString());
            else value.put("data", String.format("0x%08x", a.getData()));
        }
        var nodes = node.putArray("children");
        for (var child : children(element)) nodes.add(describe(child, false));
        return node;
    }
}
