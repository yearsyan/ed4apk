package dev.ed4apk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class FileInspectionTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();

    private Path zip(Map<String, byte[]> contents, boolean stored) throws Exception {
        Path apk = temporary.resolve(UUID.randomUUID() + ".apk");
        try (var output = new ZipOutputStream(Files.newOutputStream(apk))) {
            for (var item : contents.entrySet()) {
                var entry = new ZipEntry(item.getKey());
                if (stored) {
                    CRC32 crc = new CRC32(); crc.update(item.getValue());
                    entry.setMethod(ZipEntry.STORED); entry.setSize(item.getValue().length);
                    entry.setCompressedSize(item.getValue().length); entry.setCrc(crc.getValue());
                }
                output.putNextEntry(entry); output.write(item.getValue()); output.closeEntry();
            }
        }
        return apk;
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private record Result(int code, String out, String err) {
        JsonNode json() throws IOException { return JSON.readTree(out); }
    }

    private Result cli(String... args) throws Exception {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out, originalErr = System.err;
        try (var captureOut = new PrintStream(out, true, StandardCharsets.UTF_8);
             var captureErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(captureOut); System.setErr(captureErr);
            var command = Main.commandLine();
            command.setOut(new PrintWriter(captureOut, true)); command.setErr(new PrintWriter(captureErr, true));
            int code = command.execute(args);
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally { System.setOut(originalOut); System.setErr(originalErr); }
    }

    @Test void listsWithoutParsingManifestAndDistinguishesSizesAndTruncation() throws Exception {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("assets/", new byte[0]); contents.put("assets/空.txt", new byte[0]);
        contents.put("assets/large.txt", bytes("x".repeat(5000)));
        contents.put("AndroidManifest.xml", bytes("broken")); contents.put("META-INF/TEST.SF", bytes("signature"));
        Path apk = zip(contents, false); byte[] before = Files.readAllBytes(apk);
        Result result = cli("file", "list", apk.toString(), "--prefix", "assets/", "--sort", "size", "--limit", "1", "--json");
        assertEquals(0, result.code, result.err);
        JsonNode data = result.json().path("data");
        assertEquals(3, data.path("matchedCount").asInt());
        assertEquals(1, data.path("returnedCount").asInt()); assertTrue(data.path("truncated").asBoolean());
        JsonNode first = data.path("entries").get(0);
        assertEquals("assets/large.txt", first.path("path").asText());
        assertEquals(5000, first.path("size").asInt());
        assertTrue(first.path("compressedSize").asInt() < 5000);
        assertEquals("deflated", first.path("method").asText());
        assertTrue(first.path("dataOffset").asLong() > 0);
        Result all = cli("file", "list", apk.toString(), "--json");
        assertEquals(5, all.json().path("data").path("entries").size());
        assertArrayEquals(before, Files.readAllBytes(apk));
    }

    @Test void extractsCompleteBytesIncludingSignatureEntriesAndProtectsFiles() throws Exception {
        byte[] payload = new byte[1024 * 1024 + 3]; new Random(47).nextBytes(payload);
        Path apk = zip(Map.of("large.bin", payload, "META-INF/TEST.SF", bytes("signature")), false);
        Path output = temporary.resolve("extracted.bin"); byte[] before = Files.readAllBytes(apk);
        assertEquals(0, cli("file", "extract", apk.toString(), "large.bin", "-o", output.toString()).code);
        assertArrayEquals(payload, Files.readAllBytes(output));
        assertEquals(1, cli("file", "extract", apk.toString(), "META-INF/TEST.SF", "-o", output.toString()).code);
        assertArrayEquals(payload, Files.readAllBytes(output));
        assertEquals(0, cli("file", "extract", apk.toString(), "META-INF/TEST.SF", "-o", output.toString(), "--force").code);
        assertEquals("signature", Files.readString(output));
        assertEquals(1, cli("file", "extract", apk.toString(), "large.bin", "-o", apk.toString(), "--force").code);
        assertArrayEquals(before, Files.readAllBytes(apk));
    }

    @Test void crcFailureNeverPublishesPartialOrReplacesExistingOutput() throws Exception {
        Path apk = zip(Map.of("payload.bin", bytes("original-payload")), true);
        long offset;
        try (var session = new ApkInspectionSession(apk)) { offset = session.dataOffset("payload.bin"); }
        try (var file = new RandomAccessFile(apk.toFile(), "rw")) { file.seek(offset); file.write('X'); }
        Path output = temporary.resolve("result.bin"); Files.writeString(output, "keep");
        Result result = cli("file", "extract", apk.toString(), "payload.bin", "-o", output.toString(), "--force");
        assertEquals(1, result.code); assertTrue(result.err.contains("CRC"), result.err);
        assertEquals("keep", Files.readString(output));
        try (var files = Files.list(temporary)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".ed4apk-"))); }
    }

    @Test void duplicatePathsAreVisibleButContentLookupIsRejected() throws Exception {
        var contents = new LinkedHashMap<String, byte[]>(); contents.put("a.txt", bytes("a")); contents.put("b.txt", bytes("b"));
        Path apk = zip(contents, true); byte[] raw = Files.readAllBytes(apk);
        byte[] search = bytes("b.txt");
        for (int i = 0; i <= raw.length - search.length; i++) {
            if (Arrays.equals(raw, i, i + search.length, search, 0, search.length)) raw[i] = 'a';
        }
        Files.write(apk, raw);
        Result listed = cli("file", "list", apk.toString(), "--json");
        assertEquals(1, listed.code); assertEquals(2, listed.json().path("data").path("entries").size());
        assertEquals("duplicate_entry", listed.json().path("diagnostics").get(0).path("code").asText());
        Path output = temporary.resolve("duplicate.txt");
        assertEquals(1, cli("file", "extract", apk.toString(), "a.txt", "-o", output.toString()).code);
        assertFalse(Files.exists(output));
    }

    @Test void corruptLocalHeaderLengthsCannotProduceConfirmedPayloadOffsets() throws Exception {
        Path apk = zip(Map.of("payload.bin", bytes("payload")), true);
        byte[] raw = Files.readAllBytes(apk);
        // The central directory remains enumerable, but this local extra length points past EOF.
        raw[28] = (byte) 255; raw[29] = (byte) 255;
        Files.write(apk, raw);
        Result result = cli("file", "list", apk.toString(), "--json");
        assertEquals(1, result.code);
        JsonNode report = result.json();
        assertEquals("payload.bin", report.path("data").path("entries").get(0).path("path").asText());
        assertTrue(report.path("data").path("entries").get(0).path("dataOffset").isNull());
        assertFalse(report.path("diagnostics").isEmpty());
    }

    @Test void previewReportsTruncationAndHandlesBinaryUnicodeAndControlCharacters() throws Exception {
        Path apk = zip(Map.of("text", bytes("hello\u001b[31m\rworld"), "unicode", bytes("你好"),
                "binary", new byte[] {0, 1, (byte) 255}, "bad", new byte[] {(byte) 255}), false);
        Result text = cli("file", "show", apk.toString(), "text");
        assertEquals(0, text.code); assertFalse(text.out.contains("\u001b"));
        assertTrue(text.out.contains("\\u001b")); assertTrue(text.out.contains("\\u000d"));
        JsonNode unicode = cli("file", "show", apk.toString(), "unicode", "--limit", "4", "--json").json().path("data");
        assertEquals("text", unicode.path("format").asText()); assertEquals("你", unicode.path("content").asText());
        assertTrue(unicode.path("truncated").asBoolean());
        JsonNode binary = cli("file", "show", apk.toString(), "binary", "--json").json().path("data");
        assertEquals("hex", binary.path("format").asText()); assertFalse(binary.path("truncated").asBoolean());
        assertEquals(1, cli("file", "show", apk.toString(), "bad", "--format", "text").code);
        assertEquals(2, cli("file", "show", apk.toString(), "text", "--limit", "0").code);
        assertEquals(2, cli("file", "list", apk.toString(), "--sort", "wat").code);
    }

    @Test void infoKeepsZipResultsOnBadManifestAndAddsMetadataOnValidManifest() throws Exception {
        Path bad = zip(Map.of("AndroidManifest.xml", bytes("bad"), "classes.dex", bytes("bad dex")), true);
        Result result = cli("info", bad.toString(), "--json");
        assertEquals(1, result.code); assertEquals(2, result.json().path("data").path("entryCount").asInt());
        assertEquals(1, result.json().path("data").path("dexCount").asInt());
        assertTrue(result.json().path("data").path("manifest").isNull());
        var manifest = new AndroidManifestBlock(); manifest.setPackageName("sample");
        manifest.setVersionCode(3); manifest.setVersionName("1.2"); manifest.setMinSdkVersion(23); manifest.setTargetSdkVersion(35);
        manifest.refreshFull();
        Path valid = zip(Map.of("AndroidManifest.xml", manifest.getBytes(), "lib/arm64-v8a/libbad.so", bytes("bad elf"),
                "classes.dex", bytes("bad dex")), true);
        Result good = cli("info", valid.toString(), "--json");
        assertEquals(0, good.code, good.out + good.err);
        JsonNode data = good.json().path("data");
        assertEquals("sample", data.path("manifest").path("packageName").asText());
        assertEquals(35, data.path("manifest").path("targetSdk").asInt());
        assertEquals("arm64-v8a", data.path("abis").get(0).asText());
        assertEquals(Files.size(valid), data.path("size").asLong());
        Result xml = cli("file", "show", valid.toString(), "AndroidManifest.xml", "--format", "xml");
        assertEquals(0, xml.code, xml.err); assertTrue(xml.out.contains("package=\"sample\""), xml.out);
        assertEquals(1, cli("file", "show", valid.toString(), "AndroidManifest.xml", "--format", "xml", "--limit", "8").code);
    }
}
