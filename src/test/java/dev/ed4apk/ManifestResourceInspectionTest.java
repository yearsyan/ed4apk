package dev.ed4apk;

import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ManifestResourceInspectionTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANDROID = ManifestEditor.ANDROID;

    private AndroidManifestBlock manifest() throws Exception {
        var manifest = new AndroidManifestBlock();
        manifest.setPackageName("sample");
        manifest.setVersionCode(9);
        manifest.setVersionName("1.2");
        manifest.setMinSdkVersion(23);
        manifest.setTargetSdkVersion(35);
        manifest.getOrCreateMainActivity(".Main");
        var main = manifest.getMainActivity();
        main.removeAttribute(main.searchAttribute(ANDROID, "exported"));
        new ManifestEditor(manifest, null); // Resolve Android attribute IDs when building binary fixtures.
        return manifest;
    }

    private Path apk(AndroidManifestBlock manifest, TableBlock table) throws Exception {
        Path path = temporary.resolve(UUID.randomUUID() + ".apk");
        try (var zip = new ZipArchive(path)) {
            if (manifest != null) {
                manifest.refreshFull();
                zip.add(new BytesSource(manifest.getBytes(), "AndroidManifest.xml", 1));
            }
            if (table != null) {
                table.refresh();
                zip.add(new BytesSource(table.getBytes(), "resources.arsc", 0));
            }
            zip.add(new BytesSource(new byte[]{1}, "assets/keep", 0));
        }
        return path;
    }

    private ResXmlAttribute attribute(ResXmlElement element, String name, ValueType type, Object value) throws Exception {
        var attr = element.searchAttribute(ANDROID, name);
        if (attr == null) attr = element.newAttribute();
        attr.encodeAttributeName(ANDROID, "android", name);
        attr.setValueType(type);
        if (type == ValueType.STRING) attr.setValueAsString((String) value);
        else attr.setData((Integer) value);
        return attr;
    }

    private void launcher(ResXmlElement component) throws Exception {
        var filter = component.newElement("intent-filter");
        attribute(filter.newElement("action"), "name", ValueType.STRING, "android.intent.action.MAIN");
        attribute(filter.newElement("category"), "name", ValueType.STRING, "android.intent.category.LAUNCHER");
    }

    @Test void xmlRetainsNamespacesEscapingTypedValuesReferencesAndSelectedSubtrees() throws Exception {
        var manifest = manifest();
        var main = manifest.getMainActivity();
        attribute(main, "label", ValueType.STRING, "A & B < C \"D\"");
        attribute(main, "theme", ValueType.REFERENCE, 0x7f010123);
        attribute(main, "exported", ValueType.BOOLEAN, 0);
        var custom = main.newAttribute();
        custom.setName("marker", 0);
        custom.setNamespace("https://example.invalid/custom", "custom");
        custom.setValueAsString("custom & text");
        Path path = apk(manifest, null);
        byte[] original = Files.readAllBytes(path);
        try (var session = new ApkInspectionSession(path)) {
            var editor = ManifestEditor.inspect(session);
            assertEquals(ManifestEditor.open(path).show(null, null, null), editor.show(null, null, null));
            String fragment = editor.showXml(null, "activity", ".Main");
            var parsed = parseXml(fragment).getDocumentElement();
            assertEquals("activity", parsed.getNodeName());
            assertEquals("A & B < C \"D\"", parsed.getAttributeNS(ANDROID, "label"));
            assertEquals("false", parsed.getAttributeNS(ANDROID, "exported"));
            assertEquals("@0x7f010123", parsed.getAttributeNS(ANDROID, "theme"));
            assertEquals("custom & text", parsed.getAttributeNS("https://example.invalid/custom", "marker"));
            assertEquals(1, parsed.getElementsByTagName("intent-filter").getLength());
            assertEquals("manifest", parseXml(editor.showXml(null, null, null)).getDocumentElement().getNodeName());
            assertEquals("", editor.showXml("/manifest/application/service", null, null));
            assertEquals("manifest", parseXml(ManifestInspector.decodeXml(session.read("AndroidManifest.xml", 1024 * 1024)))
                    .getDocumentElement().getNodeName());
        }
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void multipleSelectedXmlFragmentsEachHaveTheirOwnNamespaceContextAndLiteralStringEscapes() throws Exception {
        var manifest = manifest();
        var second = manifest.getApplicationElement().newElement("activity");
        attribute(second, "name", ValueType.STRING, ".Second");
        attribute(second, "label", ValueType.STRING, "@literal");
        try (var session = new ApkInspectionSession(apk(manifest, null))) {
            String fragments = ManifestEditor.inspect(session).showXml("/manifest/application/activity", null, null);
            var parsed = parseXml("<fragments>" + fragments + "</fragments>");
            assertEquals(2, parsed.getElementsByTagName("activity").getLength());
            var element = (org.w3c.dom.Element) parsed.getElementsByTagName("activity").item(1);
            assertEquals("\\@literal", element.getAttributeNS(ANDROID, "label"));
        }
    }

    @Test void summaryDistinguishesAbsentFalseTrueAndUnresolvedDeclarationsAndFindsAliasCandidates() throws Exception {
        var manifest = manifest();
        attribute(manifest.getApplicationElement(), "debuggable", ValueType.BOOLEAN, 0);
        manifest.setExtractNativeLibs(true);
        manifest.addUsesPermission("android.permission.INTERNET");
        var alias = manifest.getApplicationElement().newElement("activity-alias");
        attribute(alias, "name", ValueType.STRING, "Alias");
        attribute(alias, "targetActivity", ValueType.STRING, ".Main");
        attribute(alias, "enabled", ValueType.REFERENCE, 0x7f020000);
        launcher(alias);
        // MAIN and category on separate filters do not constitute a launcher.
        var splitFilters = manifest.getApplicationElement().newElement("activity");
        attribute(splitFilters, "name", ValueType.STRING, ".NotALauncher");
        attribute(splitFilters.newElement("intent-filter").newElement("action"), "name", ValueType.STRING, "android.intent.action.MAIN");
        attribute(splitFilters.newElement("intent-filter").newElement("category"), "name", ValueType.STRING, "android.intent.category.LAUNCHER");
        try (var session = new ApkInspectionSession(apk(manifest, null))) {
            JsonNode data = JSON.valueToTree(ManifestInspector.summary(session));
            assertFalse(data.path("debuggable").path("declared").booleanValue());
            assertTrue(data.path("debuggable").path("present").booleanValue());
            assertTrue(data.path("extractNativeLibs").path("declared").booleanValue());
            assertFalse(data.path("applicationEnabled").path("present").booleanValue());
            assertTrue(data.path("applicationEnabled").path("declared").isNull());
            assertEquals(2, data.path("launchCandidates").size());
            var candidate = data.path("launchCandidates").get(1);
            assertEquals("activity-alias", candidate.path("kind").asText());
            assertEquals("sample.Alias", candidate.path("qualifiedName").asText());
            assertEquals("sample.Main", candidate.path("qualifiedTargetActivity").asText());
            assertTrue(candidate.path("enabled").path("present").booleanValue());
            assertTrue(candidate.path("enabled").path("declared").isNull());
            assertEquals("REFERENCE", candidate.path("enabled").path("type").asText());
            assertEquals("0x7f020000", candidate.path("enabled").path("data").asText());
            assertFalse(candidate.path("exported").path("present").booleanValue());
            assertEquals("android.permission.INTERNET", data.path("permissions").get(0).path("name").path("declared").asText());
            assertEquals(35, data.path("targetSdk").asInt());
        }
    }

    @Test void resourceInspectionKeepsEveryConfigurationAndTypedComplexValues() throws Exception {
        var table = new TableBlock();
        var pkg = table.newPackage(0x7f, "sample");
        var title = pkg.getOrCreate("", "string", "title");
        title.setValueAsString("Hello");
        pkg.getOrCreate("fr", "string", "title").setValueAsString("Bonjour");
        pkg.getOrCreate("", "string", "alias").setValueAsReference(title.getResourceId());
        pkg.getOrCreate("", "bool", "enabled").setValueAsBoolean(false);
        var style = pkg.getOrCreate("", "style", "AppTheme");
        style.ensureComplex(true);
        var bag = style.getResTableMapEntry();
        bag.setParentId(0x01030000);
        var item = style.getResValueMapArray().createNext();
        item.setNameId(0x01010000);
        item.setValueType(ValueType.REFERENCE);
        item.setData(title.getResourceId());
        Path path = apk(manifest(), table);
        byte[] original = Files.readAllBytes(path);
        try (var session = new ApkInspectionSession(path)) {
            JsonNode list = JSON.valueToTree(ResourceInspector.list(session, "string"));
            assertEquals(2, list.path("resourceCount").asInt());
            JsonNode strings = JSON.valueToTree(ResourceInspector.show(session, "@string/title"));
            assertEquals(2, strings.path("configurationCount").asInt());
            assertEquals("", strings.path("configurations").get(0).path("config").asText());
            assertEquals("Hello", strings.path("configurations").get(0).path("value").path("value").asText());
            assertEquals("-fr", strings.path("configurations").get(1).path("config").asText());
            assertEquals("Bonjour", strings.path("configurations").get(1).path("value").path("value").asText());
            assertEquals(strings, JSON.valueToTree(ResourceInspector.show(session, strings.path("id").asText())));
            assertEquals(strings, JSON.valueToTree(ResourceInspector.show(session, "@sample:string/title")));
            JsonNode reference = JSON.valueToTree(ResourceInspector.show(session, "@string/alias"));
            var refValue = reference.path("configurations").get(0).path("value");
            assertEquals("REFERENCE", refValue.path("type").asText());
            assertEquals(strings.path("id").asText(), refValue.path("data").asText());
            assertFalse(refValue.has("value"));
            JsonNode complex = JSON.valueToTree(ResourceInspector.show(session, "@style/AppTheme"));
            var config = complex.path("configurations").get(0);
            assertTrue(config.path("complex").asBoolean());
            assertEquals("BAG", config.path("value").path("type").asText());
            assertEquals("0x01030000", config.path("value").path("parentId").asText());
            assertEquals("REFERENCE", config.path("value").path("items").get(0).path("value").path("type").asText());
        }
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void absentResourcesAreEmptyForListAndExplicitFailuresForShow() throws Exception {
        try (var session = new ApkInspectionSession(apk(null, null))) {
            var result = ResourceInspector.list(session, null);
            assertEquals(false, result.get("tablePresent"));
            assertEquals(0, result.get("resourceCount"));
            assertTrue(assertThrows(java.io.IOException.class, () -> ResourceInspector.show(session, "0x7f010000"))
                    .getMessage().contains("no resources.arsc"));
            assertThrows(java.io.IOException.class, () -> ManifestInspector.summary(session));
        }
    }

    @Test void dynamicReferencesUseCorrectNotationWithoutChangingTheirBinaryTypes() throws Exception {
        var manifest = manifest();
        attribute(manifest.getMainActivity(), "theme", ValueType.DYNAMIC_REFERENCE, 0x02010000);
        attribute(manifest.getMainActivity(), "label", ValueType.DYNAMIC_ATTRIBUTE, 0x02010001);
        var table = new TableBlock();
        var pkg = table.newPackage(0x7f, "sample");
        pkg.getOrCreate("", "string", "dynamic").setValueAsRaw(ValueType.DYNAMIC_REFERENCE, 0x02010000);
        pkg.getOrCreate("", "string", "attribute").setValueAsRaw(ValueType.DYNAMIC_ATTRIBUTE, 0x02010001);
        Path path = apk(manifest, table);
        byte[] original = Files.readAllBytes(path);
        try (var session = new ApkInspectionSession(path)) {
            var editor = ManifestEditor.inspect(session);
            var before = editor.show(null, "activity", ".Main");
            assertEquals("DYNAMIC_REFERENCE", before.get(0).path("attributes").path("android:theme").path("type").asText());
            var xml = parseXml(editor.showXml(null, "activity", ".Main")).getDocumentElement();
            assertEquals("@0x02010000", xml.getAttributeNS(ANDROID, "theme"));
            assertEquals("?0x02010001", xml.getAttributeNS(ANDROID, "label"));
            assertEquals(before, editor.show(null, "activity", ".Main"));
            var fullXml = parseXml(ManifestInspector.decodeXml(session.read("AndroidManifest.xml", 1024 * 1024)));
            var activity = (org.w3c.dom.Element) fullXml.getElementsByTagName("activity").item(0);
            assertEquals("@0x02010000", activity.getAttributeNS(ANDROID, "theme"));
            assertEquals("?0x02010001", activity.getAttributeNS(ANDROID, "label"));
            for (String name : List.of("dynamic", "attribute")) {
                JsonNode data = JSON.valueToTree(ResourceInspector.show(session, "@string/" + name));
                var value = data.path("configurations").get(0).path("value");
                String expected = name.equals("dynamic") ? "@0x02010000" : "?0x02010001";
                assertEquals(expected, value.path("reference").asText());
                assertEquals(expected, value.path("decoded").asText());
                assertEquals(name.equals("dynamic") ? "DYNAMIC_REFERENCE" : "DYNAMIC_ATTRIBUTE", value.path("type").asText());
            }
        }
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test void cliReportsStructuredErrorsAndValidatesArgumentsWithoutBreakingLegacyJson() throws Exception {
        Path empty = apk(null, null);
        var missing = cli("manifest", "summary", empty.toString(), "--json");
        assertEquals(1, missing.code);
        JsonNode missingJson = JSON.readTree(missing.out);
        assertEquals(1, missingJson.path("schemaVersion").asInt());
        assertTrue(missingJson.path("data").isNull());
        assertEquals("manifest_read_failed", missingJson.path("diagnostics").get(0).path("code").asText());
        var resource = cli("resource", "show", empty.toString(), "0x7f010000", "--json");
        assertEquals(1, resource.code);
        assertTrue(JSON.readTree(resource.out).path("data").isNull());
        assertEquals(2, cli("resource", "show", empty.toString(), "nope").code);
        assertEquals(2, cli("manifest", "show", empty.toString(), "--format", "yaml").code);
        Path valid = apk(manifest(), null);
        var json = cli("manifest", "show", valid.toString());
        assertEquals(0, json.code, json.err);
        assertTrue(JSON.readTree(json.out).isArray());
        var xml = cli("manifest", "show", valid.toString(), "--format", "xml");
        assertEquals(0, xml.code, xml.err);
        assertEquals("manifest", parseXml(xml.out).getDocumentElement().getNodeName());
        var summary = cli("manifest", "summary", valid.toString());
        assertEquals(0, summary.code, summary.err);
        assertTrue(summary.out.contains("Debuggable: undeclared"));
        assertTrue(summary.out.contains("activity sample.Main exported=undeclared enabled=undeclared"));
        assertFalse(summary.out.contains("present="));
        var table = new TableBlock();
        table.newPackage(0x7f, "sample").getOrCreate("", "string", "title").setValueAsString("Hello");
        var resourceText = cli("resource", "show", apk(manifest(), table).toString(), "@string/title");
        assertEquals(0, resourceText.code, resourceText.err);
        assertTrue(resourceText.out.contains("(default) STRING Hello"));
        assertFalse(resourceText.out.contains("data="));
    }

    @Test void corruptOuterChunksAreRejectedBeforeParsing() throws Exception {
        assertThrows(java.io.IOException.class, () -> ManifestInspector.decodeXml(new byte[16]));
        var manifest = manifest();
        manifest.refreshFull();
        byte[] bytes = manifest.getBytes();
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length - 1);
        assertThrows(java.io.IOException.class, () -> ManifestInspector.decodeXml(truncated));
        Path path = temporary.resolve("broken.apk");
        try (var zip = new ZipArchive(path)) {
            zip.add(new BytesSource(truncated, "AndroidManifest.xml", 1));
            zip.add(new BytesSource(new byte[8], "resources.arsc", 1));
        }
        try (var session = new ApkInspectionSession(path)) {
            assertThrows(java.io.IOException.class, () -> ManifestInspector.overview(session));
            assertThrows(java.io.IOException.class, () -> ResourceInspector.list(session, null));
        }
    }

    private org.w3c.dom.Document parseXml(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private Result cli(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getProperty("ed4apk.java", Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.add("-jar");
        command.add(System.getProperty("ed4apk.jar"));
        command.addAll(List.of(arguments));
        Path stdout = temporary.resolve(UUID.randomUUID() + ".out");
        Path stderr = temporary.resolve(UUID.randomUUID() + ".err");
        var process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI timed out");
        return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    }

    private record Result(int code, String out, String err) {}
}
