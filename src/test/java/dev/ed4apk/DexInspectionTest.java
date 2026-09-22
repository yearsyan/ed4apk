package dev.ed4apk;

import com.android.tools.smali.dexlib2.Opcodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import static org.junit.jupiter.api.Assertions.*;

class DexInspectionTest {
    @TempDir Path temporary;

    @Test void headerCountsDistinguishReferenceIdsFromDefinitionsAndDoNotRequireManifest() throws Exception {
        byte[] dex = dex("com/example/Main");
        Path apk = apk(Map.of("classes.dex", dex));
        byte[] original = Files.readAllBytes(apk);
        Run run = info(apk.toString(), "--json");
        assertEquals(0, run.exit);
        JsonNode report = run.json();
        assertEquals(1, report.path("schemaVersion").asInt());
        JsonNode entry = report.path("data").path("entries").get(0);
        assertEquals("035", entry.path("version").asText());
        assertEquals(1, entry.path("classDefCount").asInt());
        assertEquals(2, entry.path("methodIdCount").asInt());
        assertEquals(2, entry.path("fieldIdCount").asInt());
        assertTrue(report.path("data").path("complete").asBoolean());
        assertTrue(report.path("diagnostics").isEmpty());
        assertArrayEquals(original, Files.readAllBytes(apk));
    }

    @Test void unfilteredTextPreservesExistingEntryAndClassOrdering() throws Exception {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("classes10.dex", dex("com/other/Tenth"));
        entries.put("classes2.dex", dex("com/example/Second"));
        entries.put("classes.dex", dex("com/example/First"));
        entries.put("assets/hidden.dex", dex("com/hidden/Asset"));
        // The old editing helper requires a Manifest entry, without parsing its content here.
        entries.put("AndroidManifest.xml", new byte[] {1});
        Path apk = apk(entries);
        Run run = list(apk.toString());
        assertEquals(0, run.exit);
        assertEquals(String.join(System.lineSeparator(), DexEditor.listClasses(apk)) + System.lineSeparator(), run.out);
        assertTrue(run.err.isEmpty());
    }

    @Test void dexSelectionAndJavaOrDescriptorPrefixesFilterClasses() throws Exception {
        Path apk = apk(Map.of("classes.dex", dex("com/example/First"),
                "classes2.dex", dex("com/example/Second"), "classes3.dex", dex("com/examples/Other")));
        Run run = list(apk.toString(), "--dex", "classes2.dex", "--prefix", "com.example.", "--json");
        assertEquals(0, run.exit);
        JsonNode data = run.json().path("data");
        assertEquals(1, data.path("entryCount").asInt());
        assertEquals(1, data.path("classCount").asInt());
        assertEquals("classes2.dex", data.path("classes").get(0).path("entry").asText());
        assertEquals("Lcom/example/Second;", data.path("classes").get(0).path("descriptor").asText());
        Run descriptor = list(apk.toString(), "--prefix", "Lcom/example/", "--json");
        assertEquals(0, descriptor.exit);
        assertEquals(2, descriptor.json().path("data").path("classCount").asInt());
        assertEquals(0, list(apk.toString(), "--prefix", "org.unmatched", "--json")
                .json().path("data").path("classCount").asInt());
    }

    @Test void futureContainersKeepVersionButNeverReadOldCountOffsets() throws Exception {
        byte[] unsupported = dex("com/example/Future");
        unsupported[4] = '0';
        unsupported[5] = '4';
        unsupported[6] = '1';
        ByteBuffer.wrap(unsupported).order(ByteOrder.LITTLE_ENDIAN).putInt(96, 0x7fffffff);
        Path apk = apk(Map.of("classes.dex", unsupported, "classes2.dex", dex("com/example/Good")));
        Run run = info(apk.toString(), "--json");
        assertEquals(1, run.exit);
        JsonNode entries = run.json().path("data").path("entries");
        assertEquals("041", entries.get(0).path("version").asText());
        assertTrue(entries.get(0).path("classDefCount").isNull());
        assertTrue(entries.get(0).path("methodIdCount").isNull());
        assertTrue(entries.get(0).path("fieldIdCount").isNull());
        assertEquals("unknown", entries.get(0).path("status").asText());
        assertEquals("DEX_VERSION_UNSUPPORTED", run.json().path("diagnostics").get(0).path("code").asText());
        assertEquals(1, entries.get(1).path("classDefCount").asInt());
        Run classes = list(apk.toString(), "--json");
        assertEquals(1, classes.exit);
        assertEquals("Lcom/example/Good;", classes.json().path("data").path("classes").get(0)
                .path("descriptor").asText());
    }

