package dev.ed4apk;

import com.android.zipflinger.*;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import static org.junit.jupiter.api.Assertions.*;

class EditorIntegrationTest {
    @TempDir Path temporary;

    private Path smali(String name, int value) throws IOException {
        Path file = temporary.resolve(name + "-" + value + ".smali");
        Files.writeString(file, """
                .class public Lsample/%s;
                .super Ljava/lang/Object;

                .method public static value()I
                    .registers 1
                    const/16 v0, %d
                    return v0
                .end method
                """.formatted(name, value));
        return file;
    }

    private byte[] dex(String name, int value) throws Exception {
        var classes = DexEditor.importClasses(List.of(smali(name, value)), 21, List.of());
        return DexEditor.writeClasses(classes.values(), com.android.tools.smali.dexlib2.Opcodes.forApi(21));
    }

    private Path fixture() throws Exception {
        Path apk = temporary.resolve("original.apk");
        AndroidManifestBlock manifest = new AndroidManifestBlock();
        manifest.setPackageName("dev.ed4apk.fixture");
        manifest.setMinSdkVersion(21);
        manifest.setTargetSdkVersion(28);
        manifest.setVersionCode(1);
        manifest.setVersionName("1.0");
        manifest.setApplicationLabel("Original");
        manifest.refreshFull();
        TableBlock table = new TableBlock();
        var pkg = table.newPackage(0x7f, "dev.ed4apk.fixture");
        pkg.getOrCreate("", "string", "title").setValueAsString("Default");
        pkg.getOrCreate("-en", "string", "title").setValueAsString("English");
        table.refresh();
        try (ZipArchive zip = new ZipArchive(apk)) {
            zip.add(new BytesSource(manifest.getBytes(), "AndroidManifest.xml", 1));
            zip.add(new BytesSource(table.getBytes(), "resources.arsc", 0));
            zip.add(new BytesSource(dex("Main", 1), "classes.dex", 1));
            zip.add(new BytesSource(dex("Untouched", 7), "classes2.dex", 1));
            zip.add(new BytesSource("old".getBytes(), "assets/config.json", 1));
            zip.add(new BytesSource("unchanged".repeat(1000).getBytes(), "assets/keep.bin", 9));
            zip.add(new BytesSource(new byte[120], "lib/arm64-v8a/libdemo.so", 0));
            zip.add(new BytesSource("stale".getBytes(), "META-INF/OLD.SF", 1));
            zip.add(new BytesSource("stale".getBytes(), "META-INF/MANIFEST.MF", 1));
            zip.add(new BytesSource("keep-service".getBytes(), "META-INF/services/example", 1));
        }
        return apk;
    }

    private byte[] compressedPayload(Path apk, String name) throws IOException {
        var location = ZipArchive.listEntries(apk).get(name).getPayloadLocation();
        try (RandomAccessFile file = new RandomAccessFile(apk.toFile(), "r")) {
            byte[] bytes = new byte[Math.toIntExact(location.size())];
            file.seek(location.first);
            file.readFully(bytes);
            return bytes;
        }
    }

    @Test void replacesFilesWithoutRecompressingUnchangedEntries() throws Exception {
        Path input = fixture();
        byte[] original = Files.readAllBytes(input);
        Path replacement = temporary.resolve("config.json");
        Files.writeString(replacement, "new config");
        Path output = temporary.resolve("files.apk");
        ApkArchive.rewrite(input, output, Map.of("assets/config.json", ApkArchive.Replacement.file(replacement),
                "lib/arm64-v8a/libdemo.so", ApkArchive.Replacement.bytes(new byte[8192])), false);
        assertEquals("new config", new String(ApkArchive.read(output, "assets/config.json")));
        assertArrayEquals(compressedPayload(input, "assets/keep.bin"), compressedPayload(output, "assets/keep.bin"));
        assertArrayEquals(compressedPayload(input, "classes2.dex"), compressedPayload(output, "classes2.dex"));
        assertArrayEquals(original, Files.readAllBytes(input));
        var entries = ZipArchive.listEntries(output);
        assertFalse(entries.containsKey("META-INF/OLD.SF"));
        assertFalse(entries.containsKey("META-INF/MANIFEST.MF"));
        assertTrue(entries.containsKey("META-INF/services/example"));
        assertEquals(0, entries.get("lib/arm64-v8a/libdemo.so").getPayloadLocation().first % 16384);
        ApkArchive.checkAlignment(output);
    }

