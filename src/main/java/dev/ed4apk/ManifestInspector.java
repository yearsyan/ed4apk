package dev.ed4apk;

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static dev.ed4apk.Inspection.object;

/** Read-only Manifest facts. Missing and resource-dependent declarations are never inferred. */
final class ManifestInspector {
    static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;

    static AndroidManifestBlock read(ApkInspectionSession session) throws IOException {
        byte[] bytes = session.read("AndroidManifest.xml", MAX_MANIFEST_BYTES);
        validateChunk(bytes, 0x0003, "AndroidManifest.xml");
        try {
            var manifest = ResourceEditor.manifest(bytes);
            if (manifest.getDocumentElement() == null || !"manifest".equals(manifest.getDocumentElement().getName()))
                throw new IOException("Expected a manifest root element");
            return manifest;
        } catch (RuntimeException e) {
            throw new IOException("Cannot parse AndroidManifest.xml: " + e.getMessage(), e);
        }
    }

    static String decodeXml(byte[] bytes) throws IOException {
        validateChunk(bytes, 0x0003, "binary XML");
        try {
            var document = new com.reandroid.arsc.chunk.xml.ResXmlDocument();
            document.readBytes(new java.io.ByteArrayInputStream(bytes));
            if (document.getDocumentElement() == null) throw new IOException("Binary XML has no root element");
            var output = new java.io.StringWriter();
            var serializer = com.reandroid.xml.XMLFactory.newSerializer(output);
            serializeXml(document, serializer);
            serializer.flush();
            return output.toString();
        } catch (RuntimeException e) {
            throw new IOException("Cannot decode binary XML: " + e.getMessage(), e);
        }
    }

    static void serializeXml(com.reandroid.arsc.chunk.xml.ResXmlNode node,
                             org.xmlpull.v1.XmlSerializer serializer) throws IOException {
        ResXmlElement root = node instanceof com.reandroid.arsc.chunk.xml.ResXmlDocument
                ? ((com.reandroid.arsc.chunk.xml.ResXmlDocument) node).getDocumentElement() : (ResXmlElement) node;
        List<ResXmlAttribute> dynamicReferences = new ArrayList<>();
        collectDynamicReferences(root, dynamicReferences);
        // ARSCLib 1.4.0 confuses DYNAMIC_REFERENCE with an attribute reference during XML
        // decoding. XML only exposes the raw reference notation; temporarily select the
        // equivalent '@' decoder, then restore every type for subsequent typed JSON reads.
        try {
            for (var attribute : dynamicReferences) attribute.setValueType(ValueType.REFERENCE);
            node.serialize(serializer, false);
        } finally {
            for (var attribute : dynamicReferences) attribute.setValueType(ValueType.DYNAMIC_REFERENCE);
        }
    }

    private static void collectDynamicReferences(ResXmlElement element, List<ResXmlAttribute> references) {
        var attributes = element.getAttributes();
        while (attributes.hasNext()) {
            var attribute = attributes.next();
            if (attribute.getValueType() == ValueType.DYNAMIC_REFERENCE) references.add(attribute);
        }
        for (var child : ManifestEditor.children(element)) collectDynamicReferences(child, references);
    }

    /** Reject truncated/wrong outer chunks before the library attempts to decode them. */
    static void validateChunk(byte[] bytes, int type, String name) throws IOException {
        if (bytes.length < 8) throw new IOException("Truncated " + name + " chunk header");
        int actualType = (bytes[0] & 255) | (bytes[1] & 255) << 8;
        int headerSize = (bytes[2] & 255) | (bytes[3] & 255) << 8;
        long size = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes, 4, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
        if (actualType != type || headerSize < 8 || headerSize > size || size != bytes.length)
            throw new IOException("Invalid or truncated " + name + " chunk (type=" + actualType + ", size=" + size + ")");
    }

    static Map<String, Object> overview(ApkInspectionSession session) throws IOException {
        return overview(read(session));
    }

    private static Map<String, Object> overview(AndroidManifestBlock manifest) {
        var root = manifest.getDocumentElement();
        var sdk = root.getElement("uses-sdk");
        var declarations = object(
                "packageName", declaration(attr(root, null, "package")),
                "versionName", declaration(attr(root, ManifestEditor.ANDROID, "versionName")),
                "versionCode", declaration(attr(root, ManifestEditor.ANDROID, "versionCode")),
                "versionCodeMajor", declaration(attr(root, ManifestEditor.ANDROID, "versionCodeMajor")),
                "minSdk", declaration(attr(sdk, ManifestEditor.ANDROID, "minSdkVersion")),
                "targetSdk", declaration(attr(sdk, ManifestEditor.ANDROID, "targetSdkVersion")),
                "split", declaration(attr(root, null, "split")),
                "configForSplit", declaration(attr(root, null, "configForSplit")),
                "isFeatureSplit", declaration(attr(root, ManifestEditor.ANDROID, "isFeatureSplit")));
        var result = object();
        declarations.forEach((key, value) -> result.put(key, ((Map<?, ?>) value).get("declared")));
        result.put("declarations", declarations);
        return result;
    }

