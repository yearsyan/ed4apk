package dev.ed4apk;

import picocli.CommandLine.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "info", mixinStandardHelpOptions = true, description = "Show APK metadata, size, ABIs and DEX entry names",
        footer = {"", "Example: ed4apk info app.apk --json",
                "Reads ZIP metadata and Manifest only. Does not parse DEX/ELF or verify signatures.",
                "Reports only this APK, including split identity when declared; not the entire installed application."})
final class ApkInfoCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
    @Option(names = "--json", description = "Emit a versioned JSON report; preserve ZIP details if Manifest fails") boolean json;

    @Override public Integer call() throws Exception {
        var diagnostics = new ArrayList<Map<String, Object>>();
        Map<String, Object> data = Inspection.object("size", null, "entryCount", null, "abis", List.of(),
                "dexCount", null, "dexEntries", List.of(), "manifest", null);
        try (var session = new ApkInspectionSession(apk)) {
            data.put("size", Files.size(apk));
            data.put("entryCount", session.entries().size());
            Set<String> abis = new TreeSet<>();
            for (var entry : session.entries()) {
                String[] parts = entry.getName().split("/", -1);
                if (parts.length == 3 && parts[0].equals("lib") && !parts[1].isEmpty()
                        && parts[2].endsWith(".so") && !entry.isDirectory()) abis.add(parts[1]);
            }
            data.put("abis", new ArrayList<>(abis));
            data.put("dexEntries", session.dexNames());
            data.put("dexCount", session.dexNames().size());
            try { data.put("manifest", ManifestInspector.overview(session)); }
            catch (Exception e) { diagnostics.add(Inspection.diagnostic("manifest_read_failed", "AndroidManifest.xml", Inspection.message(e))); }
            diagnostics.addAll(session.diagnostics());
        } catch (Exception e) { diagnostics.add(Inspection.diagnostic("archive_read_failed", null, Inspection.message(e))); }
        if (json) Main.printJson(Inspection.report(data, diagnostics));
        else {
            @SuppressWarnings("unchecked") Map<String, Object> manifest = (Map<String, Object>) data.get("manifest");
            if (manifest != null) {
                System.out.println("Package: " + Inspection.safeText(String.valueOf(manifest.get("packageName"))));
                System.out.println("Version: " + Inspection.safeText(manifest.get("versionName") + " (" + manifest.get("versionCode") + ")"));
                Map<?, ?> declarations = (Map<?, ?>) manifest.get("declarations");
                Map<?, ?> minimum = (Map<?, ?>) declarations.get("minSdk");
                Object minimumDisplay = Boolean.FALSE.equals(minimum.get("present")) ? "1 (default)"
                        : manifest.get("minSdk") == null ? "unknown (unresolved declaration)" : manifest.get("minSdk");
                System.out.println("Min SDK: " + Inspection.safeText(String.valueOf(minimumDisplay)));
                System.out.println("Target SDK: " + Inspection.safeText(String.valueOf(manifest.get("targetSdk"))));
                if (manifest.get("split") != null) System.out.println("Split: " + Inspection.safeText(String.valueOf(manifest.get("split"))));
            }
            System.out.println("APK size: " + data.get("size") + " bytes");
            System.out.println("Entries: " + data.get("entryCount"));
            System.out.println("ABIs: " + Inspection.safeText(String.valueOf(data.get("abis"))));
            for (Object dex : (List<?>) data.get("dexEntries")) System.out.println(dex);
            Inspection.printDiagnostics(diagnostics);
        }
        return diagnostics.isEmpty() ? 0 : 1;
    }
}
