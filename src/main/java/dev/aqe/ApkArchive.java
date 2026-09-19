package dev.aqe;

import com.android.zipflinger.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipFile;

final class ApkArchive {
    private static final Pattern SIGNATURE = Pattern.compile(
            "META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC)|SIG-[^/]+)", Pattern.CASE_INSENSITIVE);
    static final Pattern DEX_NAME = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");

    static final class Replacement {
        private final Path file;
        private final byte[] bytes;
        private Replacement(Path file, byte[] bytes) { this.file = file; this.bytes = bytes; }
        Path file() { return file; }
        byte[] bytes() { return bytes; }
        static Replacement file(Path path) { return new Replacement(path, null); }
        static Replacement bytes(byte[] bytes) { return new Replacement(null, bytes); }
    }

    static void validate(Path apk) throws IOException {
        Set<String> seen = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!seen.add(entry.getName())) throw new IOException("Duplicate ZIP entry: " + entry.getName());
                if (entry.getMethod() != 0 && entry.getMethod() != 8)
                    throw new IOException("Unsupported ZIP compression: " + entry.getName());
            }
            if (!seen.contains("AndroidManifest.xml")) throw new IOException("APK has no AndroidManifest.xml");
        }
    }

    static List<String> dexNames(Path apk) throws IOException {
        validate(apk);
        return ZipArchive.listEntries(apk).keySet().stream()
                .filter(n -> DEX_NAME.matcher(n).matches())
                .sorted(Comparator.comparingInt(ApkArchive::dexNumber)).collect(Collectors.toList());
    }

    private static int dexNumber(String name) {
        return name.equals("classes.dex") ? 1 : Integer.parseInt(name.substring(7, name.length() - 4));
    }

    static byte[] read(Path apk, String name) throws IOException {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            var entry = zip.getEntry(name);
            if (entry == null) throw new IOException("APK entry not found: " + name);
            try (var stream = zip.getInputStream(entry)) { return stream.readAllBytes(); }
        }
    }

    static boolean signatureEntry(String name) {
        return SIGNATURE.matcher(name).matches() || name.equals("stamp-cert-sha256");
    }

    static void checkEntryName(String name) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.endsWith("/")
                || name.indexOf('\0') >= 0 || Arrays.stream(name.split("/", -1))
                .anyMatch(p -> p.equals("..") || p.equals(".") || p.isEmpty()))
            throw new IllegalArgumentException("Invalid APK entry path: " + name);
        if (signatureEntry(name)) throw new IllegalArgumentException("Use the sign command to create signatures");
    }

    static long alignment(String name, boolean compressed) {
        if (compressed) return Source.NO_ALIGNMENT;
        return name.startsWith("lib/") && name.endsWith(".so") ? 16384 : 4;
    }

    static void rewrite(Path input, Path output, Map<String, Replacement> replacements,
                        boolean force) throws Exception {
        rewrite(input, output, replacements, Set.of(), force);
    }

    static void rewrite(Path input, Path output, Map<String, Replacement> replacements,
                        Set<String> deletions, boolean force) throws Exception {
        validate(input);
        for (String name : deletions) {
            checkEntryName(name);
            if (replacements.containsKey(name)) throw new IOException("Entry is both replaced and deleted: " + name);
        }
        if (deletions.contains("AndroidManifest.xml")) throw new IOException("Cannot remove AndroidManifest.xml");
        for (var item : replacements.entrySet()) {
            checkEntryName(item.getKey());
            if (item.getValue().file() != null && !Files.isRegularFile(item.getValue().file()))
                throw new IOException("Replacement file not found: " + item.getValue().file());
        }
        Outputs.write(input, output, force, temporary -> write(input, temporary, replacements, deletions));
    }

    private static void write(Path input, Path output, Map<String, Replacement> replacements,
                              Set<String> deletions) throws IOException {
        ZipSource original = new ZipSource(input);
        Map<String, Entry> entries = original.entries();
        for (Entry entry : entries.values()) {
            String name = entry.getName();
            if (!replacements.containsKey(name) && !deletions.contains(name) && !signatureEntry(name)) {
                original.select(name, name, ZipSource.COMPRESSION_NO_CHANGE,
                        alignment(name, entry.isCompressed()));
            }
        }
        // A fresh ZIP omits the old APK Signing Block as well as replaced entries.
        Files.deleteIfExists(output);
        try (ZipArchive archive = new ZipArchive(output)) {
            archive.setComment(original.getComment());
            archive.add(original);
            for (var item : replacements.entrySet()) {
                String name = item.getKey();
                Entry old = entries.get(name);
                boolean stored = name.equals("resources.arsc")
                        || (name.startsWith("lib/") && name.endsWith(".so"))
                        || (old != null && !old.isCompressed());
                int compression = stored ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED;
                Replacement replacement = item.getValue();
                Source source = replacement.file() == null
                        ? new BytesSource(replacement.bytes(), name, compression)
                        : new LargeFileSource(replacement.file(), name, compression);
                if (old != null) source.setExternalAttributes(old.getExternalAttributes());
                source.align(alignment(name, !stored));
                archive.add(source);
            }
        }
        checkAlignment(output);
    }

    static void checkAlignment(Path apk) throws IOException {
        for (Entry entry : ZipArchive.listEntries(apk).values()) {
            if (entry.isCompressed() || entry.isDirectory()) continue;
            long required = alignment(entry.getName(), false);
            if (entry.getPayloadLocation().first % required != 0)
                throw new IOException("Unaligned APK entry: " + entry.getName());
        }
    }
}
