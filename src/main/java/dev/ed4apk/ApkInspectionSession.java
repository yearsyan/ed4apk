package dev.ed4apk;

import com.android.zipflinger.Entry;
import com.android.zipflinger.ZipArchive;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Collectors;

/** A read-only ZIP view. Browsing does not require a valid Manifest or editable APK. */
final class ApkInspectionSession implements AutoCloseable {
    private final Path path;
    private final long fileSize;
    private final ZipFile zip;
    private final List<ZipEntry> entries = new ArrayList<>();
    private final Map<String, List<ZipEntry>> byName = new LinkedHashMap<>();
    private final List<Map<String, Object>> diagnostics = new ArrayList<>();
    private Map<String, Entry> offsets;
    private boolean offsetsAttempted;
    private final Set<String> invalidOffsets = new HashSet<>();

    ApkInspectionSession(Path path) throws IOException {
        this.path = path;
        this.fileSize = Files.size(path);
        this.zip = new ZipFile(path.toFile());
        try {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                entries.add(entry);
                byName.computeIfAbsent(entry.getName(), key -> new ArrayList<>()).add(entry);
            }
            for (var group : byName.entrySet()) {
                if (group.getValue().size() > 1) diagnostics.add(Inspection.diagnostic("duplicate_entry",
                        group.getKey(), "Multiple ZIP entries have this name; content lookup is ambiguous"));
            }
        } catch (RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    List<ZipEntry> entries() { return Collections.unmodifiableList(entries); }
    List<Map<String, Object>> diagnostics() { return new ArrayList<>(diagnostics); }

    ZipEntry entry(String name) throws IOException {
        List<ZipEntry> matches = byName.get(name);
        if (matches == null) throw new IOException("APK entry not found: " + name);
        if (matches.size() != 1) throw new IOException("Ambiguous duplicate ZIP entry: " + name);
        return matches.get(0);
    }

    InputStream open(String name) throws IOException {
        ZipEntry entry = entry(name);
        if (entry.isDirectory()) throw new IOException("Entry is a directory: " + name);
        return new FilterInputStream(zip.getInputStream(entry)) {
            final CRC32 crc = new CRC32();
            long count;
            boolean verified;

            private void complete() throws IOException {
                if (verified) return;
                if (count != entry.getSize()) throw new IOException("ZIP size mismatch: " + name);
                if (crc.getValue() != entry.getCrc()) throw new IOException("ZIP CRC mismatch: " + name);
                verified = true;
            }

            @Override public int read() throws IOException {
                int value = in.read();
                if (value < 0) complete();
                else { crc.update(value); count++; }
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = in.read(bytes, offset, length);
                if (read < 0) complete();
                else { crc.update(bytes, offset, read); count += read; }
                return read;
            }

            // Maintain CRC accounting when a parser skips through a compressed header prefix.
            @Override public long skip(long amount) throws IOException {
                if (amount <= 0) return 0;
                byte[] buffer = new byte[(int) Math.min(8192, amount)];
                long skipped = 0;
                while (skipped < amount) {
                    int read = read(buffer, 0, (int) Math.min(buffer.length, amount - skipped));
                    if (read < 0) break;
                    skipped += read;
                }
                return skipped;
            }
        };
    }

    byte[] read(String name, int maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("Negative read limit");
        ZipEntry entry = entry(name);
        if (entry.getSize() > maxBytes) throw new IOException("Entry exceeds read limit of " + maxBytes + " bytes: " + name);
        try (InputStream input = open(name); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > maxBytes - output.size()) throw new IOException("Entry exceeds read limit: " + name);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    Long dataOffset(String name) {
        if (!offsetsAttempted) {
            offsetsAttempted = true;
            if (byName.values().stream().anyMatch(group -> group.size() > 1)) return null;
            try {
                offsets = ZipArchive.listEntries(path);
            } catch (IOException | RuntimeException e) {
                diagnostics.add(Inspection.diagnostic("zip_offsets_unavailable", null, Inspection.message(e)));
            }
        }
        if (offsets == null || invalidOffsets.contains(name)) return null;
        Entry found = offsets.get(name);
        try {
            ZipEntry original = entry(name);
            if (found == null || found.getCompressionFlag() != original.getMethod()
                    || found.getCompressedSize() != original.getCompressedSize()
                    || found.getUncompressedSize() != original.getSize()
                    || Integer.toUnsignedLong(found.getCrc()) != original.getCrc())
                throw new IOException("ZIP readers disagree on entry metadata");
            long offset = found.getPayloadLocation().first;
            long length = original.getCompressedSize();
            if (offset < 0 || length < 0 || offset > fileSize || length > fileSize - offset
                    || found.getPayloadLocation().size() != length)
                throw new IOException("ZIP payload range is outside the archive or disagrees with its declared size");
            return offset;
        } catch (IOException | RuntimeException e) {
            invalidOffsets.add(name);
            diagnostics.add(Inspection.diagnostic("zip_entry_offset_unknown", name, Inspection.message(e)));
            return null;
        }
    }

    List<String> dexNames() {
        return byName.keySet().stream().filter(name -> ApkArchive.DEX_NAME.matcher(name).matches())
                .sorted(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()))
                .collect(Collectors.toList());
    }

    @Override public void close() throws IOException { zip.close(); }
}
