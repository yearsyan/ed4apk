package dev.ed4apk;

import com.android.tools.smali.dexlib2.iface.ClassDef;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

/** Read-only DEX metadata. Header inspection never reads method bodies or the complete entry. */
final class DexInspector {
    static final int MAX_DEX_BYTES = 256 * 1024 * 1024;
    static final int MAX_LIST_CLASSES = 1_000_000;
    private static final int MAX_DESCRIPTOR_CHARS = 65_536;
    private static final int MAX_TOTAL_DESCRIPTOR_CHARS = 32 * 1024 * 1024;
    private static final Set<Integer> HEADER_VERSIONS = Set.of(35, 37, 38, 39, 40);

    private DexInspector() {}

    static final class Result {
        final List<Map<String, Object>> entries = new ArrayList<>();
        final List<Map<String, Object>> classes = new ArrayList<>();
        final List<Map<String, Object>> diagnostics = new ArrayList<>();
        boolean complete = true;

        Map<String, Object> data(boolean includeClasses) {
            Map<String, Object> data = Inspection.object("entries", entries, "entryCount", entries.size(),
                    "complete", complete);
            if (includeClasses) {
                data.put("classes", classes);
                data.put("classCount", classes.size());
            }
            return data;
        }

        void problem(String code, String entry, String message) {
            diagnostics.add(Inspection.diagnostic(code, entry, message));
            complete = false;
        }
    }

    static Result inspect(ApkInspectionSession session, List<String> names, boolean listClasses,
                          String descriptorPrefix) {
        Result result = new Result();
        result.diagnostics.addAll(session.diagnostics());
        result.complete = result.diagnostics.isEmpty();
        long attemptedClasses = 0;
        long descriptorChars = 0;
        for (String name : names) {
            Header header = new Header();
            try {
                ZipEntry entry = session.entry(name);
                header.entrySize = entry.getSize();
                try (InputStream input = session.open(name)) {
                    header = inspectHeader(input.readNBytes(112), entry.getSize());
                }
                if (header.code != null) {
                    result.problem(header.code, name, header.message);
                } else if (listClasses) {
                    if (!"little".equals(header.byteOrder))
                        throw new InspectionFailure("DEX_ENDIAN_UNSUPPORTED",
                                "Class listing does not support byte-swapped DEX files");
                    if (entry.getSize() > MAX_DEX_BYTES)
                        throw new InspectionFailure("DEX_READ_LIMIT",
                                "DEX class listing exceeds the " + MAX_DEX_BYTES + " byte per-entry limit");
                    if (header.classDefCount > MAX_LIST_CLASSES - attemptedClasses)
                        throw new InspectionFailure("DEX_CLASS_LIMIT",
                                "DEX class listing exceeds the " + MAX_LIST_CLASSES + " class scan limit");
                    attemptedClasses += header.classDefCount;
                    byte[] bytes = session.read(name, MAX_DEX_BYTES);
                    descriptorChars += validateDescriptorBudget(bytes, MAX_TOTAL_DESCRIPTOR_CHARS - descriptorChars);
                    // Do not expose a partially decoded entry as a complete class list.
                    List<Map<String, Object>> decoded = new ArrayList<>();
                    long classCount = 0;
                    for (ClassDef cls : DexEditor.parse(bytes).getClasses()) {
                        String descriptor = cls.getType();
                        if (!descriptor.startsWith("L") || !descriptor.endsWith(";"))
                            throw new InspectionFailure("DEX_CLASS_INVALID", "Invalid class descriptor: "
                                    + Inspection.safeText(descriptor));
                        classCount++;
                        if (descriptorPrefix == null || descriptor.startsWith(descriptorPrefix))
                            decoded.add(Inspection.object("entry", name, "descriptor", descriptor));
                    }
                    if (classCount != header.classDefCount)
                        throw new InspectionFailure("DEX_CLASS_COUNT_MISMATCH",
                                "Decoded class count does not match class_defs_size");
                    result.classes.addAll(decoded);
                }
            } catch (InspectionFailure error) {
                header.code = error.code;
                header.message = error.getMessage();
                result.problem(error.code, name, error.getMessage());
            } catch (IOException | RuntimeException error) {
                header.code = "DEX_READ_FAILED";
                header.message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                result.problem(header.code, name, header.message);
            }
            result.entries.add(header.toMap(name));
        }
        return result;
    }