    @Test void replacesAndAddsClassesWhilePreservingOtherDex() throws Exception {
        Path input = fixture();
        Path replacement = temporary.resolve("replaced.apk");
        DexEditor.edit(input, replacement, List.of(smali("Main", 42)), false, null, 21, List.of(), false);
        assertTrue(DexEditor.exportClass(replacement, "sample.Main").contains("0x2a"));
        assertArrayEquals(compressedPayload(input, "classes2.dex"), compressedPayload(replacement, "classes2.dex"));
        Path added = temporary.resolve("added.apk");
        DexEditor.edit(replacement, added, List.of(smali("Added", 23)), true, "classes.dex", 21, List.of(), false);
        assertEquals(3, DexEditor.listClasses(added).size());
        assertTrue(DexEditor.exportClass(added, "sample.Added").contains("0x17"));
        assertTrue(DexEditor.exportClass(added, "sample.Main").contains("0x2a"));
        assertThrows(IOException.class, () -> DexEditor.edit(added, temporary.resolve("collision.apk"),
                List.of(smali("Main", 99)), true, "classes.dex", 21, List.of(), false));
        assertFalse(Files.exists(temporary.resolve("collision.apk")));
    }

    @Test void compilesJvmClassWithBundledD8() throws Exception {
        Path input = fixture();
        Path source = temporary.resolve("Extra.java");
        Files.writeString(source, "package sample; public class Extra { public static int value() { return 99; } }");
        Path classes = Files.createDirectory(temporary.resolve("jvm"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-d", classes.toString(), source.toString()));
        Path output = temporary.resolve("jvm.apk");
        DexEditor.edit(input, output, List.of(classes.resolve("sample/Extra.class")), true,
                "classes.dex", 21, List.of(), false);
        assertTrue(DexEditor.exportClass(output, "sample.Extra").contains("0x63"));
    }

    @Test void changesOneResourceConfigurationAndManifest() throws Exception {
        Path input = fixture();
        var before = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(input, "resources.arsc")));
        var resource = before.getLocalResource("string", "title");
        int id = resource.getResourceId();
        Path output = temporary.resolve("resource.apk");
        ResourceEditor.setString(input, output, id, "en", "New English", false);
        var after = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(output, "resources.arsc")));
        var entries = after.getEntries(id);
        Map<String, String> values = new HashMap<>();
        entries.forEachRemaining(e -> values.put(e.getResConfig().getQualifiers(), e.getValueAsString()));
        assertEquals("Default", values.get(""));
        assertEquals("New English", values.get("-en"));
        assertArrayEquals(compressedPayload(input, "classes.dex"), compressedPayload(output, "classes.dex"));
        Path manifestOutput = temporary.resolve("manifest.apk");
        ResourceEditor.setManifest(output, manifestOutput, "New Label", "2.0", 2, false);
        var manifest = ResourceEditor.manifest(manifestOutput);
        assertEquals("dev.ed4apk.fixture", manifest.getPackageName());
        assertEquals(21, manifest.getMinSdkVersion());
        assertEquals("New Label", manifest.getApplicationLabelString());
        assertEquals("2.0", manifest.getVersionName());
        assertEquals(2, manifest.getVersionCode());
    }

    @Test void rejectsUnsafeOutputAndPreservesExistingFilesOnFailure() throws Exception {
        Path input = fixture();
        byte[] original = Files.readAllBytes(input);
        assertThrows(IOException.class, () -> ApkArchive.rewrite(input, input, Map.of(), true));
        assertArrayEquals(original, Files.readAllBytes(input));
        Path output = temporary.resolve("existing.apk");
        Files.writeString(output, "keep");
        assertThrows(FileAlreadyExistsException.class, () -> ApkArchive.rewrite(input, output, Map.of(), false));
        assertThrows(IOException.class, () -> Outputs.write(input, output, true, p -> {
            Files.writeString(p, "partial"); throw new IOException("failed");
        }));
        assertEquals("keep", Files.readString(output));
        assertThrows(IllegalArgumentException.class, () -> ApkArchive.rewrite(input, temporary.resolve("bad.apk"),
                Map.of("../bad", ApkArchive.Replacement.bytes(new byte[0])), false));
    }

    private Path patch(String operations) throws IOException {
        Path file = temporary.resolve("patch.json");
        Files.writeString(file, "{\"version\":1,\"operations\":[" + operations + "]}");
        return file;
    }

    @Test void batchAddsCalledClassReplacesCallerDeletesClassAndEditsResources() throws Exception {
        Path input = fixture();
        byte[] original = Files.readAllBytes(input);
        smali("Helper", 42);
        smali("Temporary", 0);
        Files.writeString(temporary.resolve("Caller.smali"), """
                .class public Lsample/Main;
                .super Ljava/lang/Object;
                .method public static value()I
                    .registers 1
                    invoke-static {}, Lsample/Helper;->value()I
                    move-result v0
                    return v0
                .end method
                """);
        Files.writeString(temporary.resolve("config.json"), "batch config");
        var table = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(input, "resources.arsc")));
        int id = table.getLocalResource("string", "title").getResourceId();
        Path plan = patch("""
                {"op":"file.replace","path":"assets/config.json","source":"config.json"},
                {"op":"file.add","path":"assets/new.json","source":"config.json"},
                {"op":"file.delete","path":"lib/arm64-v8a/libdemo.so"},
                {"op":"dex.add","inputs":["Helper-42.smali","Temporary-0.smali"]},
                {"op":"dex.replace","inputs":["Caller.smali"]},
                {"op":"dex.delete","classes":["Lsample/Temporary;"]},
                {"op":"manifest.set","label":"Batch label"},
                {"op":"manifest.set","versionCode":2,"versionName":"2.0"},
                {"op":"resource.set-string","id":"0x%08x","config":"en","value":"Batch English"},
                {"op":"resource.set-string","id":"0x%08x","value":"Batch default"}
                """.formatted(id, id));
        Path output = temporary.resolve("batch.apk");
        var result = BatchEditor.apply(input, plan, output, false, null);
        assertEquals(10, result.operations());
        assertEquals(1, result.rebuiltDex());
        assertEquals(1, result.deletedEntries());
        assertEquals("batch config", new String(ApkArchive.read(output, "assets/config.json")));
        assertEquals("batch config", new String(ApkArchive.read(output, "assets/new.json")));
        assertFalse(ZipArchive.listEntries(output).containsKey("lib/arm64-v8a/libdemo.so"));
        assertEquals(3, DexEditor.listClasses(output).size());
        assertTrue(DexEditor.exportClass(output, "sample.Main").contains("invoke-static {}, Lsample/Helper;->value()I"));
        assertTrue(DexEditor.exportClass(output, "sample.Helper").contains("0x2a"));
        assertThrows(IOException.class, () -> DexEditor.exportClass(output, "sample.Temporary"));
        assertEquals("Batch label", ResourceEditor.manifest(output).getApplicationLabelString());
        assertEquals(2, ResourceEditor.manifest(output).getVersionCode());
        assertEquals("2.0", ResourceEditor.manifest(output).getVersionName());
        var after = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(output, "resources.arsc")));
        Map<String, String> values = new HashMap<>();
        after.getEntries(id).forEachRemaining(e -> values.put(e.getResConfig().getQualifiers(), e.getValueAsString()));
        assertEquals(Map.of("", "Batch default", "-en", "Batch English"), values);
        assertArrayEquals(original, Files.readAllBytes(input));
        for (String name : List.of("classes2.dex", "assets/keep.bin"))
            assertArrayEquals(compressedPayload(input, name), compressedPayload(output, name));
        ApkArchive.checkAlignment(output);
    }

    @Test void batchHonorsSequentialFileAndClassChangesIncludingWholeDexReplacement() throws Exception {
        Path input = fixture();
        smali("Added", 1);
        smali("Added", 2);
        smali("Fresh", 3);
        Files.write(temporary.resolve("replacement.dex"), dex("Other", 5));
        Files.writeString(temporary.resolve("first.txt"), "first");
        Files.writeString(temporary.resolve("last.txt"), "last");
        Path plan = patch("""
                {"op":"file.delete","path":"assets/config.json"},
                {"op":"file.add","path":"assets/config.json","source":"first.txt"},
                {"op":"file.replace","path":"assets/config.json","source":"last.txt"},
                {"op":"file.add","path":"assets/temporary","source":"first.txt"},
                {"op":"file.delete","path":"assets/temporary"},
                {"op":"dex.add","inputs":["Added-1.smali"],"dex":"classes2.dex"},
                {"op":"file.replace","path":"classes.dex","source":"replacement.dex"},
                {"op":"dex.replace","inputs":["Added-2.smali"]},
                {"op":"dex.delete","classes":["sample.Other","sample.Untouched"]},
                {"op":"dex.add","inputs":["Fresh-3.smali"]},
                {"op":"file.replace","path":"classes2.dex","source":"replacement.dex"},
                {"op":"dex.delete","classes":["sample.Fresh"]},
                {"op":"dex.add","inputs":["Added-2.smali"],"dex":"classes2.dex"}
                """);
        Path output = temporary.resolve("ordered.apk");
        var result = BatchEditor.apply(input, plan, output, false, null);
        assertEquals(2, result.rebuiltDex());
        assertEquals("last", new String(ApkArchive.read(output, "assets/config.json")));
        assertFalse(ZipArchive.listEntries(output).containsKey("assets/temporary"));
        assertTrue(DexEditor.parse(ApkArchive.read(output, "classes.dex")).getClasses().isEmpty());
        assertEquals(Set.of("classes2.dex | Lsample/Other;", "classes2.dex | Lsample/Added;"),
                new HashSet<>(DexEditor.listClasses(output)));
        assertTrue(DexEditor.exportClass(output, "sample.Added").contains("0x2"));
    }

    @Test void batchWholeResourceFilesSupersedeEarlierEditsAndAllowLaterEdits() throws Exception {
        Path input = fixture();
        Files.write(temporary.resolve("manifest.bin"), ApkArchive.read(input, "AndroidManifest.xml"));
        Files.write(temporary.resolve("resources.bin"), ApkArchive.read(input, "resources.arsc"));
        var table = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(input, "resources.arsc")));
        int id = table.getLocalResource("string", "title").getResourceId();
        Path plan = patch("""
                {"op":"manifest.set","label":"Superseded"},
                {"op":"file.replace","path":"AndroidManifest.xml","source":"manifest.bin"},
                {"op":"manifest.set","versionCode":4},
                {"op":"resource.set-string","id":"0x%08x","config":"en","value":"Superseded"},
                {"op":"file.delete","path":"resources.arsc"},
                {"op":"file.add","path":"resources.arsc","source":"resources.bin"},
                {"op":"resource.set-string","id":"0x%08x","value":"Final"}
                """.formatted(id, id));
        Path output = temporary.resolve("ordered-resources.apk");
        BatchEditor.apply(input, plan, output, false, null);
        assertEquals("Original", ResourceEditor.manifest(output).getApplicationLabelString());
        assertEquals(4, ResourceEditor.manifest(output).getVersionCode());
        var after = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(output, "resources.arsc")));
        Map<String, String> values = new HashMap<>();
        after.getEntries(id).forEachRemaining(e -> values.put(e.getResConfig().getQualifiers(), e.getValueAsString()));
        assertEquals(Map.of("", "Final", "-en", "English"), values);
    }

    @Test void batchReportsOperationAndNeverPublishesPartialEditsOrFailedSignature() throws Exception {
        Path input = fixture();
        byte[] original = Files.readAllBytes(input);
        smali("Added", 1);
        Path output = temporary.resolve("existing.apk");
        Files.writeString(output, "keep existing output");
        String first = "{\"op\":\"dex.add\",\"inputs\":[\"Added-1.smali\"]},";
        for (String invalid : List.of(
                "{\"op\":\"dex.delete\",\"classes\":[\"sample.Missing\"]}",
                "{\"op\":\"dex.add\",\"inputs\":[\"Added-1.smali\"]}",
                "{\"op\":\"file.delete\",\"path\":\"assets/missing\"}",
                "{\"op\":\"file.add\",\"path\":\"assets/config.json\",\"source\":\"Added-1.smali\"}",
                "{\"op\":\"file.replace\",\"path\":\"assets/missing\",\"source\":\"Added-1.smali\"}",
                "{\"op\":\"dex.replace\",\"inputs\":null}",
                "{\"op\":\"manifest.set\",\"versionCode\":1.5}",
                "{\"op\":\"file.add\",\"path\":\"../bad\",\"source\":\"Added-1.smali\"}")) {
            Path plan = patch(first + invalid);
            var error = assertThrows(IOException.class, () -> BatchEditor.apply(input, plan, output, true, null));
            assertTrue(error.getMessage().contains("Operation #2"), error.getMessage());
            assertEquals("keep existing output", Files.readString(output));
        }
        Path plan = patch("{\"op\":\"manifest.set\",\"label\":\"Ready for signing\"}");
        assertThrows(IOException.class, () -> BatchEditor.apply(input, plan, output, true, (unsigned, target) -> {
            assertEquals("Ready for signing", ResourceEditor.manifest(unsigned).getApplicationLabelString());
            Files.writeString(target, "partial signed output");
            throw new IOException("Signing failed");
        }));
        assertEquals("keep existing output", Files.readString(output));
        assertThrows(IOException.class, () -> BatchEditor.apply(input, plan, input, true, null));
        assertThrows(FileAlreadyExistsException.class, () -> BatchEditor.apply(input, plan, output, false, null));
        Path deleteManifest = patch("{\"op\":\"file.delete\",\"path\":\"AndroidManifest.xml\"}");
        Path absent = temporary.resolve("absent.apk");
        assertThrows(IOException.class, () -> BatchEditor.apply(input, deleteManifest, absent, false, null));
        assertFalse(Files.exists(absent));
        assertArrayEquals(original, Files.readAllBytes(input));
        try (var paths = Files.list(temporary)) {
            assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".ed4apk-")));
        }
    }

    @Test void batchRejectsAmbiguousJsonAndUnknownFields() throws Exception {
        Path input = fixture();
        Path plan = temporary.resolve("invalid.json");
        Path output = temporary.resolve("invalid.apk");
        for (String json : List.of(
                "{\"version\":1,\"version\":2,\"operations\":[]}",
                "{\"version\":1,\"operations\":[{\"op\":\"manifest.set\",\"label\":\"a\",\"label\":\"b\"}]}",
                "{\"version\":1,\"operations\":[{\"op\":\"manifest.set\",\"lable\":\"typo\"}]}",
                "{\"version\":1,\"operations\":[{\"op\":\"dex.typo\"}]}",
                "{\"version\":1,\"operations\":[]} {}",
                "{\"version\":1,\"operations\":[]}",
                "{\"version\":1.0,\"operations\":[{\"op\":\"manifest.set\",\"label\":\"x\"}]}",
                "{\"version\":1,\"operations\":[{\"op\":\"manifest.set\",\"label\":\"x\"}],\"typo\":true}")) {
            Files.writeString(plan, json);
            assertThrows(IOException.class, () -> BatchEditor.apply(input, plan, output, false, null), json);
            assertFalse(Files.exists(output));
        }
    }

    private String process(List<String> command, Map<String, String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(temporary.toFile()).redirectErrorStream(true);
        builder.environment().putAll(env);
        builder.environment().remove("CLASSPATH");
        builder.environment().remove("ANDROID_HOME");
        builder.environment().remove("ANDROID_SDK_ROOT");
        Path log = Files.createTempFile(temporary, "process-", ".log");
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Process timed out: " + command.getFirst());
        }
        String result = Files.readString(log);
        assertEquals(0, process.exitValue(), result);
        return result;
    }

    private String jar(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(System.getProperty("ed4apk.java",
                Path.of(System.getProperty("java.home"), "bin", "java").toString()),
                "-jar", System.getProperty("ed4apk.jar")));
        command.addAll(List.of(args));
        return process(command, Map.of("ed4apk_KS_PASS", "changeit"));
    }

    @Test void standaloneJarEditsResourcesDexAndSignsWithoutSdkOrClasspath() throws Exception {
        Path input = fixture();
        assertTrue(jar("--help").contains("APK Quick Editor"));
        assertTrue(jar("info", input.toString()).contains("dev.ed4apk.fixture"));
        Path edited = temporary.resolve("jar-edited.apk");
        jar("dex", "replace", input.toString(), smali("Main", 18).toString(), "-o", edited.toString());
        Path exported = temporary.resolve("Main.smali");
        jar("dex", "export", edited.toString(), "sample.Main", "-o", exported.toString());
        assertTrue(Files.readString(exported).contains("0x12"));
        Path manifest = temporary.resolve("jar-manifest.apk");
        jar("manifest", "set", edited.toString(), "--label", "JAR edit", "-o", manifest.toString());
        assertEquals("JAR edit", ResourceEditor.manifest(manifest).getApplicationLabelString());
        Path config = temporary.resolve("config.json");
        Files.writeString(config, "jar replacement");
        Path files = temporary.resolve("jar-files.apk");
        jar("replace", manifest.toString(), "assets/config.json=" + config, "-o", files.toString());
        assertEquals("jar replacement", new String(ApkArchive.read(files, "assets/config.json")));
        var table = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(files, "resources.arsc")));
        String id = String.format("0x%08x", table.getLocalResource("string", "title").getResourceId());
        Path resource = temporary.resolve("jar-resource.apk");
        jar("resource", "set-string", files.toString(), id, "From JAR", "-o", resource.toString());
        var resourceTable = TableBlock.load(new ByteArrayInputStream(ApkArchive.read(resource, "resources.arsc")));
        var variants = resourceTable.getEntries(Long.decode(id).intValue());
        String defaultValue = null;
        while (variants.hasNext()) {
            var entry = variants.next();
            if (entry.getResConfig().getQualifiers().isEmpty()) defaultValue = entry.getValueAsString();
        }
        assertEquals("From JAR", defaultValue);
        Path javaSource = temporary.resolve("Bundled.java");
        Files.writeString(javaSource, "public class Bundled { public static int value() { return 77; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "8", "-d", temporary.toString(), javaSource.toString()));
        Path withClass = temporary.resolve("jar-class.apk");
        jar("dex", "add", resource.toString(), temporary.resolve("Bundled.class").toString(), "-o", withClass.toString());
        assertTrue(DexEditor.exportClass(withClass, "Bundled").contains("0x4d"));
        Path key = temporary.resolve("test.p12");
        process(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-keystore", key.toString(), "-storetype", "PKCS12", "-storepass", "changeit",
                "-keypass", "changeit", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=ed4apk Integration Test", "-noprompt"), Map.of());
        Path signed = temporary.resolve("signed.apk");
        jar("sign", withClass.toString(), "--ks", key.toString(), "--alias", "test", "-o", signed.toString());
        assertTrue(jar("verify", signed.toString()).contains("v2=true"));
        assertTrue(Signing.verify(signed).isVerified());
        ApkArchive.checkAlignment(signed);

        // Run from a different directory than the patch, proving paths resolve against the patch file.
        Path patchDir = Files.createDirectory(temporary.resolve("patch-files"));
        Files.writeString(patchDir.resolve("payload.txt"), "standalone batch");
        Path plan = patchDir.resolve("batch.json");
        Files.writeString(plan, """
                {"version":1,"operations":[
                  {"op":"file.add","path":"assets/batch.txt","source":"payload.txt"},
                  {"op":"manifest.set","label":"Standalone batch"},
                  {"op":"dex.delete","classes":["Bundled"]}
                ]}
                """);
        Path batchUnsigned = temporary.resolve("jar-batch.apk");
        assertTrue(jar("apply", signed.toString(), "--patch", plan.toString(), "-o", batchUnsigned.toString())
                .contains("Applied 3 operations"));
        assertFalse(Signing.verify(batchUnsigned).isVerified());
        assertEquals("standalone batch", new String(ApkArchive.read(batchUnsigned, "assets/batch.txt")));
        assertThrows(IOException.class, () -> DexEditor.exportClass(batchUnsigned, "Bundled"));
        Path batchSigned = temporary.resolve("jar-batch-signed.apk");
        assertTrue(jar("apply", signed.toString(), "--patch", plan.toString(), "--ks", key.toString(),
                "--alias", "test", "-o", batchSigned.toString()).contains("Signed and verified"));
        assertTrue(Signing.verify(batchSigned).isVerified());
        assertEquals("Standalone batch", ResourceEditor.manifest(batchSigned).getApplicationLabelString());
        ApkArchive.checkAlignment(batchSigned);
    }
}
