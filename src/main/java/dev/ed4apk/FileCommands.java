package dev.ed4apk;

import picocli.CommandLine;
import picocli.CommandLine.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;

@Command(name = "file", mixinStandardHelpOptions = true, description = "Browse, preview and extract APK entries without editing",
        subcommands = {FileCommands.ListCommand.class, FileCommands.Show.class, FileCommands.Extract.class})
final class FileCommands {
    static Map<String, Object> describe(ApkInspectionSession session, ZipEntry entry) {
        return Inspection.object("path", entry.getName(), "directory", entry.isDirectory(),
                "size", entry.getSize(), "compressedSize", entry.getCompressedSize(),
                "method", entry.getMethod() == ZipEntry.STORED ? "stored" : "deflated",
                "crc32", String.format(Locale.ROOT, "%08x", entry.getCrc()),
                "dataOffset", entry.isDirectory() ? null : session.dataOffset(entry.getName()));
    }

    private static int failure(boolean json, String entry, Exception e) throws IOException {
        var diagnostics = List.of(Inspection.diagnostic("read_failed", entry, Inspection.message(e)));
        if (json) Main.printJson(Inspection.report(null, diagnostics));
        else Inspection.printDiagnostics(diagnostics);
        return 1;
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List ZIP entries, sizes, compression and payload offsets",
            footer = {"", "Example: ed4apk file list app.apk --prefix lib/ --sort size --limit 20 --json",
                    "Sizes are bytes; size sorting is descending. No --limit means all matches.",
                    "Does not parse Manifest, DEX or ELF. Duplicate paths are listed but cannot be extracted by name."})
    static class ListCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK") Path apk;
        @Option(names = "--prefix", defaultValue = "", description = "Case-sensitive APK path prefix") String prefix;
        @Option(names = "--sort", defaultValue = "path", description = "path, size or compressed-size") String sort;
        @Option(names = "--limit", description = "Maximum results (nonnegative); omitted means all") Integer limit;
        @Option(names = "--json", description = "Emit a versioned JSON report") boolean json;
        @Spec CommandLine.Model.CommandSpec spec;

