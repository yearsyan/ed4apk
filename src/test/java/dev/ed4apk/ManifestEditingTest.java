package dev.ed4apk;

import com.android.apksig.internal.apk.AndroidBinXmlParser;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.*;
import com.reandroid.arsc.value.ValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ManifestEditingTest {
    @TempDir Path temporary;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String ANDROID = ManifestEditor.ANDROID;

    private Path fixture(boolean resources) throws Exception {
        Path apk = temporary.resolve("input.apk");
        var manifest = new AndroidManifestBlock();
        manifest.setPackageName("sample");
        manifest.setMinSdkVersion(21);
        manifest.setApplicationLabel("Original");
        manifest.getOrCreateMainActivity(".Main");
        var main = manifest.getMainActivity();
        main.setStartComment("retain comment");
        var custom = main.newAttribute();
        custom.setName("name", 0);
        custom.setNamespace("https://example.invalid/custom", "custom");
        custom.setValueAsString("unrelated");
        manifest.refresh();
        var smali = temporary.resolve("Main.smali");
        Files.writeString(smali, ".class public Lsample/Main;\n.super Ljava/lang/Object;\n");
        byte[] dex = DexEditor.writeClasses(DexEditor.importClasses(List.of(smali), 21, List.of()).values(), Opcodes.forApi(21));
        try (var zip = new ZipArchive(apk)) {
            zip.add(new BytesSource(manifest.getBytes(), "AndroidManifest.xml", 1));
            zip.add(new BytesSource(dex, "classes.dex", 1));
            zip.add(new BytesSource("keep".repeat(300).getBytes(), "assets/keep.bin", 9));
            if (resources) {
                var table = new TableBlock();
                var pkg = table.newPackage(0x7f, "sample");
                pkg.getOrCreate("", "string", "title").setValueAsString("Title");
                table.refresh();
                zip.add(new BytesSource(table.getBytes(), "resources.arsc", 0));
            }
        }
        return apk;
    }

    private Path patch(String operations) throws Exception {
        Path file = Files.createTempFile(temporary, "patch-", ".json");
        Files.writeString(file, "{\"version\":1,\"operations\":[" + operations + "]}");
        return file;
    }
    private Path apply(Path input, String operations) throws Exception {
        Path output = temporary.resolve(UUID.randomUUID() + ".apk");
        BatchEditor.apply(input, patch(operations), output, false, null);
        return output;
    }
    private ResXmlElement component(Path apk, String kind, String name) throws Exception {
        var matches = ManifestEditor.open(apk).select(null, kind, name);
        assertEquals(1, matches.size());
        return matches.get(0);
    }
    private ResXmlAttribute attr(ResXmlElement e, String name) { return e.searchAttribute(ANDROID, name); }
    private byte[] compressed(Path apk, String name) throws Exception {
        var loc = ZipArchive.listEntries(apk).get(name).getPayloadLocation();
        try (var f = new RandomAccessFile(apk.toFile(), "r")) {
            byte[] data = new byte[(int) loc.size()]; f.seek(loc.first); f.readFully(data); return data;
        }
    }

    @ParameterizedTest @ValueSource(strings = {"activity", "service", "receiver", "provider", "activity-alias"})
    void addsEditsReplacesAndDeletesEachComponentWithoutChangingOtherEntries(String kind) throws Exception {
        Path input = fixture(true);
        byte[] original = Files.readAllBytes(input);
        String extra = kind.equals("provider") ? ",\"android:authorities\":\"sample.extra\""
                : kind.equals("activity-alias") ? ",\"android:targetActivity\":\".Main\"" : "";
        Path added = apply(input, """
                {"op":"manifest.add","parent":"/manifest/application","node":{
                  "tag":"%s","attributes":{"android:name":".Extra","android:exported":false%s},
                  "children":[{"tag":"meta-data","attributes":{"android:name":"key","android:value":"value"}}]}}
                """.formatted(kind, extra));
        var extraNode = component(added, kind, "sample.Extra");
        assertEquals(ValueType.BOOLEAN, attr(extraNode, "exported").getValueType());
        assertFalse(attr(extraNode, "exported").getValueAsBoolean());
        Path updated = apply(added, """
                {"op":"manifest.update","component":"%s","name":"Extra",
                 "attributes":{"android:enabled":false,"android:permission":"sample.PERMISSION"},"removeAttributes":["android:exported"]}
                """.formatted(kind));
        extraNode = component(updated, kind, ".Extra");
        assertNull(attr(extraNode, "exported"));
        assertFalse(attr(extraNode, "enabled").getValueAsBoolean());
        assertEquals("value", attr(extraNode.getElement("meta-data"), "value").getValueAsString());
        Path replaced = apply(updated, """
                {"op":"manifest.replace","component":"%s","name":"sample.Extra","node":{
                 "tag":"%s","attributes":{"android:name":".Replacement","android:exported":true%s}}}
                """.formatted(kind, kind, extra));
        extraNode = component(replaced, kind, "Replacement");
        assertEquals(0, extraNode.getElementsCount());
        assertNull(attr(extraNode, "permission"));
        assertEquals(List.of("activity", kind), ManifestEditor.children(ResourceEditor.manifest(replaced)
                .getApplicationElement()).stream().map(ResXmlElement::getName).toList());
        Path deleted = apply(replaced, """
                {"op":"manifest.delete","component":"%s","name":".Replacement"}
                """.formatted(kind));
        assertTrue(ManifestEditor.open(deleted).select(null, kind, ".Replacement").isEmpty());
        var main = component(deleted, "activity", "Main");
        assertEquals("retain comment", main.getStartComment());
        assertEquals("unrelated", main.searchAttribute("https://example.invalid/custom", "name").getValueAsString());
        for (String entry : List.of("classes.dex", "resources.arsc", "assets/keep.bin"))
            assertArrayEquals(compressed(input, entry), compressed(deleted, entry), entry);
        assertArrayEquals(original, Files.readAllBytes(input));
    }

    @Test void editsNestedFiltersMetadataAndRootAttributesWithXpath() throws Exception {
        Path input = fixture(false);
        Path output = apply(input, """
                {"op":"manifest.update","path":"/manifest/uses-sdk","attributes":{"android:targetSdkVersion":35}},
                {"op":"manifest.update","component":"activity","name":".Main","attributes":{"android:exported":true}},
                {"op":"manifest.add","parent":"/manifest/application/activity[@android:name='.Main']/intent-filter",
                 "node":{"tag":"data","attributes":{"android:scheme":"demo","android:host":"example.org"}}},
                {"op":"manifest.replace","path":"//activity/intent-filter/category[1]",
                 "node":{"tag":"category","attributes":{"android:name":"android.intent.category.DEFAULT"}}},
                {"op":"manifest.delete","path":"//activity/intent-filter/action[@android:name='android.intent.action.MAIN']"},
                {"op":"manifest.add","parent":"/manifest","node":{"tag":"uses-permission","attributes":{"android:name":"android.permission.INTERNET"}}},
                {"op":"manifest.add","parent":"/manifest/application/activity","node":{"tag":"meta-data",
                 "attributes":{"android:name":"url","android:value":{"type":"STRING","value":"@literal"}}}}
                """);
        var manifest = ResourceEditor.manifest(output);
        assertEquals(35, manifest.getTargetSdkVersion());
        assertEquals(List.of("android.permission.INTERNET"), manifest.getUsesPermissions());
        var main = component(output, "activity", "Main");
        var filter = main.getElement("intent-filter");
        assertNull(filter.getElement("action"));
        assertEquals("android.intent.category.DEFAULT", attr(filter.getElement("category"), "name").getValueAsString());
        assertEquals("demo", attr(filter.getElement("data"), "scheme").getValueAsString());
        assertEquals("@literal", attr(main.getElement("meta-data"), "value").getValueAsString());
    }

    @Test void encodesFrameworkIdsEnumsFlagsAndReferencesReadableByIndependentAndroidParser() throws Exception {
        Path input = fixture(true);
        Path output = apply(input, """
                {"op":"manifest.update","component":"activity","name":".Main","attributes":{
                  "android:exported":true,"android:launchMode":"singleTask","android:configChanges":"orientation|screenSize",
                  "android:label":"@string/title","android:theme":"@android:style/Theme.Material"}}
                """);
        var parser = new AndroidBinXmlParser(ByteBuffer.wrap(ApkArchive.read(output, "AndroidManifest.xml")));
        Map<String, Integer> ids = new HashMap<>(), types = new HashMap<>(), data = new HashMap<>();
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.getEventType() != AndroidBinXmlParser.EVENT_START_ELEMENT || !parser.getName().equals("activity")) continue;
            for (int i = 0; i < parser.getAttributeCount(); i++) {
                if (!ANDROID.equals(parser.getAttributeNamespace(i))) continue;
                String name = parser.getAttributeName(i);
                ids.put(name, parser.getAttributeNameResourceId(i));
                int type = parser.getAttributeValueType(i);
                types.put(name, type);
                if (type == AndroidBinXmlParser.VALUE_TYPE_INT || type == AndroidBinXmlParser.VALUE_TYPE_REFERENCE)
                    data.put(name, parser.getAttributeIntValue(i));
                if (name.equals("exported")) assertTrue(parser.getAttributeBooleanValue(i));
            }
        }
        assertEquals(0x01010010, ids.get("exported"));
        assertEquals(0x0101001d, ids.get("launchMode"));
        assertEquals(0x0101001f, ids.get("configChanges"));
        assertEquals(AndroidBinXmlParser.VALUE_TYPE_BOOLEAN, types.get("exported"));
        assertEquals(2, data.get("launchMode"));
        assertEquals(0x480, data.get("configChanges"));
        var table = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(input, "resources.arsc")));
        assertEquals(table.getLocalResource("string", "title").getResourceId(), data.get("label"));
        assertEquals(0x01030224, data.get("theme"));
    }

    @Test void showNodeRoundTripsExactTypesAndNamespaces() throws Exception {
        Path input = fixture(true);
        Path edited = apply(input, """
                {"op":"manifest.update","component":"activity","name":".Main","attributes":{
                 "android:label":{"type":"STRING","value":"true"},"android:theme":{"type":"REFERENCE","data":"0x7f020001"},
                 "android:exported":false,"android:configChanges":"orientation|screenSize"}}
                """);
        var node = ManifestEditor.open(edited).show(null, "activity", ".Main").get(0);
        Path output = apply(edited, "{\"op\":\"manifest.replace\",\"component\":\"activity\",\"name\":\"Main\",\"node\":" + node + "}");
        assertEquals(node, ManifestEditor.open(output).show(null, "activity", ".Main").get(0));
        assertEquals("true", attr(component(output, "activity", "Main"), "label").getValueAsString());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void removesComponentAndClassInEitherOrderAndChecksFinalReferences(boolean deleteClassFirst) throws Exception {
        Path input = fixture(false);
        String cls = "{\"op\":\"dex.delete\",\"classes\":[\"sample.Main\"]}";
        String declaration = "{\"op\":\"manifest.delete\",\"component\":\"activity\",\"name\":\"Main\"}";
        Path output = apply(input, deleteClassFirst ? cls + "," + declaration : declaration + "," + cls);
        assertTrue(DexEditor.listClasses(output).isEmpty());
        assertNull(ResourceEditor.manifest(output).getApplicationElement().getElement("activity"));
        assertThrows(IOException.class, () -> apply(input, cls + "," + """
                {"op":"manifest.update","component":"activity","name":"Main","attributes":{"android:exported":false}}
                """));
    }

    @Test void worksWithClassRenamesAndOrderedWholeManifestResourceReplacement() throws Exception {
        Path input = fixture(true);
        Path renamed = apply(input, """
                {"op":"manifest.update","component":"activity","name":"Main","attributes":{"android:enabled":false}},
                {"op":"dex.rename","from":"sample.Main","to":"sample.Home"},
                {"op":"manifest.update","component":"activity","name":"Home","attributes":{"android:exported":true}}
                """);
        assertFalse(attr(component(renamed, "activity", ".Home"), "enabled").getValueAsBoolean());
        Path manifest = temporary.resolve("original.bin"), resources = temporary.resolve("resources.bin");
        Files.write(manifest, ApkArchive.read(input, "AndroidManifest.xml"));
        Files.write(resources, ApkArchive.read(input, "resources.arsc"));
        Path replaced = apply(renamed, """
                {"op":"manifest.delete","component":"activity","name":"Home"},
                {"op":"file.replace","path":"AndroidManifest.xml","source":"original.bin"},
                {"op":"file.replace","path":"resources.arsc","source":"resources.bin"},
                {"op":"manifest.update","component":"activity","name":"Main","attributes":{"android:label":"@string/title"}}
                """);
        assertEquals(ValueType.REFERENCE, attr(component(replaced, "activity", "Main"), "label").getValueType());
    }

    @Test void rejectsAmbiguousMissingInvalidOperationsAndPreservesExistingOutput() throws Exception {
        Path input = fixture(false);
        byte[] original = Files.readAllBytes(input);
        List<String> invalid = List.of(
                "{\"op\":\"manifest.delete\",\"path\":\"//*\"}",
                "{\"op\":\"manifest.delete\",\"path\":\"/manifest/application/service\"}",
                "{\"op\":\"manifest.delete\",\"path\":\"/manifest\"}",
                "{\"op\":\"manifest.delete\",\"path\":\"//activity/@android:name\"}",
                "{\"op\":\"manifest.delete\",\"path\":\"//activity[\"}",
                "{\"op\":\"manifest.delete\",\"path\":\"//activity\",\"component\":\"activity\",\"name\":\"Main\"}",
                "{\"op\":\"manifest.delete\",\"component\":\"unknown\",\"name\":\"Main\"}",
                "{\"op\":\"manifest.add\",\"parent\":\"/manifest/application\",\"node\":{\"tag\":\"activity\",\"attributes\":{\"android:name\":\"sample.Main\"}}}",
                "{\"op\":\"manifest.add\",\"parent\":\"/manifest\",\"node\":{\"tag\":\"service\",\"attributes\":{\"android:name\":\".Bad\"}}}",
                "{\"op\":\"manifest.add\",\"parent\":\"/manifest/application\",\"node\":{\"tag\":\"activity\"}}",
                "{\"op\":\"manifest.add\",\"parent\":\"/manifest/application\",\"node\":{\"tag\":\"service\",\"children\":{}}}",
                "{\"op\":\"manifest.add\",\"parent\":\"/manifest/application\",\"node\":{\"tag\":\"service\",\"typo\":true}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\"}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":[]}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:exported\":null}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:exported\":\"invalid\"}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:launchMode\":\"invalid\"}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:label\":\"@string/missing\"}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:typo\":true}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"unknown:name\":\"x\"}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:enabled\":{\"type\":\"BOOLEAN\",\"data\":4294967296}}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:enabled\":{\"type\":\"BOOLEAN\",\"data\":1,\"typo\":1}}}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"removeAttributes\":[\"android:missing\"]}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"removeAttributes\":[\"android:name\"]}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:name\":\".New\"},\"removeAttributes\":[\"android:name\"]}",
                "{\"op\":\"manifest.update\",\"path\":\"//activity\",\"attributes\":{\"android:enabled\":true,\"android:enabled\":false}}"
        );
        Path output = temporary.resolve("keep.apk");
        Files.writeString(output, "keep existing output");
        for (String operation : invalid) {
            var error = assertThrows(IOException.class, () -> BatchEditor.apply(input,
                    patch("{\"op\":\"manifest.set\",\"label\":\"partial\"}," + operation), output, true, null), operation);
            assertTrue(error.getMessage().contains("Operation #2") || error.getMessage().contains("Duplicate field"), error.getMessage());
            assertEquals("keep existing output", Files.readString(output), operation);
        }
        assertArrayEquals(original, Files.readAllBytes(input));
    }

    @Test void editsProviderChildrenAndKeepsReplacementBeforeItsAlias() throws Exception {
        Path input = fixture(false);
        Path output = apply(input, """
                {"op":"manifest.add","parent":"/manifest/application","node":{"tag":"activity-alias",
                 "attributes":{"android:name":".Launcher","android:targetActivity":".Main","android:exported":true}}},
                {"op":"manifest.replace","component":"activity","name":"Main","node":{"tag":"activity","attributes":{"android:name":".Main"}}},
                {"op":"manifest.add","parent":"/manifest/application","node":{"tag":"provider",
                 "attributes":{"android:name":".Files","android:authorities":"sample.files","android:exported":false,"android:grantUriPermissions":true,"android:initOrder":10},
                 "children":[{"tag":"grant-uri-permission","attributes":{"android:pathPrefix":"/shared/"}},
                  {"tag":"path-permission","attributes":{"android:path":"/private","android:readPermission":"sample.READ"}}]}},
                {"op":"manifest.update","path":"//provider/path-permission[@android:path='/private']","attributes":{"android:writePermission":"sample.WRITE"}}
                """);
        var children = ManifestEditor.children(ResourceEditor.manifest(output).getApplicationElement());
        assertEquals(List.of("activity", "activity-alias", "provider"), children.stream().map(ResXmlElement::getName).toList());
        var provider = component(output, "provider", "Files");
        assertEquals(10, attr(provider, "initOrder").getData());
        assertEquals("/shared/", attr(provider.getElement("grant-uri-permission"), "pathPrefix").getValueAsString());
        assertEquals("sample.WRITE", attr(provider.getElement("path-permission"), "writePermission").getValueAsString());
        assertThrows(IOException.class, () -> apply(output, """
                {"op":"manifest.update","component":"activity-alias","name":"Launcher","attributes":{"android:name":"sample.Main"}}
                """));
    }

    @Test void allowsXpathEditsOfResourceNamedComponentsWithoutInliningTheirNames() throws Exception {
        Path input = fixture(true);
        Path output = apply(input, """
                {"op":"manifest.update","component":"activity","name":"Main","attributes":{"android:name":"@string/title"}},
                {"op":"manifest.update","path":"/manifest/application/activity","attributes":{"android:enabled":false}}
                """);
        var activity = ResourceEditor.manifest(output).getApplicationElement().getElement("activity");
        assertEquals(ValueType.REFERENCE, attr(activity, "name").getValueType());
        assertFalse(attr(activity, "enabled").getValueAsBoolean());
        assertArrayEquals(ApkArchive.read(input, "resources.arsc"), ApkArchive.read(output, "resources.arsc"));
    }

    @Test void supportsAndroidNamespaceAliasesAndRejectsConflictingExpandedAttributes() throws Exception {
        Path input = fixture(false);
        Path added = apply(input, """
                {"op":"manifest.add","parent":"/manifest/application","node":{"tag":"service",
                 "namespaces":{"a":"http://schemas.android.com/apk/res/android"},
                 "attributes":{"a:name":".Extra","a:exported":false}}}
                """);
        assertEquals(0x01010010, attr(component(added, "service", "Extra"), "exported").getNameId());
        assertThrows(IOException.class, () -> apply(added, """
                {"op":"manifest.update","component":"service","name":"Extra",
                 "attributes":{"a:enabled":true,"android:enabled":false}}
                """));
        assertThrows(IOException.class, () -> apply(added, """
                {"op":"manifest.update","component":"service","name":"Extra",
                 "attributes":{"a:exported":true},"removeAttributes":["android:exported"]}
                """));
    }

    private String jar(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(System.getProperty("ed4apk.java",
                Path.of(System.getProperty("java.home"), "bin", "java").toString()), "-jar", System.getProperty("ed4apk.jar")));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporary, "cli-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        String text = Files.readString(log);
        assertEquals(0, process.exitValue(), text);
        return text;
    }

    @Test void standaloneJarOffersAllCommandsAndBundledExample() throws Exception {
        Path input = fixture(false), node = temporary.resolve("service.json");
        Files.writeString(node, """
                {"tag":"service","attributes":{"android:name":".Extra","android:exported":false}}
                """);
        Path added = temporary.resolve("added.apk"), updated = temporary.resolve("updated.apk"), replaced = temporary.resolve("replaced.apk"), deleted = temporary.resolve("deleted.apk");
        jar("manifest", "add", input.toString(), "--node", node.toString(), "-o", added.toString());
        jar("manifest", "update", added.toString(), "--component", "service", "--name", "Extra", "--attribute", "android:enabled=false", "--remove-attribute", "android:exported", "-o", updated.toString());
        var shown = JSON.readTree(jar("manifest", "show", updated.toString(), "--component", "service", "--name", ".Extra"));
        assertEquals(1, shown.size());
        assertEquals("BOOLEAN", shown.get(0).path("attributes").path("android:enabled").path("type").asText());
        jar("manifest", "replace", updated.toString(), "--path", "//service", "--node", node.toString(), "-o", replaced.toString());
        jar("manifest", "delete", replaced.toString(), "--component", "service", "--name", "sample.Extra", "-o", deleted.toString());
        assertEquals(0, JSON.readTree(jar("manifest", "show", deleted.toString(), "--path", "//service")).size());
        assertTrue(jar("--help-all").contains("manifest.update"));
        assertEquals("activity", JSON.readTree(jar("examples", "manifest", "--file", "activity.json")).path("tag").asText());
    }
}