    /** dexlib allocates by the declared string length; bound that length before asking for a type. */
    private static long validateDescriptorBudget(byte[] bytes, long remainingCharacters) throws InspectionFailure {
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long classCount = unsigned(header, 96);
        long classOffset = unsigned(header, 100);
        long typeCount = unsigned(header, 64);
        long typeOffset = unsigned(header, 68);
        long stringCount = unsigned(header, 56);
        long stringOffset = unsigned(header, 60);
        long characters = 0;
        for (long index = 0; index < classCount; index++) {
            long typeIndex = unsigned(header, (int) (classOffset + index * 32));
            if (typeIndex >= typeCount)
                throw new InspectionFailure("DEX_CLASS_INVALID", "Class definition has an invalid type index");
            long stringIndex = unsigned(header, (int) (typeOffset + typeIndex * 4));
            if (stringIndex >= stringCount)
                throw new InspectionFailure("DEX_CLASS_INVALID", "Class descriptor has an invalid string index");
            long offset = unsigned(header, (int) (stringOffset + stringIndex * 4));
            if (offset < unsigned(header, 108) || offset >= bytes.length)
                throw new InspectionFailure("DEX_CLASS_INVALID", "Class descriptor string is outside DEX data");
            long length = 0;
            boolean ended = false;
            for (int shift = 0; shift < 35 && offset < bytes.length; shift += 7) {
                int value = bytes[(int) offset++] & 0xff;
                if (shift == 28 && (value & 0xf0) != 0)
                    throw new InspectionFailure("DEX_CLASS_INVALID", "Invalid descriptor UTF-16 length");
                length |= (long) (value & 0x7f) << shift;
                if ((value & 0x80) == 0) { ended = true; break; }
            }
            if (!ended)
                throw new InspectionFailure("DEX_CLASS_INVALID", "Truncated descriptor UTF-16 length");
            if (length > MAX_DESCRIPTOR_CHARS || length > remainingCharacters - characters)
                throw new InspectionFailure("DEX_DESCRIPTOR_LIMIT", "Descriptor text exceeds the "
                        + MAX_DESCRIPTOR_CHARS + " character per-class or " + MAX_TOTAL_DESCRIPTOR_CHARS
                        + " character total inspection limit");
            if (length >= bytes.length - offset)
                throw new InspectionFailure("DEX_CLASS_INVALID", "Descriptor text cannot fit inside DEX data");
            characters += length;
        }
        return characters;
    }

    static final class Header {
        String version;
        String byteOrder;
        long entrySize = -1;
        Long fileSize;
        Long classDefCount;
        Long methodIdCount;
        Long fieldIdCount;
        String code;
        String message;

        Map<String, Object> toMap(String entry) {
            return Inspection.object("entry", entry, "size", entrySize < 0 ? null : entrySize,
                    "version", version, "byteOrder", byteOrder, "fileSize", fileSize,
                    "classDefCount", classDefCount, "methodIdCount", methodIdCount,
                    "fieldIdCount", fieldIdCount, "status", code == null ? "pass" : "unknown");
        }
    }