    @Test void corruptRangesAndShortHeadersDoNotLoseOtherDexResults() throws Exception {
        byte[] badRange = dex("com/example/Bad");
        ByteBuffer.wrap(badRange).order(ByteOrder.LITTLE_ENDIAN).putInt(100, badRange.length - 4);
        Path apk = apk(Map.of("classes.dex", badRange, "classes2.dex", "dex\n035\0".getBytes(StandardCharsets.US_ASCII),
                "classes3.dex", dex("com/example/Good")));
        Run run = info(apk.toString(), "--json");
        assertEquals(1, run.exit);
        JsonNode report = run.json();
        assertEquals(3, report.path("data").path("entries").size());
        assertFalse(report.path("data").path("complete").asBoolean());
        assertTrue(report.path("data").path("entries").get(0).path("classDefCount").isNull());
        assertEquals("035", report.path("data").path("entries").get(1).path("version").asText());
        assertEquals("DEX_TABLE_RANGE_INVALID", report.path("diagnostics").get(0).path("code").asText());
        assertEquals("DEX_HEADER_TRUNCATED", report.path("diagnostics").get(1).path("code").asText());
        assertEquals(1, report.path("data").path("entries").get(2).path("classDefCount").asInt());
        Run classes = list(apk.toString(), "--json");
        assertEquals(1, classes.exit);
        assertEquals(1, classes.json().path("data").path("classCount").asInt());
    }

