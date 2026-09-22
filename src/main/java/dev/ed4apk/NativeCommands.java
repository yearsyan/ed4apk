package dev.ed4apk;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "native", mixinStandardHelpOptions = true,
        description = "Browse standard native libraries and check static 16 KiB alignment",
        subcommands = {NativeCommands.ListLibraries.class, NativeCommands.Check.class, NativeCommands.Show.class},
        footer = {"Only lib/<abi>/*.so in this APK is scanned; assets, downloads and other splits are outside scope.",
                "The 16 KiB policy covers arm64-v8a and x86_64. Runtime compatibility still needs device testing."})
final class NativeCommands {
    static class Options {
        @Parameters(index = "0", paramLabel = "APK", description = "APK to inspect without modifying it") Path apk;
        @Option(names = "--json", description = "Emit a schemaVersion/data/diagnostics JSON report") boolean json;
        @Spec CommandLine.Model.CommandSpec spec;

        int failure(String entry, Exception error) throws IOException {
            if (error instanceof CommandLine.ParameterException) throw (CommandLine.ParameterException) error;
            var diagnostics = List.of(Inspection.diagnostic("native_read_failed", entry, Inspection.message(error)));
            if (json) Main.printJson(Inspection.report(null, diagnostics));
            else for (var diagnostic : diagnostics)
                spec.commandLine().getErr().println(Inspection.safeText(diagnostic.get("code") + ": " + diagnostic.get("entry") + ": " + diagnostic.get("message")));
            return 1;
        }

        void render(NativeInspector.Report report, boolean detailed) throws Exception {
            if (json) { Main.printJson(report.json()); return; }
            var out = spec.commandLine().getOut();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> libraries = (List<Map<String, Object>>) report.data.get("libraries");
            @SuppressWarnings("unchecked")
            Map<String, Object> scope = (Map<String, Object>) report.data.get("scope");
            out.println("Scope: this APK's lib/<abi>/*.so; 16 KiB target ABIs: arm64-v8a, x86_64");
            out.println("Scanned: " + scope.get("scannedCount") + "; target libraries: " + scope.get("targetCount"));
            if (libraries.isEmpty()) out.println("No standard native libraries found in this APK.");
            else if (Boolean.TRUE.equals(scope.get("elfInspected"))) {
                out.printf("%-48s %-8s %-8s %-8s %-10s %-8s%n", "PATH", "ZIP16K", "LOAD16K", "RELRO16K", "PACKAGING", "RESULT");
                for (Map<String, Object> library : libraries) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> packaging = (Map<String, Object>) library.get("packaging");
                    out.printf("%-48s %-8s %-8s %-8s %-10s %-8s%n", Inspection.safeText((String) library.get("path")),
                            state(library.get("zipAlignment")), state(library.get("loadAlignment")), state(library.get("relroAlignment")),
                            state(packaging.get("status")), state(library.get("status")));
                    if (detailed) renderElf(library, out);
                }
                out.println("Result: " + state(report.status) + " (static checks; runtime behavior is outside scope)");
            } else {
                out.printf("%-48s %-14s %12s %12s %-9s %-8s%n", "PATH", "ABI", "SIZE", "COMPRESSED", "METHOD", "ZIP16K");
                for (Map<String, Object> library : libraries)
                    out.printf("%-48s %-14s %12s %12s %-9s %-8s%n", Inspection.safeText((String) library.get("path")),
                            Inspection.safeText((String) library.get("abi")), library.get("size"), library.get("compressedSize"),
                            library.get("method"), state(library.get("zipAlignment")));
                out.println("ELF headers were not inspected. Use native check for a complete static report.");
            }
            for (Map<String, Object> diagnostic : report.diagnostics)
                spec.commandLine().getErr().println(Inspection.safeText(String.valueOf(diagnostic.get("entry"))) + ": ["
                        + diagnostic.get("code") + "] " + Inspection.safeText(String.valueOf(diagnostic.get("message"))));
        }

