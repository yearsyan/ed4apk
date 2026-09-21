package dev.ed4apk;

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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ClassRenamingTest {
    @TempDir Path temporary;
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    static final String APP = "http://schemas.android.com/apk/res-auto";
    static final String TARGET = ".class public Lsample/Target;\n.super Ljava/lang/Object;\n";

    static class Fixture {
        final AndroidManifestBlock manifest = new AndroidManifestBlock();
        final TableBlock table = new TableBlock();
        final Map<String, ResXmlDocument> xml = new LinkedHashMap<>();
        Fixture() {
            manifest.setPackageName("sample");
            manifest.setMinSdkVersion(28);
            manifest.setApplicationLabel("sample.Target");
            table.newPackage(0x7f, "sample");
        }
        ResXmlElement xml(String path, String tag) {
            ResXmlDocument doc = new ResXmlDocument();
            xml.put(path, doc);
            return doc.newElement(tag);
        }
    }

    Path source(String text) throws IOException {
        Path file = Files.createTempFile(temporary, "source-", ".smali");
        Files.writeString(file, text);
        return file;
    }
    byte[] dex(String text) throws Exception {
        return DexEditor.writeClasses(DexEditor.importClasses(List.of(source(text)), 28, List.of()).values(), Opcodes.forApi(28));
    }
    Path fixture(Consumer<Fixture> configure) throws Exception {
        Fixture f = new Fixture();
        configure.accept(f);
        Path input = temporary.resolve(UUID.randomUUID() + ".apk");
        try (ZipArchive zip = new ZipArchive(input)) {
            f.manifest.refresh(); f.table.refresh();
            zip.add(new BytesSource(f.manifest.getBytes(), "AndroidManifest.xml", 1));
            zip.add(new BytesSource(f.table.getBytes(), "resources.arsc", 0));
            zip.add(new BytesSource(dex(TARGET), "classes.dex", 1));
            zip.add(new BytesSource(dex(TARGET.replace("Target", "Untouched") +
                    ".field public static final name:Ljava/lang/String; = \"sample.Target\"\n"), "classes2.dex", 1));
            zip.add(new BytesSource("<sample.Target/>".getBytes(), "assets/config.xml", 1));
            for (var item : f.xml.entrySet()) {
                item.getValue().refresh();
                zip.add(new BytesSource(item.getValue().getBytes(), item.getKey(), 1));
            }
        }
        return input;
    }
    static ResXmlAttribute attribute(ResXmlElement element, String uri, String name, String value) {
        ResXmlAttribute attr = element.newAttribute(name, ANDROID.equals(uri) && name.equals("name") ? 0x01010003 : 0);
        if (!uri.isEmpty()) attr.setNamespace(uri, ANDROID.equals(uri) ? "android" : "app");
        attr.setValueAsString(value);
        return attr;
    }
    static void reference(ResXmlAttribute attribute, int id) {
        attribute.setValueType(ValueType.REFERENCE); attribute.setData(id);
    }
    static ResXmlDocument xml(Path apk, String path) throws IOException {
        ResXmlDocument doc = new ResXmlDocument();
        doc.readBytes(new ByteArrayInputStream(ApkArchive.read(apk, path)));
        return doc;
    }
    @SafeVarargs final Path patch(Map<String, Object>... operations) throws IOException {
        Path file = Files.createTempFile(temporary, "patch-", ".json");
        new ObjectMapper().writeValue(file.toFile(), Map.of("version", 1, "operations", operations));
        return file;
    }
    Map<String, Object> rename(String from, String to) { return Map.of("op", "dex.rename", "from", from, "to", to); }

    static Stream<Arguments> xmlSlots() {
        return Stream.of(
                Arguments.of("layout", "sample.Target", "", "", "sample.Target", "sample.Renamed"),
                Arguments.of("layout", "view", "", "class", "sample.Target", "sample.Renamed"),
                Arguments.of("layout", "fragment", "", "class", "sample.Target", "sample.Renamed"),
                Arguments.of("layout", "fragment", ANDROID, "name", "sample.Target", "sample.Renamed"),
                Arguments.of("layout", "androidx.fragment.app.FragmentContainerView", ANDROID, "name", "sample.Target", "sample.Renamed"),
                Arguments.of("layout", "View", APP, "layout_behavior", ".Target", "sample.Renamed"),
                Arguments.of("layout", "androidx.recyclerview.widget.RecyclerView", APP, "layoutManager", "sample.Target", "sample.Renamed"),
                Arguments.of("navigation", "fragment", ANDROID, "name", ".Target", "sample.Renamed"),
                Arguments.of("navigation", "dialog", ANDROID, "name", "sample.Target", "sample.Renamed"),
                Arguments.of("navigation", "activity", ANDROID, "name", ".Target", "sample.Renamed"),
                Arguments.of("navigation", "argument", APP, "argType", "sample.Target[]", "sample.Renamed[]"),
                Arguments.of("xml", "sample.Target", "", "", "sample.Target", "sample.Renamed"),
                Arguments.of("xml", "Preference", ANDROID, "fragment", "sample.Target", "sample.Renamed"),
                Arguments.of("xml", "Preference", APP, "fragment", "sample.Target", "sample.Renamed"),
                Arguments.of("xml", "intent", ANDROID, "targetClass", "sample.Target", "sample.Renamed"));
    }

    @ParameterizedTest(name = "{0}/{1} {3}") @MethodSource("xmlSlots")
    void rewritesKnownXmlSlotsAndProtectsDeletion(String role, String tag, String uri, String name, String value, String expected) throws Exception {
        String path = "res/" + role + "/screen.xml";
        Path input = fixture(f -> {
            var root = f.xml(path, tag);
            if (!name.isEmpty()) attribute(root, uri, name, value);
            attribute(root, "", "unrelated", "sample.Target");
            attribute(root, "https://example.invalid/custom", "name", "sample.Target");
        });
        var hits = BatchEditor.references(input, "sample.Target");
        assertEquals(2, hits.size());
        assertEquals(1, hits.stream().filter(h -> h.definition).count());
        assertTrue(hits.stream().anyMatch(h -> h.entry.equals(path) && h.owner == null));
        Path deleted = temporary.resolve("deleted.apk");
        IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(input,
                PatchPlan.deleteClasses(List.of("sample.Target")), deleted, false, null));
        assertTrue(error.getMessage().contains(path), error.getMessage());
        assertFalse(Files.exists(deleted));
        BatchEditor.apply(input, PatchPlan.deleteClasses(List.of("sample.Target"), true), deleted, false, null);
        Path output = temporary.resolve("renamed.apk");
        var result = BatchEditor.apply(input, PatchPlan.renameClass("sample.Target", "sample.Renamed"), output, false, null);
        assertEquals(1, result.rebuiltDex());
        var root = xml(output, path).getDocumentElement();
        assertEquals(expected, name.isEmpty() ? root.getName() : root.searchAttribute(uri.isEmpty() ? null : uri, name).getValueAsString());
        assertEquals("sample.Target", root.searchAttributeByName("unrelated").getValueAsString());
        assertTrue(BatchEditor.references(output, "sample.Target").isEmpty());
        assertEquals(2, BatchEditor.references(output, "sample.Renamed").size());
        for (String entry : List.of("classes2.dex", "resources.arsc", "assets/config.xml", "AndroidManifest.xml"))
            assertArrayEquals(compressed(input, entry), compressed(output, entry), entry);
    }

    @Test void renamesManifestRelativeNamesButPreservesAliasesLabelsPermissionsAndMetadata() throws Exception {
        Path input = fixture(f -> {
            f.manifest.getOrCreateMainActivity(".Target");
            var app = f.manifest.getApplicationElement();
            attribute(app, ANDROID, "name", "Target");
            attribute(app, ANDROID, "backupAgent", "sample.Target");
            var alias = app.newElement("activity-alias");
            attribute(alias, ANDROID, "name", "sample.Target");
            attribute(alias, ANDROID, "targetActivity", ".Target");
            attribute(alias, ANDROID, "permission", "sample.Target");
            attribute(app.newElement("meta-data"), ANDROID, "name", "sample.Target");
        });
        Path output = temporary.resolve("manifest.apk");
        BatchEditor.apply(input, PatchPlan.renameClass("sample.Target", "Lsample/Renamed;"), output, false, null);
        assertTrue(BatchEditor.references(output, "sample.Target").isEmpty());
        assertEquals(5, BatchEditor.references(output, "sample.Renamed").size());
        var manifest = ResourceEditor.manifest(output);
        assertEquals("sample.Target", manifest.getApplicationLabelString());
        var children = manifest.getApplicationElement().getElements();
        while (children.hasNext()) {
            var child = children.next();
            if (child.getName().equals("activity-alias")) {
                assertEquals("sample.Target", child.searchAttributeByName("name").getValueAsString());
                assertEquals("sample.Renamed", child.searchAttributeByName("targetActivity").getValueAsString());
                assertEquals("sample.Target", child.searchAttributeByName("permission").getValueAsString());
            }
        }
    }

    @Test void scansAllConfigurationsAndObfuscatedPathsAndInlinesOnlyTheClassAttribute() throws Exception {
        Path input = fixture(f -> {
            var pkg = f.table.iterator().next();
            var string = pkg.getOrCreate("", "string", "class_name"); string.setValueAsString("sample.Target");
            pkg.getOrCreate("-en", "string", "class_name").setValueAsString(".Target");
            var alias = pkg.getOrCreate("", "string", "alias"); alias.setValueAsReference(string.getResourceId());
            pkg.getOrCreate("", "navigation", "screen").setValueAsString("r/a.bin");
            var root = f.xml("r/a.bin", "fragment");
            reference(attribute(root, ANDROID, "name", ""), alias.getResourceId());
            reference(attribute(root, ANDROID, "label", ""), alias.getResourceId());
            f.xml("res/layout/screen.xml", "sample.Target");
            f.xml("res/layout-land/screen.xml", "sample.Target");
        });
        Path output = temporary.resolve("configs.apk");
        assertEquals(5, BatchEditor.references(input, "sample.Target").size()); // Two string configurations, two tags, definition.
        BatchEditor.apply(input, PatchPlan.renameClass("sample.Target", "sample.Renamed"), output, false, null);
        var root = xml(output, "r/a.bin").getDocumentElement();
        assertEquals("sample.Renamed", root.searchAttributeByName("name").getValueAsString());
        assertEquals(ValueType.REFERENCE, root.searchAttributeByName("label").getValueType());
        assertArrayEquals(ApkArchive.read(input, "resources.arsc"), ApkArchive.read(output, "resources.arsc"));
        assertTrue(BatchEditor.references(output, "sample.Target").isEmpty());
        assertEquals(4, BatchEditor.references(output, "sample.Renamed").size());
    }

    @Test void rejectsAmbiguousMissingCyclicAndThemeClassResourcesWithoutPublishing() throws Exception {
        for (String mode : List.of("ambiguous", "missing", "cycle", "theme")) {
            Path input = fixture(f -> {
                var pkg = f.table.iterator().next();
                var string = pkg.getOrCreate("", "string", "class_name"); string.setValueAsString("sample.Target");
                if (mode.equals("ambiguous")) pkg.getOrCreate("-en", "string", "class_name").setValueAsString("sample.Untouched");
                if (mode.equals("cycle")) string.setValueAsReference(string.getResourceId());
                var attr = attribute(f.xml("res/layout/screen.xml", "view"), "", "class", "");
                reference(attr, mode.equals("missing") ? 0x7fffffff : string.getResourceId());
                if (mode.equals("theme")) attr.setValueType(ValueType.ATTRIBUTE);
            });
            Path output = temporary.resolve(mode + ".apk"); Files.writeString(output, "keep");
            assertThrows(IOException.class, () -> BatchEditor.apply(input,
                    PatchPlan.renameClass("sample.Target", "sample.Renamed"), output, true, null), mode);
            assertEquals("keep", Files.readString(output));
        }
    }

    @Test void renameChainsAndWholeFileEditsUseFinalOverlayAndRejectStaleReferences() throws Exception {
        String path = "res/layout/screen.xml";
        Path input = fixture(f -> f.xml(path, "sample.Target"));
        Path originalXml = temporary.resolve("original.xml"); Files.write(originalXml, ApkArchive.read(input, path));
        var first = rename("sample.Target", "sample.Middle");
        var second = rename("sample.Middle", "sample.Final");
        Path chain = temporary.resolve("chain.apk");
        BatchEditor.apply(input, patch(first, second), chain, false, null);
        assertEquals("sample.Final", xml(chain, path).getDocumentElement().getName());
        assertTrue(BatchEditor.references(chain, "sample.Target").isEmpty());
        assertTrue(BatchEditor.references(chain, "sample.Middle").isEmpty());
        Path output = temporary.resolve("overlay.apk"); Files.writeString(output, "keep");
        IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(input,
                patch(first, Map.of("op", "file.replace", "path", path, "source", originalXml.toString())), output, true, null));
        assertTrue(error.getMessage().contains("References to renamed classes remain"), error.getMessage());
        assertEquals("keep", Files.readString(output));
        BatchEditor.apply(input, patch(first, Map.of("op", "file.delete", "path", path)), output, true, null);
        assertFalse(ZipArchive.listEntries(output).containsKey(path));
        // Explicitly restoring the old class makes later references to it valid again.
        BatchEditor.apply(input, patch(first, Map.of("op", "dex.add", "inputs", List.of(source(TARGET).toString())),
                Map.of("op", "file.replace", "path", path, "source", originalXml.toString())), output, true, null);
        assertEquals(2, BatchEditor.references(output, "sample.Target").size());
    }

    @Test void renameAddReplaceAndDeleteComposeInOneTransaction() throws Exception {
        Path input = fixture(f -> {}), output = temporary.resolve("batch.apk");
        Path added = source(TARGET.replace("Target", "Helper"));
        Path replaced = source(TARGET.replace("Target", "Renamed") + ".field public helper:Lsample/Helper;\n");
        BatchEditor.apply(input, patch(rename("sample.Target", "sample.Renamed"),
                Map.of("op", "dex.add", "inputs", List.of(added.toString())),
                Map.of("op", "dex.replace", "inputs", List.of(replaced.toString())),
                Map.of("op", "dex.delete", "classes", List.of("sample.Untouched"))), output, false, null);
        assertEquals(Set.of("classes.dex | Lsample/Renamed;", "classes.dex | Lsample/Helper;"), new HashSet<>(DexEditor.listClasses(output)));
        assertTrue(DexEditor.exportClass(output, "sample.Renamed").contains("helper:Lsample/Helper;"));
    }

    @Test void validatesReferencesFromDexAndResourceEditsMadeAfterRename() throws Exception {
        Path input = fixture(f -> f.xml("res/layout/screen.xml", "sample.Target"));
        Path caller = source(TARGET.replace("Target", "Untouched") + ".field public ref:Lsample/Target;\n");
        Path rawDex = temporary.resolve("caller.dex");
        Files.write(rawDex, dex(Files.readString(caller)));
        for (var later : List.<Map<String, Object>>of(Map.of("op", "dex.replace", "inputs", List.of(caller.toString())),
                Map.of("op", "file.replace", "path", "classes2.dex", "source", rawDex.toString()))) {
            Path output = temporary.resolve(UUID.randomUUID() + ".apk");
            IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(input,
                    patch(rename("sample.Target", "sample.Renamed"), later), output, false, null));
            assertTrue(error.getMessage().contains("classes2.dex"), error.getMessage());
            assertFalse(Files.exists(output));
        }
        Path indirect = fixture(f -> {
            var string = f.table.iterator().next().getOrCreate("", "string", "class_name");
            string.setValueAsString("sample.External");
            reference(attribute(f.xml("res/layout/screen.xml", "view"), "", "class", ""), string.getResourceId());
        });
        var table = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(indirect, "resources.arsc")));
        String id = String.format("0x%08x", table.getLocalResource("string", "class_name").getResourceId());
        Path output = temporary.resolve("indirect.apk");
        assertThrows(IOException.class, () -> BatchEditor.apply(indirect,
                patch(rename("sample.Target", "sample.Renamed"),
                        Map.of("op", "resource.set-string", "id", id, "value", "sample.Target")), output, false, null));
        assertFalse(Files.exists(output));
        BatchEditor.apply(indirect, patch(Map.of("op", "resource.set-string", "id", id, "value", "sample.Target"),
                rename("sample.Target", "sample.Renamed")), output, false, null);
        assertEquals("sample.Renamed", xml(output, "res/layout/screen.xml").getDocumentElement()
                .searchAttributeByName("class").getValueAsString());
    }

    @Test void rejectsConflictsCrossPackageMissingAndAlreadyReferencedDestinations() throws Exception {
        Path input = fixture(f -> attribute(f.xml("res/layout/screen.xml", "view"), "", "class", "sample.External"));
        Path output = temporary.resolve("conflict.apk"); Files.writeString(output, "keep");
        for (String[] names : List.of(new String[]{"sample.Target", "sample.Untouched"},
                new String[]{"sample.Target", "other.Target"}, new String[]{"sample.Target", "sample.External"},
                new String[]{"sample.Missing", "sample.New"}, new String[]{"sample.Target", "sample.Target"},
                new String[]{"sample.Target", "[Lsample/Array;"}, new String[]{"sample.Target", "sample.Bad(Name"},
                new String[]{"sample.Target", "sample.Bad\u0000Name"})) {
            assertThrows(IOException.class, () -> BatchEditor.apply(input, PatchPlan.renameClass(names[0], names[1]), output, true, null));
            assertEquals("keep", Files.readString(output));
        }
    }

    @Test void standaloneJava11JarQueriesPreviewsRenamesAndExposesOfflineHelp() throws Exception {
        Path input = fixture(f -> f.manifest.getOrCreateMainActivity(".Target"));
        byte[] before = Files.readAllBytes(input);
        ObjectMapper mapper = new ObjectMapper();
        var hits = mapper.readTree(cli(0, "dex", "refs", input.toString(), "sample.Target", "--json"));
        assertEquals(2, hits.size());
        assertTrue(mapper.readTree(cli(0, "dex", "refs", input.toString(), "sample.Missing", "--json")).isEmpty());
        var preview = mapper.readTree(cli(0, "dex", "rename", input.toString(), "sample.Target", "sample.Renamed", "--dry-run", "--json"));
        assertEquals(2, preview.path("changedEntries").size());
        assertEquals(1, preview.path("rebuiltDex").intValue());
        assertArrayEquals(before, Files.readAllBytes(input));
        Path output = temporary.resolve("cli.apk");
        cli(1, "dex", "rename", input.toString(), "sample.Target", "sample.Renamed", "--dry-run", "-o", output.toString());
        assertFalse(Files.exists(output));
        cli(0, "dex", "rename", input.toString(), "sample.Target", "sample.Renamed", "-o", output.toString());
        assertTrue(BatchEditor.references(output, "sample.Target").isEmpty());
        Path plan = patch(rename("sample.Renamed", "sample.Last"));
        cli(0, "apply", output.toString(), "--patch", plan.toString(), "-o", temporary.resolve("last.apk").toString());
        assertTrue(cli(0, "--help-all").contains("dex.rename"));
        assertTrue(cli(0, "dex", "rename", "--help").contains("--dry-run"));
    }

    byte[] compressed(Path apk, String name) throws IOException {
        var location = ZipArchive.listEntries(apk).get(name).getPayloadLocation();
        try (RandomAccessFile file = new RandomAccessFile(apk.toFile(), "r")) {
            byte[] bytes = new byte[Math.toIntExact(location.size())];
            file.seek(location.first); file.readFully(bytes); return bytes;
        }
    }
    String cli(int expected, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(System.getProperty("ed4apk.java",
                Path.of(System.getProperty("java.home"), "bin", "java").toString()), "-jar", System.getProperty("ed4apk.jar")));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporary, "cli-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        for (String key : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT", "CLASSPATH")) builder.environment().remove(key);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("CLI timed out"); }
        String result = Files.readString(log);
        assertEquals(expected, process.exitValue(), result);
        return result;
    }
}