    static Header inspectHeader(byte[] headerBytes, long entrySize) {
        Header result = new Header();
        result.entrySize = entrySize;
        try {
            if (headerBytes.length < 8)
                throw new InspectionFailure("DEX_HEADER_TRUNCATED", "DEX magic is truncated; expected 8 bytes");
            if (headerBytes[0] != 'd' || headerBytes[1] != 'e' || headerBytes[2] != 'x'
                    || headerBytes[3] != '\n' || headerBytes[7] != 0)
                throw new InspectionFailure("DEX_MAGIC_INVALID", "Not a standard DEX file (invalid magic)");
            for (int index = 4; index <= 6; index++) {
                if (headerBytes[index] < '0' || headerBytes[index] > '9')
                    throw new InspectionFailure("DEX_VERSION_INVALID", "Invalid DEX version in magic");
            }
            result.version = new String(headerBytes, 4, 3, StandardCharsets.US_ASCII);
            int version = Integer.parseInt(result.version);
            if (!HEADER_VERSIONS.contains(version))
                throw new InspectionFailure("DEX_VERSION_UNSUPPORTED", "DEX " + result.version
                        + (version >= 41 ? " containers" : " headers")
                        + " are not yet supported; counts are unknown");
            if (headerBytes.length < 112)
                throw new InspectionFailure("DEX_HEADER_TRUNCATED", "DEX header is truncated; expected 112 bytes");
            ByteBuffer buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
            int endian = buffer.getInt(40);
            if (endian == 0x12345678) result.byteOrder = "little";
            else if (endian == 0x78563412) {
                result.byteOrder = "big";
                buffer.order(ByteOrder.BIG_ENDIAN);
            } else throw new InspectionFailure("DEX_ENDIAN_INVALID", "Invalid DEX endian tag");
            long fileSize = unsigned(buffer, 32);
            result.fileSize = fileSize;
            if (entrySize < 0)
                throw new InspectionFailure("DEX_SIZE_UNKNOWN", "ZIP entry size is unavailable");
            if (fileSize < 112 || fileSize != entrySize)
                throw new InspectionFailure("DEX_SIZE_MISMATCH", "DEX file_size=" + fileSize
                        + " does not match ZIP entry size=" + entrySize);
            if (unsigned(buffer, 36) != 112)
                throw new InspectionFailure("DEX_HEADER_SIZE_INVALID", "DEX " + result.version
                        + " requires header_size=112");

            List<long[]> ranges = new ArrayList<>();
            table(buffer, 56, 4, "string_ids", fileSize, ranges);
            table(buffer, 64, 4, "type_ids", fileSize, ranges);
            table(buffer, 72, 12, "proto_ids", fileSize, ranges);
            table(buffer, 80, 8, "field_ids", fileSize, ranges);
            table(buffer, 88, 8, "method_ids", fileSize, ranges);
            table(buffer, 96, 32, "class_defs", fileSize, ranges);
            range(unsigned(buffer, 44), unsigned(buffer, 48), "link_data", fileSize, false);
            long dataSize = unsigned(buffer, 104);
            long dataOffset = unsigned(buffer, 108);
            range(dataSize, dataOffset, "data", fileSize, true);
            long mapOffset = unsigned(buffer, 52);
            if (mapOffset < dataOffset || mapOffset % 4 != 0 || mapOffset > dataOffset + dataSize - 4
                    || mapOffset < 112)
                throw new InspectionFailure("DEX_MAP_RANGE_INVALID", "DEX map_off is outside the data section");
            ranges.add(new long[] {dataOffset, dataOffset + dataSize});
            ranges.sort((left, right) -> Long.compare(left[0], right[0]));
            for (int index = 1; index < ranges.size(); index++) {
                if (ranges.get(index)[0] < ranges.get(index - 1)[1])
                    throw new InspectionFailure("DEX_TABLE_OVERLAP", "DEX header sections overlap");
            }
            // Assign only after all header range checks pass; ID counts are not definition counts.
            result.fieldIdCount = unsigned(buffer, 80);
            result.methodIdCount = unsigned(buffer, 88);
            result.classDefCount = unsigned(buffer, 96);
        } catch (InspectionFailure error) {
            result.code = error.code;
            result.message = error.getMessage();
        }
        return result;
    }

    private static void table(ByteBuffer header, int at, int itemSize, String name, long fileSize,
                              List<long[]> ranges) throws InspectionFailure {
        long length = unsigned(header, at) * itemSize;
        long offset = unsigned(header, at + 4);
        range(length, offset, name, fileSize, true);
        if (length != 0) ranges.add(new long[] {offset, offset + length});
    }

    private static void range(long length, long offset, String name, long fileSize, boolean aligned)
            throws InspectionFailure {
        if (length == 0 && offset == 0) return;
        if (length == 0 || offset < 112 || offset > fileSize || length > fileSize - offset
                || (aligned && offset % 4 != 0))
            throw new InspectionFailure("DEX_TABLE_RANGE_INVALID", "Invalid " + name
                    + " range: offset=" + offset + ", size=" + length + ", file_size=" + fileSize);
    }

    private static long unsigned(ByteBuffer buffer, int at) {
        return Integer.toUnsignedLong(buffer.getInt(at));
    }

    private static final class InspectionFailure extends IOException {
        final String code;
        InspectionFailure(String code, String message) { super(message); this.code = code; }
    }
}
