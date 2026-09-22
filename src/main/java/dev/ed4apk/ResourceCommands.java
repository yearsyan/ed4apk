package dev.ed4apk;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

final class ResourceCommands {
    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List resource IDs, names and all configuration values",
            footer = {"No resources.arsc: successful empty inventory with tablePresent=false.",
                    "Configurations use the same qualifiers as resource set-string --config (empty means default)."})
    static class ListCommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK") Path apk;
        @Option(names = "--type", description = "Exact resource type, such as string, drawable or style") String type;
        @Option(names = "--json", description = "Print a versioned JSON report") boolean json;
        @Spec CommandLine.Model.CommandSpec spec;
        public Integer call() throws Exception {
            if (type != null && !type.matches("[A-Za-z0-9_]+"))
                throw new CommandLine.ParameterException(spec.commandLine(), "--type must be a resource type name");
            return run(apk, type, null, json);
        }
    }

    @Command(name = "show", mixinStandardHelpOptions = true,
            description = "Show every configuration of one resource, preserving binary value types",
            footer = {"ID accepts hexadecimal/decimal, @type/name, or @package:type/name.",
                    "References remain unresolved; complex bags retain parent IDs, item IDs and typed values.",
                    "A missing table/resource or ambiguous name returns exit code 1."})
    static class Show implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK") Path apk;
        @Parameters(index = "1", paramLabel = "ID") String id;
        @Option(names = "--json", description = "Print a versioned JSON report") boolean json;
        @Spec CommandLine.Model.CommandSpec spec;
        public Integer call() throws Exception {
            try { ResourceInspector.validateSelector(id); }
            catch (IllegalArgumentException e) { throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage()); }
            return run(apk, null, id, json);
        }
    }

    private static int run(Path apk, String type, String id, boolean json) throws Exception {
        Map<String, Object> data = null;
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        try (var session = new ApkInspectionSession(apk)) {
            diagnostics.addAll(session.diagnostics());
            data = id == null ? ResourceInspector.list(session, type) : ResourceInspector.show(session, id);
        } catch (Exception e) {
            diagnostics.add(Inspection.diagnostic("resource_read_failed", "resources.arsc", Inspection.message(e)));
        }
        if (json) Main.printJson(Inspection.report(data, diagnostics));
        else {
            if (data != null) {
                if (id != null) printResource(data);
                else if (Boolean.FALSE.equals(data.get("tablePresent"))) System.out.println("No resources.arsc table in this APK.");
                else {
                    for (Object resource : (List<?>) data.get("resources")) {
                        @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) resource;
                        printResource(item);
                    }
                    System.out.println("Resources: " + data.get("resourceCount"));
                }
            }
            Inspection.printDiagnostics(diagnostics);
        }
        return diagnostics.isEmpty() ? 0 : 1;
    }

    private static void printResource(Map<String, Object> resource) {
        System.out.println(Inspection.safeText(resource.get("id") + " " + resource.get("qualifiedName")));
        for (Object item : (List<?>) resource.get("configurations")) {
            Map<?, ?> config = (Map<?, ?>) item;
            String qualifier = (String) config.get("config");
            System.out.println("  " + Inspection.safeText(qualifier.isEmpty() ? "(default)" : qualifier)
                    + " " + describeValue((Map<?, ?>) config.get("value")));
        }
    }

    private static String describeValue(Map<?, ?> value) {
        if ("BAG".equals(value.get("type"))) return Inspection.safeText(value.toString());
        Object display = value.containsKey("value") ? value.get("value")
                : value.containsKey("reference") ? value.get("reference")
                : value.containsKey("decoded") ? value.get("decoded") : value.get("data");
        return value.get("type") + " " + Inspection.safeText(String.valueOf(display));
    }
}