    @Test void invalidClassReferenceDoesNotDiscardOtherEntriesOrPublishPartialEntryClasses() throws Exception {
        byte[] corrupt = dex("com/example/Bad");
        ByteBuffer buffer = ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN);
        int classesOffset = buffer.getInt(100);
        buffer.putInt(classesOffset, Integer.MAX_VALUE);
        Path apk = apk(Map.of("classes.dex", corrupt, "classes2.dex", dex("com/example/Good")));
        Run run = list(apk.toString(), "--json");
        assertEquals(1, run.exit);
        assertEquals(1, run.json().path("data").path("classes").size());
        assertEquals("classes2.dex", run.json().path("data").path("classes").get(0).path("entry").asText());
        assertEquals("unknown", run.json().path("data").path("entries").get(0).path("status").asText());
    }

    @Test void maliciousDescriptorLengthIsRejectedBeforeDexlibCanAllocateIt() throws Exception {
        byte[] corrupt = dex("com/example/Bad");
        ByteBuffer buffer = ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN);
        int typeIndex = buffer.getInt(buffer.getInt(100));
        int stringIndex = buffer.getInt(buffer.getInt(68) + typeIndex * 4);
        int stringOffset = buffer.getInt(buffer.getInt(60) + stringIndex * 4);
        // ULEB128 for Integer.MAX_VALUE; no actual large allocation is needed for this fixture.
        for (int index = 0; index < 4; index++) corrupt[stringOffset + index] = (byte) 0xff;
        corrupt[stringOffset + 4] = 7;
        Path apk = apk(Map.of("classes.dex", corrupt, "classes2.dex", dex("com/example/Good")));
        Run run = list(apk.toString(), "--json");
        assertEquals(1, run.exit);
        assertEquals("DEX_DESCRIPTOR_LIMIT", run.json().path("diagnostics").get(0).path("code").asText());
        assertEquals(1, run.json().path("data").path("classCount").asInt());
    }

    @Test void headerValidationChecksFileSizeMagicOverlapAndUnsignedRanges() throws Exception {
        byte[] valid = dex("com/example/Main");
        byte[] header = Arrays.copyOf(valid, 112);
        assertNull(DexInspector.inspectHeader(header, valid.length).code);
        assertEquals("DEX_SIZE_MISMATCH", DexInspector.inspectHeader(header, valid.length + 1).code);
        header[3] = 0;
        assertEquals("DEX_MAGIC_INVALID", DexInspector.inspectHeader(header, valid.length).code);
        header = Arrays.copyOf(valid, 112);
        ByteBuffer values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        values.putInt(96, -1);
        assertEquals("DEX_TABLE_RANGE_INVALID", DexInspector.inspectHeader(header, valid.length).code);
        header = Arrays.copyOf(valid, 112);
        values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        values.putInt(92, values.getInt(84));
        assertEquals("DEX_TABLE_OVERLAP", DexInspector.inspectHeader(header, valid.length).code);
    }

    @Test void headerInspectionUnderstandsByteOrderWithoutPretendingDexlibSupportsIt() throws Exception {
        byte[] little = dex("com/example/Main");
        byte[] big = little.clone();
        ByteBuffer source = ByteBuffer.wrap(little).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer target = ByteBuffer.wrap(big).order(ByteOrder.BIG_ENDIAN);
        for (int at = 32; at < 112; at += 4) target.putInt(at, source.getInt(at));
        DexInspector.Header header = DexInspector.inspectHeader(Arrays.copyOf(big, 112), big.length);
        assertNull(header.code);
        assertEquals("big", header.byteOrder);
        assertEquals(1L, header.classDefCount);
        Run run = list(apk(Map.of("classes.dex", big)).toString(), "--json");
        assertEquals(1, run.exit);
        assertEquals("DEX_ENDIAN_UNSUPPORTED", run.json().path("diagnostics").get(0).path("code").asText());
    }

    @Test void unavailableApkStillHasJsonEnvelopeAndMissingDexIsParameterError() throws Exception {
        Run missingApk = info(temporary.resolve("missing.apk").toString(), "--json");
        assertEquals(1, missingApk.exit);
        assertEquals(1, missingApk.json().path("schemaVersion").asInt());
        assertFalse(missingApk.json().path("data").path("complete").asBoolean());
        assertEquals("APK_READ_FAILED", missingApk.json().path("diagnostics").get(0).path("code").asText());
        Run missingDex = list(apk(Map.of("classes.dex", dex("com/example/Main"))).toString(),
                "--dex", "classes9.dex", "--json");
        assertEquals(2, missingDex.exit);
        assertTrue(missingDex.out.isEmpty());
        assertTrue(missingDex.err.contains("DEX entry not found"));
    }

    private byte[] dex(String descriptorPath) throws Exception {
        Path source = Files.createTempFile(temporary, "class-", ".smali");
        Files.writeString(source, """
                .class public L%s;
                .super Ljava/lang/Object;
                .field public static own:I
                .method public static run()V
                    .registers 1
                    sget v0, Lexternal/Thing;->field:I
                    invoke-static {}, Lexternal/Thing;->ping()V
                    return-void
                .end method
                """.formatted(descriptorPath));
        var classes = DexEditor.importClasses(List.of(source), 21, List.of());
        return DexEditor.writeClasses(classes.values(), Opcodes.forApi(21));
    }

    private Path apk(Map<String, byte[]> entries) throws Exception {
        Path path = Files.createTempFile(temporary, "inspect-", ".apk");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private Run info(String... args) throws Exception { return run(new DexInspectionCommands.Info(), args); }
    private Run list(String... args) throws Exception { return run(new DexInspectionCommands.ListCommand(), args); }

    private Run run(Object command, String... args) throws Exception {
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(stdout);
            System.setErr(stderr);
            CommandLine cli = new CommandLine(command).setOut(new PrintWriter(stdout, true))
                    .setErr(new PrintWriter(stderr, true));
            int exit = cli.execute(args);
            return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
    }

    private static final class Run {
        final int exit;
        final String out;
        final String err;
        Run(int exit, String out, String err) { this.exit = exit; this.out = out; this.err = err; }
        JsonNode json() throws Exception { return new ObjectMapper().readTree(out); }
    }
}
