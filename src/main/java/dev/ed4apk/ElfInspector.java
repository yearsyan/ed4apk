package dev.ed4apk;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads only ELF headers. No sections, symbols, disassembly or native execution. */
final class ElfInspector {
    static final int READ_BUDGET = 8 * 1024 * 1024;
    private static final BigInteger MAX_UNSIGNED_64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger PAGE = BigInteger.valueOf(16384);
    private static final long PT_LOAD = 1;
    private static final long PT_GNU_RELRO = 0x6474e552L;

    static final class Result {
        final Map<String, Object> data = Inspection.object("class", null, "byteOrder", null,
                "machine", null, "machineName", null, "type", null, "programHeaderCount", null,
                "readBudgetBytes", READ_BUDGET, "bytesRead", 0L);
        final List<Map<String, Object>> segments = new ArrayList<>();
        final List<Map<String, Object>> diagnostics = new ArrayList<>();
        String parsing = "unknown";
        String loadAlignment = "unknown";
        String relroAlignment = "unknown";
        Integer elfClass;
        Integer machine;
        boolean littleEndian;

        Map<String, Object> report() {
            data.put("parsing", parsing);
            data.put("loadAlignment", loadAlignment);
            data.put("relroAlignment", relroAlignment);
            data.put("segments", segments);
            return data;
        }
    }

    static Result inspect(InputStream stream, long size, String entry) {
        return inspect(stream, size, entry, READ_BUDGET, true);
    }

    static Result inspect(InputStream stream, long size, String entry, int budget) {
        return inspect(stream, size, entry, budget, true);
    }

    static Result inspect(InputStream stream, long size, String entry, boolean policyApplicable) {
        return inspect(stream, size, entry, READ_BUDGET, policyApplicable);
    }