        @Override public Integer call() throws Exception {
            if (limit != null && limit < 0) throw new ParameterException(spec.commandLine(), "--limit must be nonnegative");
            if (!Set.of("path", "size", "compressed-size").contains(sort))
                throw new ParameterException(spec.commandLine(), "--sort must be path, size or compressed-size");
            try (var session = new ApkInspectionSession(apk)) {
                List<ZipEntry> matches = new ArrayList<>();
                for (ZipEntry entry : session.entries()) if (entry.getName().startsWith(prefix)) matches.add(entry);
                Comparator<ZipEntry> order = Comparator.comparing(ZipEntry::getName);
                if (!sort.equals("path")) order = Comparator.comparingLong((ZipEntry e) ->
                        sort.equals("size") ? e.getSize() : e.getCompressedSize()).reversed().thenComparing(order);
                matches.sort(order);
                int count = limit == null ? matches.size() : Math.min(limit, matches.size());
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < count; i++) rows.add(describe(session, matches.get(i)));
                var diagnostics = session.diagnostics();
                var data = Inspection.object("entries", rows, "matchedCount", matches.size(),
                        "returnedCount", count, "truncated", count < matches.size());
                if (json) Main.printJson(Inspection.report(data, diagnostics));
                else {
                    System.out.printf("%12s %12s %-9s %s%n", "SIZE", "PACKED", "METHOD", "PATH");
                    for (var row : rows) System.out.printf("%12s %12s %-9s %s%n", row.get("size"),
                            row.get("compressedSize"), row.get("method"), Inspection.safeText((String) row.get("path")));
                    System.out.printf("Returned %d of %d matching entries.%n", count, matches.size());
                    Inspection.printDiagnostics(diagnostics);
                }
                return diagnostics.isEmpty() ? 0 : 1;
            } catch (IOException | RuntimeException e) { return failure(json, null, e); }
        }
    }

    @Command(name = "extract", mixinStandardHelpOptions = true, description = "Extract one entry's original uncompressed bytes",
            footer = {"", "Example: ed4apk file extract app.apk lib/arm64-v8a/libfoo.so -o libfoo.so",
                    "Streams and checks CRC before publishing. Binary XML stays binary; use manifest show --format xml to decode."})
    static class Extract implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK") Path apk;
        @Parameters(index = "1", paramLabel = "ENTRY") String entry;
        @Mixin Main.Output output;

        @Override public Integer call() throws Exception {
            try (var session = new ApkInspectionSession(apk)) {
                session.entry(entry);
                Outputs.write(apk, output.path, output.force, target -> {
                    try (InputStream input = session.open(entry); OutputStream destination = Files.newOutputStream(target)) {
                        input.transferTo(destination);
                    }
                });
            }
            System.out.println("Extracted " + Inspection.safeText(entry) + " to " + Inspection.safeText(output.path.toString()));
            return 0;
        }
    }

    @Command(name = "show", mixinStandardHelpOptions = true, description = "Preview an entry as UTF-8, hex, or decoded binary XML",
            footer = {"", "Examples: ed4apk file show app.apk assets/config.json",
                    "          ed4apk file show app.apk assets/data.bin --format hex --limit 256",
                    "Preview limit defaults to 64 KiB (maximum 16 MiB). Truncation is explicit; extract for full bytes.",
                    "XML decoding requires the complete entry within the limit. Terminal control characters are escaped."})
    static class Show implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK") Path apk;
        @Parameters(index = "1", paramLabel = "ENTRY") String entry;
        @Option(names = "--format", defaultValue = "auto", description = "auto, text, hex or xml") String format;
        @Option(names = "--limit", defaultValue = "65536", description = "Maximum uncompressed preview bytes (1..16777216)") int limit;
        @Option(names = "--json", description = "Emit a versioned JSON report with preview and truncation state") boolean json;
        @Spec CommandLine.Model.CommandSpec spec;

        @Override public Integer call() throws Exception {
            if (!Set.of("auto", "text", "hex", "xml").contains(format))
                throw new ParameterException(spec.commandLine(), "--format must be auto, text, hex or xml");
            if (limit < 1 || limit > 16 * 1024 * 1024)
                throw new ParameterException(spec.commandLine(), "--limit must be between 1 and 16777216 bytes");
            try (var session = new ApkInspectionSession(apk)) {
                ZipEntry metadata = session.entry(entry);
                byte[] bytes;
                boolean truncated;
                if (format.equals("xml")) {
                    bytes = session.read(entry, limit);
                    truncated = false;
                } else {
                    try (InputStream input = session.open(entry)) { bytes = input.readNBytes(limit + 1); }
                    truncated = bytes.length > limit;
                    if (truncated) bytes = Arrays.copyOf(bytes, limit);
                }
                String rendered;
                String effective = format;
                if (format.equals("xml")) rendered = ManifestInspector.decodeXml(bytes);
                else if (format.equals("hex")) rendered = hex(bytes);
                else {
                    String decoded = utf8(bytes, truncated);
                    boolean binary = decoded != null && decoded.indexOf('\0') >= 0;
                    if (format.equals("auto") && (decoded == null || binary)) {
                        effective = "hex"; rendered = hex(bytes);
                    } else {
                        if (decoded == null) throw new IOException("Entry is not valid UTF-8; use --format hex");
                        effective = "text"; rendered = decoded;
                    }
                }
                var data = Inspection.object("entry", describe(session, metadata), "format", effective,
                        "previewBytes", bytes.length, "truncated", truncated, "content", rendered);
                var diagnostics = session.diagnostics();
                if (json) Main.printJson(Inspection.report(data, diagnostics));
                else {
                    System.out.print(Inspection.safeContent(rendered));
                    if (!rendered.endsWith("\n")) System.out.println();
                    if (truncated) System.out.println("[Preview truncated at " + bytes.length + " bytes; use file extract for complete content.]");
                    Inspection.printDiagnostics(diagnostics);
                }
                return diagnostics.isEmpty() ? 0 : 1;
            } catch (IOException | RuntimeException e) { return failure(json, entry, e); }
        }
    }

    private static String utf8(byte[] bytes, boolean truncated) {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer output = CharBuffer.allocate(bytes.length);
        CoderResult result = decoder.decode(ByteBuffer.wrap(bytes), output, !truncated);
        if (result.isError()) return null;
        output.flip();
        return output.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < bytes.length; i += 16) {
            text.append(String.format(Locale.ROOT, "%08x  ", i));
            for (int j = 0; j < 16; j++) text.append(i + j < bytes.length
                    ? String.format(Locale.ROOT, "%02x ", bytes[i + j] & 255) : "   ");
            text.append(" |");
            for (int j = i; j < Math.min(bytes.length, i + 16); j++) {
                int c = bytes[j] & 255;
                text.append(c >= 32 && c < 127 ? (char) c : '.');
            }
            text.append("|\n");
        }
        return text.toString();
    }
}
