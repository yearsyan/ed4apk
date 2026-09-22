package dev.ed4apk;

import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NativeInspectionTest {
    @TempDir Path temporary;
    static final String ARM64 = "lib/arm64-v8a/libsample.so";
    static final ObjectMapper JSON = new ObjectMapper();

    // Fixture segments: type, flags, file offset, virtual address, file size, memory size, alignment.
    private static long[] load(long alignment) { return new long[]{1, 5, 0, 0, 512, 16384, alignment}; }
    private static byte[] elf(long[]... segments) { return elf(ByteOrder.LITTLE_ENDIAN, 183, segments); }
    private static byte[] elf(ByteOrder order, int machine, long[]... segments) {
        ByteBuffer b = ByteBuffer.allocate(512).order(order);
        b.put(new byte[]{0x7f, 'E', 'L', 'F', 2, (byte) (order == ByteOrder.LITTLE_ENDIAN ? 1 : 2), 1});
        b.putShort(16, (short) 3).putShort(18, (short) machine).putInt(20, 1);
        b.putLong(32, 64).putShort(52, (short) 64).putShort(54, (short) 56).putShort(56, (short) segments.length);
        for (int i = 0; i < segments.length; i++) {
            long[] s = segments[i];
            int p = 64 + i * 56;
            b.putInt(p, (int) s[0]).putInt(p + 4, (int) s[1]);
            b.putLong(p + 8, s[2]).putLong(p + 16, s[3]).putLong(p + 24, s[3]);
            b.putLong(p + 32, s[4]).putLong(p + 40, s[5]).putLong(p + 48, s[6]);
        }
        return b.array();
    }
    private static byte[] elf32() {
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{0x7f, 'E', 'L', 'F', 1, 1, 1});
        b.putShort(16, (short) 3).putShort(18, (short) 40).putInt(20, 1);
        b.putInt(28, 52).putShort(40, (short) 52).putShort(42, (short) 32).putShort(44, (short) 1);
        b.putInt(52, 1).putInt(56, 0).putInt(60, 0).putInt(68, 512).putInt(72, 4096).putInt(76, 5).putInt(80, 4096);
        return b.array();
    }
    private static ElfInspector.Result inspect(byte[] bytes) {
        return ElfInspector.inspect(new ByteArrayInputStream(bytes), bytes.length, ARM64);
    }
    private static boolean diagnostic(ElfInspector.Result result, String code) {
        return result.diagnostics.stream().anyMatch(d -> code.equals(d.get("code")));
    }
    private static boolean diagnostic(NativeInspector.Report result, String code) {
        return result.diagnostics.stream().anyMatch(d -> code.equals(d.get("code")));
    }

    private static byte[] manifest(Boolean extracts, String split) throws Exception {
        var manifest = new AndroidManifestBlock();
        manifest.setPackageName("sample.nativeinspection");
        manifest.setMinSdkVersion(23);
        manifest.setApplicationLabel("Sample");
        if (extracts != null) {
            var attr = manifest.getApplicationElement().newAttribute();
            attr.setName("extractNativeLibs", 0x010104ea);
            attr.setNamespace(ManifestEditor.ANDROID, "android");
            attr.setValueAsBoolean(extracts);
        }
        if (split != null) {
            var attr = manifest.getDocumentElement().newAttribute();
            attr.setName("split", 0);
            attr.setValueAsString(split);
        }
        manifest.refresh();
        return manifest.getBytes();
    }
    private Path apk(Boolean extracts, boolean compressed, boolean aligned, Map<String, byte[]> libraries) throws Exception {
        return apk(manifest(extracts, null), compressed, aligned, libraries);
    }
    private Path apk(byte[] manifest, boolean compressed, boolean aligned, Map<String, byte[]> libraries) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID() + ".apk");
        try (var zip = new ZipArchive(file)) {
            if (manifest != null) zip.add(new BytesSource(manifest, "AndroidManifest.xml", 1));
            for (var library : libraries.entrySet()) {
                var source = new BytesSource(library.getValue(), library.getKey(), compressed ? 1 : 0);
                if (aligned && !compressed) source.align(16384);
                zip.add(source);
            }
        }
        return file;
    }
    private NativeInspector.Report check(Path apk) throws Exception {
        try (var session = new ApkInspectionSession(apk)) { return NativeInspector.check(session, null); }
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> libraries(NativeInspector.Report report) {
        return (List<Map<String, Object>>) report.data.get("libraries");
    }

    @Test void checksAllLoadSegmentsAndAcceptsNonzeroCongruenceAnd64KiBAlignment() {
        var valid = inspect(elf(load(65536), new long[]{1, 6, 128, 16512, 128, 256, 16384}));
        assertEquals("pass", valid.parsing);
        assertEquals("pass", valid.loadAlignment);
        assertEquals("not_applicable", valid.relroAlignment);
        assertEquals(128, valid.segments.get(1).get("offsetPageRemainder"));
        assertEquals(128, valid.segments.get(1).get("addressPageRemainder"));
        var tooSmall = inspect(elf(load(16384), new long[]{1, 6, 128, 16512, 128, 256, 4096}));
        assertEquals("fail", tooSmall.loadAlignment);
        assertEquals(1, tooSmall.diagnostics.get(0).get("segmentIndex"));
        var incongruent = inspect(elf(load(16384), new long[]{1, 6, 128, 16384, 128, 256, 16384}));
        assertEquals("fail", incongruent.loadAlignment);
        assertTrue(diagnostic(incongruent, "elf_load_congruence"));
        assertEquals("fail", inspect(elf(load(24576))).loadAlignment);
        assertEquals("fail", inspect(elf(load(0))).loadAlignment);
    }

    @Test void checksRelroEndAndPreservesFullUnsignedAddresses() {
        var good = inspect(elf(load(16384), new long[]{0x6474e552, 4, 0, 16384, 512, 16384, 1}));
        assertEquals("pass", good.relroAlignment);
        var bad = inspect(elf(load(16384), new long[]{0x6474e552, 4, 0, 16384, 512, 16383, 1}));
        assertEquals("fail", bad.relroAlignment);
        assertTrue(diagnostic(bad, "elf_relro_alignment"));
        var high = inspect(elf(new long[]{1, 5, 0, Long.MIN_VALUE, 512, 16384, 16384}));
        assertEquals("pass", high.parsing);
        assertEquals("pass", high.loadAlignment);
        assertEquals("0x8000000000000000", high.segments.get(0).get("virtualAddress"));
        var overflow = inspect(elf(load(16384), new long[]{0x6474e552, 4, 0, -4L, 0, 8, 1}));
        assertEquals("unknown", overflow.parsing);
        assertEquals("unknown", overflow.relroAlignment);
        assertTrue(diagnostic(overflow, "elf_address_overflow"));
    }

    @Test void malformedHeadersAndSegmentsNeverPass() {
        assertEquals("unknown", inspect(new byte[128]).parsing);
        assertTrue(diagnostic(inspect(Arrays.copyOf(elf(load(16384)), 10)), "elf_truncated"));
        assertEquals("unknown", inspect(Arrays.copyOf(elf(load(16384)), 80)).parsing);
        assertTrue(diagnostic(inspect(elf()), "elf_no_load_segments"));
        assertTrue(diagnostic(inspect(elf(new long[]{4, 0, 0, 0, 32, 32, 4})), "elf_no_load_segments"));
        assertTrue(diagnostic(inspect(elf(new long[]{1, 5, 500, 500, 128, 256, 16384})), "elf_invalid_file_range"));
        assertTrue(diagnostic(inspect(elf(new long[]{1, 5, 0, 0, 512, 128, 16384})), "elf_invalid_load_size"));
        byte[] extended = elf(load(16384));
        ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN).putShort(56, (short) 0xffff);
        assertTrue(diagnostic(inspect(extended), "elf_extended_program_headers"));
        byte[] rangeOverflow = elf(load(16384));
        ByteBuffer.wrap(rangeOverflow).order(ByteOrder.LITTLE_ENDIAN).putLong(32, -16L);
        assertTrue(diagnostic(inspect(rangeOverflow), "elf_address_overflow"));
    }

    @Test void ignoresUndefinedNullSegmentFields() {
        var result = inspect(elf(load(16384), new long[]{0, -1L, -1L, -1L, -1L, -1L, -1L}));
        assertEquals("pass", result.parsing);
        assertEquals("pass", result.loadAlignment);
        assertTrue(result.diagnostics.isEmpty());
    }

    @Test void readsHeadersOnlyAndEnforcesBudgetBeforeExpensiveDecompression() throws Exception {
        byte[] valid = elf(load(16384));
        var input = new ByteArrayInputStream(valid);
        var result = ElfInspector.inspect(input, valid.length, ARM64);
        assertEquals(120L, result.data.get("bytesRead"));
        assertEquals(valid.length - 120, input.available());
        byte[] farAway = valid.clone();
        ByteBuffer.wrap(farAway).order(ByteOrder.LITTLE_ENDIAN).putLong(32, ElfInspector.READ_BUDGET + 64L);
        var limited = ElfInspector.inspect(new ByteArrayInputStream(farAway), 64L * 1024 * 1024, ARM64);
        assertTrue(diagnostic(limited, "elf_read_budget_exceeded"));
        assertEquals(64L, limited.data.get("bytesRead"));
        assertEquals("unknown", limited.loadAlignment);
        assertTrue(diagnostic(ElfInspector.inspect(new ByteArrayInputStream(valid), valid.length, ARM64, 100), "elf_read_budget_exceeded"));
    }

    @Test void zipAndElfAlignmentAreIndependentAndApkIsUnchanged() throws Exception {
        Path elfBad = apk(true, false, true, Map.of(ARM64, elf(load(4096))));
        byte[] original = Files.readAllBytes(elfBad);
        var badElfReport = check(elfBad);
        var library = libraries(badElfReport).get(0);
        assertEquals("pass", library.get("zipAlignment"));
        assertEquals("fail", library.get("loadAlignment"));
        assertEquals("fail", badElfReport.status);
        assertArrayEquals(original, Files.readAllBytes(elfBad));
        var badZipReport = check(apk(true, false, false, Map.of(ARM64, elf(load(16384)))));
        library = libraries(badZipReport).get(0);
        assertEquals("fail", library.get("zipAlignment"));
        assertEquals("pass", library.get("loadAlignment"));
        assertEquals("fail", badZipReport.status);
        assertEquals("pass", check(apk(false, false, true, Map.of(ARM64, elf(load(16384))))).status);
    }

    @Test void compressedLibrariesStillNeedElfAndExplicitPackagingContext() throws Exception {
        var allowed = check(apk(true, true, false, Map.of(ARM64, elf(load(16384)))));
        assertEquals("pass", allowed.status);
        assertEquals("not_applicable", libraries(allowed).get(0).get("zipAlignment"));
        var forbidden = check(apk(false, true, false, Map.of(ARM64, elf(load(16384)))));
        assertEquals("fail", forbidden.status);
        assertTrue(diagnostic(forbidden, "native_packaging_conflict"));
        var undeclared = check(apk((Boolean) null, true, false, Map.of(ARM64, elf(load(16384)))));
        assertEquals("unknown", undeclared.status);
        assertTrue(diagnostic(undeclared, "native_loading_mode_unknown"));
        assertEquals("fail", check(apk(true, true, false, Map.of(ARM64, elf(load(4096))))).status);
        var unreadable = check(apk(new byte[]{1, 2, 3}, true, false, Map.of(ARM64, elf(load(16384)))));
        assertEquals("unknown", unreadable.status);
        assertTrue(diagnostic(unreadable, "native_manifest_unavailable"));
        var storedUnreadable = check(apk(new byte[]{1, 2, 3}, false, true, Map.of(ARM64, elf(load(16384)))));
        assertEquals("unknown", storedUnreadable.status);
        assertEquals("pass", libraries(storedUnreadable).get(0).get("status"));
        assertTrue(storedUnreadable.incomplete);
        var split = check(apk(manifest(null, "config.arm64_v8a"), true, false, Map.of(ARM64, elf(load(16384)))));
        assertEquals("unknown", split.status);
        assertTrue(diagnostic(split, "native_split_loading_context_missing"));
    }

    @Test void malformedLibraryDoesNotHideOtherLibrariesAndListingDoesNotParseElf() throws Exception {
        var content = Map.of(ARM64, elf(load(16384)), "lib/arm64-v8a/libbroken.so", new byte[]{1, 2, 3});
        Path apk = apk(true, false, true, content);
        var report = check(apk);
        assertEquals(2, libraries(report).size());
        assertEquals("unknown", report.status);
        assertEquals("unknown", libraries(report).get(0).get("status"));
        assertEquals("pass", libraries(report).get(1).get("status"));
        try (var session = new ApkInspectionSession(apk)) {
            var list = NativeInspector.list(session, null);
            assertFalse(libraries(list).get(0).containsKey("elf"));
            assertFalse(diagnostic(list, "elf_truncated"));
            assertEquals("pass", list.status);
        }
    }

    @Test void abiPolicyIsExplicitAndMachineAndEndianMismatchFail() throws Exception {
        var mismatch = check(apk(true, false, true, Map.of(ARM64, elf(ByteOrder.LITTLE_ENDIAN, 62, load(16384)))));
        assertEquals("fail", mismatch.status);
        assertTrue(diagnostic(mismatch, "native_abi_mismatch"));
        var endian = check(apk(true, false, true, Map.of(ARM64, elf(ByteOrder.BIG_ENDIAN, 183, load(16384)))));
        assertEquals("fail", endian.status);
        assertEquals("pass", ((Map<?, ?>) libraries(endian).get(0).get("elf")).get("parsing"));
        var abi32 = check(apk(true, false, false, Map.of("lib/armeabi-v7a/libold.so", elf32())));
        assertEquals("not_applicable", abi32.status);
        assertEquals("not_applicable", libraries(abi32).get(0).get("loadAlignment"));
        assertFalse(diagnostic(abi32, "elf_load_alignment"));
        assertEquals("not_applicable", ((Map<?, ?>) libraries(abi32).get(0).get("elf")).get("loadAlignment"));
        var broken32 = check(apk(true, false, false, Map.of("lib/armeabi-v7a/libold.so", new byte[4])));
        assertEquals("unknown", broken32.status);
        assertTrue(broken32.incomplete);
        var future = check(apk(true, false, true, Map.of("lib/future64/libunknown.so", elf(load(16384)))));
        assertEquals("unknown", future.status);
        assertTrue(diagnostic(future, "native_abi_not_covered"));
        try (var session = new ApkInspectionSession(apk(true, false, true, Map.of(ARM64, elf(load(16384)))))) {
            assertThrows(IllegalArgumentException.class, () -> NativeInspector.check(session, "x86_64"));
        }
    }

    @Test void noStandardLibrariesIsNotApplicableAndCustomPathsStayOutsideScope() throws Exception {
        Path apk = apk(true, true, false, Map.of("assets/libcustom.so", elf(load(4096)), "lib/arm64-v8a/nested/libcustom.so", elf(load(4096))));
        var report = check(apk);
        assertEquals("not_applicable", report.status);
        assertTrue(libraries(report).isEmpty());
        assertEquals(0, ((Map<?, ?>) report.data.get("scope")).get("scannedCount"));
    }

    @Test void jsonCliHasStableEnvelopeAndCheckExitCodes() throws Exception {
        Path good = apk(true, true, false, Map.of(ARM64, elf(load(16384))));
        var checked = cli("native", "check", good.toString(), "--json");
        assertEquals(0, checked.code, checked.error);
        JsonNode json = JSON.readTree(checked.output);
        assertEquals(1, json.path("schemaVersion").asInt());
        assertEquals("pass", json.path("data").path("summary").path("status").asText());
        assertTrue(json.path("diagnostics").isArray());
        var shown = cli("native", "show", good.toString(), ARM64, "--json");
        assertEquals(0, shown.code, shown.error);
        assertEquals("0x4000", JSON.readTree(shown.output).path("data").path("libraries").get(0)
                .path("elf").path("segments").get(0).path("alignment").asText());
        var failure = cli("native", "check", apk(true, true, false, Map.of(ARM64, elf(load(4096)))).toString(), "--json");
        assertEquals(1, failure.code);
        assertEquals("fail", JSON.readTree(failure.output).path("data").path("summary").path("status").asText());
        assertEquals(2, cli("native", "check", good.toString(), "--page-size", "4096").code);
        assertEquals(2, cli("native", "check", good.toString(), "--abi", "missing").code);
        assertEquals(0, cli("native", "list", apk(true, false, false, Map.of(ARM64, new byte[32])).toString()).code);
        var absent = cli("native", "show", good.toString(), "lib/arm64-v8a/missing.so", "--json");
        assertEquals(1, absent.code);
        assertTrue(JSON.readTree(absent.output).path("data").isNull());
        assertEquals("native_read_failed", JSON.readTree(absent.output).path("diagnostics").get(0).path("code").asText());
        var missingApk = cli("native", "list", temporary.resolve("missing.apk").toString(), "--json");
        assertEquals(1, missingApk.code);
        assertEquals(1, JSON.readTree(missingApk.output).path("schemaVersion").asInt());
    }

    private static final class CliResult {
        final int code;
        final String output;
        final String error;
        CliResult(int code, String output, String error) { this.code = code; this.output = output; this.error = error; }
    }
    private CliResult cli(String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(System.getProperty("ed4apk.java", Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        command.add("-jar"); command.add(System.getProperty("ed4apk.jar")); command.addAll(List.of(arguments));
        Path stdout = temporary.resolve(UUID.randomUUID() + ".stdout");
        Path stderr = temporary.resolve(UUID.randomUUID() + ".stderr");
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Native CLI timed out");
        return new CliResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    }
}
