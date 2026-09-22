package dev.ed4apk;

import com.reandroid.arsc.value.ValueType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/** Combines ZIP facts, Manifest loading declarations and independent ELF checks. */
final class NativeInspector {
    static final int PAGE_SIZE = 16384;
    private static final Pattern LIBRARY = Pattern.compile("^lib/([^/]+)/([^/]+\\.so)$");
    private static final List<String> TARGET_ABIS = List.of("arm64-v8a", "x86_64");
    private static final List<String> ABIS_32 = List.of("armeabi", "armeabi-v7a", "x86", "mips");

    static final class Report {
        final Map<String, Object> data;
        final List<Map<String, Object>> diagnostics;
        final String status;
        final boolean incomplete;
        Report(Map<String, Object> data, List<Map<String, Object>> diagnostics, String status, boolean incomplete) {
            this.data = data; this.diagnostics = diagnostics; this.status = status; this.incomplete = incomplete;
        }
        Object json() { return Inspection.report(data, diagnostics); }
    }

    static String abi(String path) {
        Matcher matcher = LIBRARY.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }

    static Report list(ApkInspectionSession session, String selectedAbi) throws IOException {
        return inspect(session, selectedAbi, null, false);
    }

    static Report check(ApkInspectionSession session, String selectedAbi) throws IOException {
        return inspect(session, selectedAbi, null, true);
    }

    static Report show(ApkInspectionSession session, String path) throws IOException {
        if (abi(path) == null) throw new IllegalArgumentException("Expected a standard library path: lib/<abi>/<name>.so");
        session.entry(path); // Reject nonexistent or ambiguous paths before emitting a single-entry report.
        return inspect(session, null, path, true);
    }