    private static Result inspect(InputStream stream, long size, String entry, int budget, boolean policyApplicable) {
        Result result = new Result();
        result.data.put("readBudgetBytes", budget);
        result.data.put("alignmentPolicyApplicable", policyApplicable);
        Reader reader = new Reader(stream, budget);
        try {
            byte[] ident = reader.read(16);
            if (ident[0] != 0x7f || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F')
                throw new Invalid("elf_invalid_magic", "Entry does not begin with the ELF magic");
            int kind = Byte.toUnsignedInt(ident[4]);
            int encoding = Byte.toUnsignedInt(ident[5]);
            if (kind != 1 && kind != 2)
                throw new Invalid("elf_unsupported_class", "Unsupported ELF class: " + kind);
            if (encoding != 1 && encoding != 2)
                throw new Invalid("elf_unsupported_byte_order", "Unsupported ELF byte order: " + encoding);
            if (ident[6] != 1) throw new Invalid("elf_invalid_version", "Unsupported ELF identification version");
            result.elfClass = kind == 1 ? 32 : 64;
            result.littleEndian = encoding == 1;
            result.data.put("class", result.elfClass);
            result.data.put("byteOrder", result.littleEndian ? "little_endian" : "big_endian");
            int headerSize = kind == 1 ? 52 : 64;
            byte[] header = new byte[headerSize];
            System.arraycopy(ident, 0, header, 0, 16);
            System.arraycopy(reader.read(headerSize - 16), 0, header, 16, headerSize - 16);
            ByteBuffer h = ByteBuffer.wrap(header).order(result.littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            int type = u16(h, 16);
            result.machine = u16(h, 18);
            result.data.put("type", type);
            result.data.put("machine", result.machine);
            result.data.put("machineName", machineName(result.machine));
            if (u32(h, 20) != 1) throw new Invalid("elf_invalid_version", "Unsupported ELF header version");
            if (type != 3) throw new Invalid("elf_not_shared_object", "Expected ET_DYN (3) for a shared library; found type " + type);
            int declaredHeaderSize = u16(h, kind == 1 ? 40 : 52);
            if (declaredHeaderSize < headerSize || size >= 0 && declaredHeaderSize > size)
                throw new Invalid("elf_invalid_header_size", "ELF header size is outside the file or smaller than the required header");
            BigInteger tableOffset = unsigned(h, kind == 1 ? 28 : 32, kind);
            int entrySize = u16(h, kind == 1 ? 42 : 54);
            int count = u16(h, kind == 1 ? 44 : 56);
            result.data.put("programHeaderOffset", hex(tableOffset));
            result.data.put("programHeaderCount", count);
            result.data.put("programHeaderSize", entrySize);
            if (count == 0xffff)
                throw new Invalid("elf_extended_program_headers", "Extended program-header numbering requires section headers and is outside this inspector's scope");
            if (count == 0) throw new Invalid("elf_no_load_segments", "ELF has no program headers or PT_LOAD segments");
            int requiredEntrySize = kind == 1 ? 32 : 56;
            if (entrySize < requiredEntrySize)
                throw new Invalid("elf_invalid_program_header_size", "Program-header entry is smaller than " + requiredEntrySize + " bytes");
            if (tableOffset.compareTo(BigInteger.valueOf(declaredHeaderSize)) < 0)
                throw new Invalid("elf_invalid_program_header_range", "Program-header table overlaps the ELF header");
            BigInteger tableEnd = checkedAdd(tableOffset, BigInteger.valueOf((long) count * entrySize));
            checkFileRange(tableEnd, size, "Program-header table");
            if (tableEnd.compareTo(BigInteger.valueOf(budget)) > 0)
                throw new Invalid("elf_read_budget_exceeded", "Program-header table exceeds the " + budget + "-byte stream read budget");
            boolean loadFailed = false;
            boolean relroFailed = false;
            int loads = 0;
            int relros = 0;
            for (int i = 0; i < count; i++) {
                reader.advanceTo(tableOffset.longValueExact() + (long) i * entrySize);
                ByteBuffer p = ByteBuffer.wrap(reader.read(requiredEntrySize)).order(h.order());
                long segmentType = u32(p, 0);
                long flags = u32(p, kind == 1 ? 24 : 4);
                BigInteger offset = unsigned(p, kind == 1 ? 4 : 8, kind);
                BigInteger address = unsigned(p, kind == 1 ? 8 : 16, kind);
                BigInteger fileSize = unsigned(p, kind == 1 ? 16 : 32, kind);
                BigInteger memorySize = unsigned(p, kind == 1 ? 20 : 40, kind);
                BigInteger alignment = unsigned(p, kind == 1 ? 28 : 48, kind);
                Map<String, Object> segment = Inspection.object("index", i, "type", hex(BigInteger.valueOf(segmentType)),
                        "typeName", segmentType == PT_LOAD ? "PT_LOAD" : segmentType == PT_GNU_RELRO ? "PT_GNU_RELRO" : "OTHER",
                        "offset", hex(offset), "virtualAddress", hex(address), "fileSize", hex(fileSize),
                        "memorySize", hex(memorySize), "alignment", hex(alignment), "flags", flags,
                        "offsetPageRemainder", offset.mod(PAGE).intValue(), "addressPageRemainder", address.mod(PAGE).intValue());
                result.segments.add(segment);
                // PT_NULL denotes an unused table entry; the remaining fields are undefined.
                if (segmentType == 0) continue;
                try {
                    checkFileRange(checkedAdd(offset, fileSize), size, "Program header " + i);
                    BigInteger memoryEnd = checkedAdd(address, memorySize);
                    if (kind == 1 && memoryEnd.compareTo(BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)) > 0)
                        throw new Invalid("elf_address_overflow", "ELF32 segment virtual-address range overflows 32 bits");
                    if (segmentType == PT_LOAD) {
                        loads++;
                        if (fileSize.compareTo(memorySize) > 0)
                            throw new Invalid("elf_invalid_load_size", "PT_LOAD file size exceeds its memory size");
                        boolean powerOfTwo = alignment.signum() > 0 && alignment.and(alignment.subtract(BigInteger.ONE)).signum() == 0;
                        boolean aligned = powerOfTwo && alignment.compareTo(PAGE) >= 0;
                        boolean congruent = powerOfTwo && address.mod(alignment).equals(offset.mod(alignment));
                        if (!policyApplicable && alignment.compareTo(BigInteger.ONE) > 0 && (!powerOfTwo || !congruent))
                            throw new Invalid("elf_invalid_load_layout", "PT_LOAD alignment must be zero, one, or a power of two with congruent file offset and virtual address");
                        segment.put("loadAlignment", !policyApplicable ? "not_applicable" : aligned && congruent ? "pass" : "fail");
                        if (policyApplicable && !aligned) {
                            loadFailed = true;
                            diagnostic(result, "elf_load_alignment", entry, "PT_LOAD[" + i + "].p_align=" + hex(alignment)
                                    + "; required a power of two >= 0x4000. Rebuild or replace the library; ZIP realignment cannot repair ELF layout.", i);
                        }
                        if (policyApplicable && !congruent) {
                            loadFailed = true;
                            diagnostic(result, "elf_load_congruence", entry, "PT_LOAD[" + i + "] virtual address and file offset are not congruent modulo p_align", i);
                        }
                    }
                    if (segmentType == PT_GNU_RELRO) {
                        relros++;
                        boolean aligned = memoryEnd.mod(PAGE).signum() == 0;
                        segment.put("memoryEnd", hex(memoryEnd));
                        segment.put("relroAlignment", !policyApplicable ? "not_applicable" : aligned ? "pass" : "fail");
                        if (policyApplicable && !aligned) {
                            relroFailed = true;
                            diagnostic(result, "elf_relro_alignment", entry, "PT_GNU_RELRO[" + i + "] memory end " + hex(memoryEnd)
                                    + " is not aligned to 16384 bytes", i);
                        }
                    }
                } catch (Invalid e) {
                    diagnostic(result, e.code, entry, "Program header " + i + ": " + e.getMessage(), i);
                    throw new Invalid("elf_invalid_segment", "ELF segment validation did not complete");
                }
            }
            if (loads == 0) throw new Invalid("elf_no_load_segments", "ELF has no PT_LOAD segments");
            result.data.put("loadSegmentCount", loads);
            result.data.put("relroSegmentCount", relros);
            result.parsing = "pass";
            result.loadAlignment = !policyApplicable ? "not_applicable" : loadFailed ? "fail" : "pass";
            result.relroAlignment = !policyApplicable || relros == 0 ? "not_applicable" : relroFailed ? "fail" : "pass";
        } catch (Invalid e) {
            diagnostic(result, e.code, entry, e.getMessage(), null);
        } catch (EOFException e) {
            diagnostic(result, "elf_truncated", entry, "ELF header or program-header table is truncated", null);
        } catch (IOException | ArithmeticException e) {
            diagnostic(result, "elf_read_failed", entry, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null);
        } finally {
            result.data.put("bytesRead", reader.position);
        }
        return result;
    }

    private static void diagnostic(Result result, String code, String entry, String message, Integer index) {
        Map<String, Object> diagnostic = Inspection.diagnostic(code, entry, message);
        if (index != null) diagnostic.put("segmentIndex", index);
        result.diagnostics.add(diagnostic);
    }

    private static int u16(ByteBuffer data, int offset) { return Short.toUnsignedInt(data.getShort(offset)); }
    private static long u32(ByteBuffer data, int offset) { return Integer.toUnsignedLong(data.getInt(offset)); }
    private static BigInteger unsigned(ByteBuffer data, int offset, int elfKind) {
        if (elfKind == 1) return BigInteger.valueOf(u32(data, offset));
        long bits = data.getLong(offset);
        return bits >= 0 ? BigInteger.valueOf(bits) : BigInteger.valueOf(bits & Long.MAX_VALUE).setBit(63);
    }
    static String hex(BigInteger value) { return "0x" + value.toString(16); }
    private static BigInteger checkedAdd(BigInteger a, BigInteger b) throws Invalid {
        BigInteger result = a.add(b);
        if (result.compareTo(MAX_UNSIGNED_64) > 0)
            throw new Invalid("elf_address_overflow", "Unsigned 64-bit range overflow");
        return result;
    }
    private static void checkFileRange(BigInteger end, long size, String label) throws Invalid {
        if (size < 0) throw new Invalid("elf_unknown_file_size", "Uncompressed file size is unavailable");
        if (end.compareTo(BigInteger.valueOf(size)) > 0)
            throw new Invalid("elf_invalid_file_range", label + " extends beyond the uncompressed entry size");
    }
    private static String machineName(int value) {
        switch (value) {
            case 3: return "EM_386";
            case 40: return "EM_ARM";
            case 62: return "EM_X86_64";
            case 183: return "EM_AARCH64";
            case 243: return "EM_RISCV";
            default: return "UNKNOWN";
        }
    }
    private static final class Invalid extends IOException {
        final String code;
        Invalid(String code, String message) { super(message); this.code = code; }
    }
    private static final class Reader {
        final InputStream stream;
        final long budget;
        long position;
        Reader(InputStream stream, long budget) { this.stream = stream; this.budget = budget; }
        byte[] read(int count) throws IOException {
            if (position + count > budget)
                throw new Invalid("elf_read_budget_exceeded", "ELF stream read budget exhausted at " + position + " bytes");
            byte[] bytes = new byte[count];
            int done = 0;
            while (done < count) {
                int n = stream.read(bytes, done, count - done);
                if (n < 0) throw new EOFException();
                if (n == 0) {
                    int next = stream.read();
                    if (next < 0) throw new EOFException();
                    bytes[done++] = (byte) next;
                    position++;
                } else { done += n; position += n; }
            }
            return bytes;
        }
        void advanceTo(long target) throws IOException {
            if (target < position) throw new Invalid("elf_invalid_program_header_range", "Overlapping program-header read ranges");
            if (target > budget) throw new Invalid("elf_read_budget_exceeded", "Program headers exceed the ELF stream read budget");
            while (position < target) read((int) Math.min(8192, target - position));
        }
    }
}