    static Map<String, Object> summary(ApkInspectionSession session) throws IOException {
        var manifest = read(session);
        var result = overview(manifest);
        var root = manifest.getDocumentElement();
        var app = root.getElement("application");
        List<Map<String, Object>> permissions = new ArrayList<>();
        List<Map<String, Object>> components = new ArrayList<>();
        List<Map<String, Object>> launchers = new ArrayList<>();
        for (var element : ManifestEditor.children(root)) {
            if (element.getName().equals("uses-permission") || element.getName().equals("uses-permission-sdk-23")
                    || element.getName().equals("uses-permission-sdk-m")) {
                permissions.add(object("tag", element.getName(),
                        "name", declaration(attr(element, ManifestEditor.ANDROID, "name")),
                        "maxSdkVersion", declaration(attr(element, ManifestEditor.ANDROID, "maxSdkVersion")),
                        "usesPermissionFlags", declaration(attr(element, ManifestEditor.ANDROID, "usesPermissionFlags"))));
            }
        }
        if (app != null) for (var element : ManifestEditor.children(app)) {
            if (!ManifestEditor.COMPONENTS.contains(element.getName())) continue;
            var name = attr(element, ManifestEditor.ANDROID, "name");
            var target = attr(element, ManifestEditor.ANDROID, "targetActivity");
            var component = object("kind", element.getName(), "name", declaration(name),
                    "qualifiedName", qualifiedName(name, manifest.getPackageName()),
                    "exported", declaration(attr(element, ManifestEditor.ANDROID, "exported")),
                    "enabled", declaration(attr(element, ManifestEditor.ANDROID, "enabled")),
                    "permission", declaration(attr(element, ManifestEditor.ANDROID, "permission")));
            if (element.getName().equals("activity-alias")) {
                component.put("targetActivity", declaration(target));
                component.put("qualifiedTargetActivity", qualifiedName(target, manifest.getPackageName()));
            }
            components.add(component);
            if (element.getName().equals("activity") || element.getName().equals("activity-alias")) {
                List<String> categories = launcherCategories(element);
                if (!categories.isEmpty()) launchers.add(object("kind", element.getName(),
                        "name", declaration(name), "qualifiedName", qualifiedName(name, manifest.getPackageName()),
                        "targetActivity", declaration(target), "qualifiedTargetActivity", qualifiedName(target, manifest.getPackageName()),
                        "categories", categories, "enabled", declaration(attr(element, ManifestEditor.ANDROID, "enabled")),
                        "exported", declaration(attr(element, ManifestEditor.ANDROID, "exported"))));
            }
        }
        result.put("applicationPresent", app != null);
        result.put("debuggable", declaration(attr(app, ManifestEditor.ANDROID, "debuggable")));
        result.put("extractNativeLibs", declaration(attr(app, ManifestEditor.ANDROID, "extractNativeLibs")));
        result.put("applicationEnabled", declaration(attr(app, ManifestEditor.ANDROID, "enabled")));
        result.put("permissions", permissions);
        result.put("components", components);
        result.put("launchCandidates", launchers);
        result.put("launchCandidateScope", "Declared MAIN + LAUNCHER/LEANBACK_LAUNCHER filters; availability is not inferred from defaults, resources, aliases or device state");
        return result;
    }

    private static List<String> launcherCategories(ResXmlElement component) {
        List<String> result = new ArrayList<>();
        for (var filter : ManifestEditor.children(component)) {
            if (!filter.getName().equals("intent-filter")) continue;
            boolean main = false;
            List<String> categories = new ArrayList<>();
            for (var item : ManifestEditor.children(filter)) {
                var name = attr(item, ManifestEditor.ANDROID, "name");
                if (name == null || name.getValueType() != ValueType.STRING) continue;
                String value = name.getValueAsString();
                if (item.getName().equals("action") && "android.intent.action.MAIN".equals(value)) main = true;
                if (item.getName().equals("category") && ("android.intent.category.LAUNCHER".equals(value)
                        || "android.intent.category.LEANBACK_LAUNCHER".equals(value))) categories.add(value);
            }
            if (main) for (String category : categories) if (!result.contains(category)) result.add(category);
        }
        return result;
    }

    static ResXmlAttribute attr(ResXmlElement element, String namespace, String name) {
        return element == null ? null : element.searchAttribute(namespace, name);
    }

    static Map<String, Object> declaration(ResXmlAttribute attr) {
        if (attr == null) return object("present", false, "declared", null, "type", null, "data", null);
        var type = attr.getValueType();
        Object declared = null;
        if (type == ValueType.STRING) declared = attr.getValueAsString();
        else if (type == ValueType.BOOLEAN) declared = attr.getValueAsBoolean();
        else if (type == ValueType.DEC || type == ValueType.HEX) declared = attr.getData();
        var result = object("present", true, "declared", declared, "type", type == null ? "UNKNOWN" : type.name(),
                "data", type == ValueType.STRING ? null : String.format("0x%08x", attr.getData()));
        if (declared == null) result.put("reason", "Declaration is not a literal string, integer or boolean; value is not resolved");
        return result;
    }

    private static String qualifiedName(ResXmlAttribute attr, String pkg) {
        if (attr == null || attr.getValueType() != ValueType.STRING) return null;
        String name = attr.getValueAsString();
        if (name == null || name.isEmpty()) return null;
        if (!name.startsWith(".") && name.contains(".")) return name;
        if (pkg == null || pkg.isEmpty()) return null;
        return pkg + (name.startsWith(".") ? "" : ".") + name;
    }
}