    private static Report inspect(ApkInspectionSession session, String selectedAbi, String selectedPath, boolean full) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        int discovered = 0;
        for (ZipEntry entry : session.entries()) {
            String declaredAbi = abi(entry.getName());
            if (entry.isDirectory() || declaredAbi == null) continue;
            discovered++;
            if (selectedAbi != null && !selectedAbi.equals(declaredAbi)) continue;
            if (selectedPath != null && !selectedPath.equals(entry.getName())) continue;
            entries.add(entry);
        }
        if (selectedAbi != null && entries.isEmpty())
            throw new IllegalArgumentException("No standard native libraries found for --abi " + selectedAbi);
        entries.sort(Comparator.comparing(ZipEntry::getName));
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        List<Map<String, Object>> libraries = new ArrayList<>();
        Loading loading = full && !entries.isEmpty() ? loading(session, diagnostics) : new Loading();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String state : List.of("pass", "fail", "unknown", "not_applicable")) counts.put(state, 0);
        int targetCount = 0;
        boolean incomplete = false;
        String status = "not_applicable";
        for (ZipEntry entry : entries) {
            String path = entry.getName();
            String declaredAbi = abi(path);
            boolean target = TARGET_ABIS.contains(declaredAbi);
            boolean abi32 = ABIS_32.contains(declaredAbi);
            if (target) targetCount++;
            Long offset = session.dataOffset(path);
            String zipStatus = entry.getMethod() == ZipEntry.DEFLATED ? "not_applicable"
                    : !target ? (abi32 ? "not_applicable" : "unknown")
                    : entry.getMethod() != ZipEntry.STORED || offset == null ? "unknown"
                    : offset % PAGE_SIZE == 0 ? "pass" : "fail";
            Map<String, Object> library = Inspection.object("path", path, "abi", declaredAbi, "size", entry.getSize(),
                    "compressedSize", entry.getCompressedSize(), "method", method(entry), "dataOffset", offset,
                    "policyApplicable", target, "zipAlignment", zipStatus);
            if (target && "fail".equals(zipStatus))
                diagnostics.add(Inspection.diagnostic("native_zip_alignment", path,
                        "Uncompressed library payload starts at " + offset + "; required a multiple of 16384. Repack/align the APK and sign it again."));
            if (target && "unknown".equals(zipStatus)) {
                incomplete = true;
                diagnostics.add(Inspection.diagnostic("native_zip_alignment_unknown", path,
                        "Cannot confirm the uncompressed library payload offset or storage method"));
            }
            if (!target && !abi32)
                diagnostics.add(Inspection.diagnostic("native_abi_not_covered", path,
                        "ABI " + declaredAbi + " is outside the supported arm64-v8a/x86_64 16 KiB policy"));
            String libraryStatus = zipStatus;
            if (!target && !abi32) libraryStatus = "unknown";
            if (full) {
                ElfInspector.Result elf;
                try (InputStream input = session.open(path)) {
                    elf = ElfInspector.inspect(input, entry.getSize(), path, target);
                } catch (IOException e) {
                    elf = new ElfInspector.Result();
                    elf.diagnostics.add(Inspection.diagnostic("native_read_failed", path, Inspection.message(e)));
                }
                diagnostics.addAll(elf.diagnostics);
                if (!"pass".equals(elf.parsing)) incomplete = true;
                String abiStatus = checkAbi(declaredAbi, elf, path, diagnostics);
                String packaging = packaging(entry, loading, diagnostics);
                library.put("abiMatch", abiStatus);
                library.put("elf", elf.report());
                library.put("loadAlignment", target ? elf.loadAlignment : abi32 ? "not_applicable" : "unknown");
                library.put("relroAlignment", target ? elf.relroAlignment : abi32 ? "not_applicable" : "unknown");
                library.put("packaging", Inspection.object("status", packaging, "extractNativeLibs", loading.declared,
                        "contextStatus", loading.status));
                libraryStatus = target ? combine(zipStatus, abiStatus, elf.parsing, elf.loadAlignment, elf.relroAlignment, packaging)
                        : abi32 ? combine("pass".equals(elf.parsing) ? "not_applicable" : "unknown",
                                "fail".equals(abiStatus) ? "fail" : "not_applicable") : "unknown";
            }
            library.put("status", libraryStatus);
            counts.put(libraryStatus, counts.get(libraryStatus) + 1);
            status = combine(status, libraryStatus);
            libraries.add(library);
        }
        // Offset validation is lazy and may add diagnostics while visiting entries.
        List<Map<String, Object>> archiveDiagnostics = session.diagnostics();
        diagnostics.addAll(0, archiveDiagnostics);
        if (!archiveDiagnostics.isEmpty()) {
            incomplete = true;
            if (full) status = combine(status, "unknown");
        }
        if (full && loading.unreadable) {
            status = combine(status, "unknown");
            incomplete = true;
        }
        Map<String, Object> scope = Inspection.object("paths", "lib/<abi>/*.so", "targetAbis", TARGET_ABIS,
                "selectedAbi", selectedAbi, "selectedPath", selectedPath, "pageSize", PAGE_SIZE,
                "policy", "Static ZIP, PT_LOAD and PT_GNU_RELRO alignment for this APK only",
                "excluded", List.of("assets/custom native loading", "nested archives", "runtime downloads", "other APK splits"),
                "discoveredCount", discovered, "scannedCount", entries.size(), "targetCount", targetCount,
                "elfInspected", full);
        Map<String, Object> summary = Inspection.object("status", status, "counts", counts,
                "message", entries.isEmpty() ? "No standard native libraries found in this APK" : null);
        Map<String, Object> data = Inspection.object("scope", scope, "libraries", libraries, "summary", summary);
        if (full && !entries.isEmpty())
            data.put("loadingContext", Inspection.object("status", loading.status, "extractNativeLibs", loading.declared,
                    "split", loading.split));
        return new Report(data, diagnostics, status, incomplete);
    }

    private static String method(ZipEntry entry) {
        return entry.getMethod() == ZipEntry.STORED ? "stored" : entry.getMethod() == ZipEntry.DEFLATED ? "deflated" : "unknown";
    }

    private static String checkAbi(String abi, ElfInspector.Result elf, String path, List<Map<String, Object>> diagnostics) {
        int expectedClass;
        int machine;
        switch (abi) {
            case "arm64-v8a": expectedClass = 64; machine = 183; break;
            case "x86_64": expectedClass = 64; machine = 62; break;
            case "armeabi": case "armeabi-v7a": expectedClass = 32; machine = 40; break;
            case "x86": expectedClass = 32; machine = 3; break;
            case "mips": expectedClass = 32; machine = 8; break;
            default: return "unknown";
        }
        if (elf.elfClass == null || elf.machine == null) return "unknown";
        if (elf.elfClass != expectedClass || elf.machine != machine || !elf.littleEndian) {
            diagnostics.add(Inspection.diagnostic("native_abi_mismatch", path, "Path declares " + abi
                    + " but ELF reports class=" + elf.elfClass + ", machine=" + elf.machine
                    + ", byteOrder=" + (elf.littleEndian ? "little_endian" : "big_endian")));
            return "fail";
        }
        return "pass";
    }

    private static String packaging(ZipEntry entry, Loading loading, List<Map<String, Object>> diagnostics) {
        if (entry.getMethod() == ZipEntry.STORED) return "pass";
        if (entry.getMethod() != ZipEntry.DEFLATED) return "unknown";
        if (Boolean.TRUE.equals(loading.declared)) return "pass";
        if (Boolean.FALSE.equals(loading.declared)) {
            diagnostics.add(Inspection.diagnostic("native_packaging_conflict", entry.getName(),
                    "Compressed native library conflicts with android:extractNativeLibs=false"));
            return "fail";
        }
        diagnostics.add(Inspection.diagnostic("native_loading_mode_unknown", entry.getName(),
                "Compressed library loading cannot be confirmed without an explicit extractNativeLibs declaration and loading context"));
        return "unknown";
    }

    private static final class Loading {
        Boolean declared;
        String split;
        String status = "unknown";
        boolean unreadable;
    }

    private static Loading loading(ApkInspectionSession session, List<Map<String, Object>> diagnostics) {
        Loading loading = new Loading();
        try {
            var manifest = ManifestInspector.read(session);
            var root = manifest.getDocumentElement();
            if (root == null || !"manifest".equals(root.getName())) throw new IOException("Manifest root is missing");
            var split = root.searchAttribute(null, "split");
            if (split != null) loading.split = split.getValueAsString();
            var app = manifest.getApplicationElement();
            if (app == null) {
                diagnostics.add(Inspection.diagnostic(loading.split == null ? "native_application_loading_context_missing" : "native_split_loading_context_missing",
                        "AndroidManifest.xml", "This Manifest has no application loading declaration; loading context is unavailable"));
                return loading;
            }
            var attribute = app.searchAttribute(ManifestEditor.ANDROID, "extractNativeLibs");
            if (attribute != null) {
                if (attribute.getValueType() == ValueType.BOOLEAN) {
                    loading.declared = attribute.getValueAsBoolean();
                    loading.status = "pass";
                } else diagnostics.add(Inspection.diagnostic("native_extract_mode_unresolved", "AndroidManifest.xml",
                        "extractNativeLibs is not a literal boolean; its loading value is unknown"));
            } else {
                diagnostics.add(Inspection.diagnostic(loading.split == null ? "native_extract_mode_undeclared" : "native_split_loading_context_missing",
                        "AndroidManifest.xml", loading.split == null
                                ? "extractNativeLibs is not declared; no build-system default is inferred"
                                : "This split does not declare extractNativeLibs; loading context may be in the base APK"));
            }
        } catch (IOException | RuntimeException e) {
            loading.unreadable = true;
            diagnostics.add(Inspection.diagnostic("native_manifest_unavailable", "AndroidManifest.xml", Inspection.message(e)));
        }
        return loading;
    }

    static String combine(String... values) {
        boolean applicable = false;
        boolean unknown = false;
        for (String value : values) {
            if ("fail".equals(value)) return "fail";
            if ("unknown".equals(value)) unknown = true;
            if ("pass".equals(value)) applicable = true;
        }
        return unknown ? "unknown" : applicable ? "pass" : "not_applicable";
    }
}
