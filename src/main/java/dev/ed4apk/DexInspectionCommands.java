package dev.ed4apk;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

final class DexInspectionCommands {
    private DexInspectionCommands() {}

    abstract static class Options implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Option(names = "--dex", paramLabel = "ENTRY", description = "Read one exact root DEX entry (e.g. classes2.dex)")
        String dex;
        @Option(names = "--json", description = "Print a schemaVersion/data/diagnostics JSON report") boolean json;
        @CommandLine.Spec CommandLine.Model.CommandSpec spec;

        Integer inspect(boolean list, String prefix) throws IOException {
            DexInspector.Result result;
            try (ApkInspectionSession session = new ApkInspectionSession(apk)) {
                List<String> names = session.dexNames();
                if (dex != null) {
                    if (!names.contains(dex)) throw new CommandLine.ParameterException(spec.commandLine(),
                            "DEX entry not found: " + Inspection.safeText(dex));
                    names = List.of(dex);
                }
                result = DexInspector.inspect(session, names, list, prefix);
            } catch (CommandLine.ParameterException error) {
                throw error;
            } catch (IOException | RuntimeException error) {
                result = new DexInspector.Result();
                result.problem("APK_READ_FAILED", null,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
            if (json) Main.printJson(Inspection.report(result.data(list), result.diagnostics));
            else {
                if (list) {
                    for (Map<String, Object> cls : result.classes)
                        System.out.println(Inspection.safeText((String) cls.get("entry")) + " | "
                                + Inspection.safeText((String) cls.get("descriptor")));
                } else {
                    System.out.printf("%-22s %-7s %-12s %-12s %-12s %s%n",
                            "DEX", "VERSION", "CLASS_DEFS", "METHOD_IDS", "FIELD_IDS", "STATUS");
                    for (Map<String, Object> entry : result.entries)
                        System.out.printf("%-22s %-7s %-12s %-12s %-12s %s%n",
                                Inspection.safeText((String) entry.get("entry")), display(entry.get("version")),
                                display(entry.get("classDefCount")), display(entry.get("methodIdCount")),
                                display(entry.get("fieldIdCount")), entry.get("status"));
                    if (result.entries.isEmpty()) System.out.println("No root DEX entries found.");
                }
                for (Map<String, Object> diagnostic : result.diagnostics)
                    System.err.println(Inspection.safeText(String.valueOf(diagnostic.get("code"))) + ": "
                            + (diagnostic.get("entry") == null ? "" : Inspection.safeText(
                                    String.valueOf(diagnostic.get("entry"))) + ": ")
                            + Inspection.safeText(String.valueOf(diagnostic.get("message"))));
            }
            return result.complete ? 0 : 1;
        }
    }

    @Command(name = "info", mixinStandardHelpOptions = true,
            description = "Show DEX versions, class definitions and method/field ID table counts",
            footer = {"", "Reads headers and checks section ranges; does not decompile code or verify checksums.",
                    "Method/field ID counts include references, not just methods/fields defined in this DEX.",
                    "DEX 041+ containers retain their version but have unknown counts and exit 1.",
                    "Example: ed4apk dex info app.apk --dex classes2.dex --json"})
    static class Info extends Options {
        @Override public Integer call() throws IOException { return inspect(false, null); }
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List class descriptors and owning DEX; optionally filter by DEX or name prefix",
            footer = {"", "Example: ed4apk dex list app.apk --dex classes2.dex --prefix com.example. --json",
                    "--prefix matches a literal descriptor prefix; Java dots become slashes with an initial L.",
                    "Use com.example. (or Lcom/example/) for a package boundary; descriptors may end with ';'.",
                    "Unfiltered text keeps the existing ENTRY | DESCRIPTOR format.",
                    "Reads at most 256 MiB per DEX and scans at most 1,000,000 classes per command.",
                    "Descriptor text is limited to 65,536 characters per class and 32 Mi characters per command.",
                    "A failed DEX is diagnosed with exit 1; readable DEX entries remain in the report."})
    static class ListCommand extends Options {
        @Option(names = "--prefix", paramLabel = "PREFIX", description = "Java name/package or DEX descriptor prefix")
        String prefix;

        @Override public Integer call() throws IOException {
            String normalized = prefix;
            if (prefix != null && !prefix.isEmpty()) {
                if (prefix.codePoints().anyMatch(Character::isISOControl))
                    throw new CommandLine.ParameterException(spec.commandLine(), "--prefix cannot contain control characters");
                if (!prefix.startsWith("L") || prefix.indexOf('.') >= 0)
                    normalized = "L" + prefix.replace('.', '/');
            }
            return inspect(true, normalized);
        }
    }

    private static String display(Object value) { return value == null ? "unknown" : String.valueOf(value); }
}