        @SuppressWarnings("unchecked")
        private void renderElf(Map<String, Object> library, java.io.PrintWriter out) {
            Map<String, Object> elf = (Map<String, Object>) library.get("elf");
            out.println("  ELF: " + elf.get("class") + "-bit " + elf.get("byteOrder") + " " + elf.get("machineName")
                    + "; ABI match=" + state(library.get("abiMatch")) + "; read " + elf.get("bytesRead") + " bytes");
            for (Map<String, Object> segment : (List<Map<String, Object>>) elf.get("segments"))
                out.println("  [" + segment.get("index") + "] " + segment.get("typeName")
                        + " offset=" + segment.get("offset") + " vaddr=" + segment.get("virtualAddress")
                        + " filesz=" + segment.get("fileSize") + " memsz=" + segment.get("memorySize")
                        + " align=" + segment.get("alignment") + " flags=" + segment.get("flags"));
        }
    }

    static class Filtered extends Options {
        @Option(names = "--abi", description = "Exact ABI directory to inspect; a missing ABI is an error") String abi;
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List standard SO paths, sizes, storage methods and ZIP alignment without reading ELF content")
    static class ListLibraries extends Filtered implements Callable<Integer> {
        public Integer call() throws Exception {
            NativeInspector.Report report;
            try (var session = new ApkInspectionSession(apk)) {
                try { report = NativeInspector.list(session, abi); }
                catch (IllegalArgumentException e) { throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage()); }
            } catch (IOException | RuntimeException e) { return failure(null, e); }
            render(report, false);
            return report.incomplete ? 1 : 0;
        }
    }

    @Command(name = "check", mixinStandardHelpOptions = true,
            description = "Check ZIP, ELF LOAD/RELRO alignment and declared native loading mode",
            footer = {"Exit 0: all target libraries pass or no applicable entries. Exit 1: fail/unknown. Exit 2: invalid options.",
                    "Compressed libraries have no ZIP alignment requirement; their ELF and packaging are still checked.",
                    "ZIP realignment cannot fix ELF layout. Rebuild or replace failing libraries."})
    static class Check extends Filtered implements Callable<Integer> {
        @Option(names = "--page-size", defaultValue = "16384", description = "Supported static page-size policy (only 16384)") int pageSize;
        public Integer call() throws Exception {
            if (pageSize != NativeInspector.PAGE_SIZE)
                throw new CommandLine.ParameterException(spec.commandLine(), "--page-size currently supports only 16384");
            NativeInspector.Report report;
            try (var session = new ApkInspectionSession(apk)) {
                try { report = NativeInspector.check(session, abi); }
                catch (IllegalArgumentException e) { throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage()); }
            } catch (IOException | RuntimeException e) { return failure(null, e); }
            render(report, false);
            return report.incomplete || "fail".equals(report.status) || "unknown".equals(report.status) ? 1 : 0;
        }
    }

    @Command(name = "show", mixinStandardHelpOptions = true,
            description = "Show one standard SO's ELF headers, segments and static alignment results",
            footer = {"Unsigned ELF values are hexadecimal strings in JSON. Header reads are limited to 8 MiB.",
                    "Exit 1 indicates incomplete inspection; use native check to gate on alignment failures."})
    static class Show extends Options implements Callable<Integer> {
        @Parameters(index = "1", paramLabel = "ENTRY", description = "Exact path lib/<abi>/<name>.so") String entry;
        public Integer call() throws Exception {
            NativeInspector.Report report;
            try (var session = new ApkInspectionSession(apk)) {
                try { report = NativeInspector.show(session, entry); }
                catch (IllegalArgumentException e) { throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage()); }
            } catch (IOException | RuntimeException e) { return failure(entry, e); }
            render(report, true);
            return report.incomplete ? 1 : 0;
        }
    }

    private static String state(Object value) {
        return "not_applicable".equals(value) ? "N/A" : String.valueOf(value).toUpperCase(java.util.Locale.ROOT);
    }
}
