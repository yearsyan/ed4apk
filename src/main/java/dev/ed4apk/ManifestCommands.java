package dev.ed4apk;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;

/** CLI adapters share the same transaction and validation path as JSON patches. */
final class ManifestCommands {
    static class Target {
        @Option(names = "--path", description = "Absolute XPath selecting exactly one element; android prefix is predefined")
        String path;
        @Option(names = "--component", description = "activity, activity-alias, service, receiver or provider; requires --name")
        String component;
        @Option(names = "--name", description = "Component name; .Main, Main and package.Main are equivalent")
        String name;
        void put(ObjectNode op) {
            if (path != null) op.put("path", path);
            if (component != null) op.put("component", component);
            if (name != null) op.put("name", name);
        }
    }

    static class Edit {
        @Parameters(index = "0", paramLabel = "APK") Path apk;
        @Mixin Main.Output output;
        int apply(ObjectNode op) throws Exception {
            BatchEditor.apply(apk, PatchPlan.manifest(op), output.path, output.force, null);
            output.unsigned();
            return 0;
        }
    }

    @Command(name = "show", mixinStandardHelpOptions = true,
            description = "Print Manifest elements as typed JSON (default) or readable XML",
            footer = {"", "ed4apk manifest show app.apk",
                    "ed4apk manifest show app.apk --path /manifest/application/activity",
                    "ed4apk manifest show app.apk --component activity --name .Main",
                    "Outputs an array. One element object can be saved as --node for add/replace.",
                    "No selector means the whole Manifest. No matches yields [] in JSON, empty output in XML.",
                    "XML selectors may output multiple standalone fragments, each with inherited namespaces.",
                    "XML is a decoded view; source formatting and resource symbols are not recovered.",
                    "ed4apk manifest show app.apk --format xml"})
    static class Show implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK") Path apk;
        @Mixin Target target;
        enum Format { json, xml }
        @Option(names = "--format", defaultValue = "json", description = "Output format: ${COMPLETION-CANDIDATES}")
        Format format;
        public Integer call() throws Exception {
            try (var session = new ApkInspectionSession(apk)) {
                var editor = ManifestEditor.inspect(session);
                if (format == Format.xml) System.out.print(editor.showXml(target.path, target.component, target.name));
                else Main.printJson(editor.show(target.path, target.component, target.name));
            }
            return 0;
        }
    }

    @Command(name = "summary", mixinStandardHelpOptions = true,
            description = "Show declared permissions, components, launch candidates and application flags",
            footer = {"Missing declarations are null; resource-dependent values retain their binary type and data.",
                    "Launch candidates include activity-alias and require MAIN and launcher category in the same filter.",
                    "Candidates are declarations, not a guarantee of launchability on a device."})
    static class Summary implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK") Path apk;
        @Option(names = "--json", description = "Print a versioned JSON report") boolean json;
        public Integer call() throws Exception {
            Map<String, Object> data = null;
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            try (var session = new ApkInspectionSession(apk)) {
                diagnostics.addAll(session.diagnostics());
                data = ManifestInspector.summary(session);
            } catch (Exception e) {
                diagnostics.add(Inspection.diagnostic("manifest_read_failed", "AndroidManifest.xml", Inspection.message(e)));
            }
            if (json) Main.printJson(Inspection.report(data, diagnostics));
            else {
                if (data != null) printSummary(data);
                Inspection.printDiagnostics(diagnostics);
            }
            return diagnostics.isEmpty() ? 0 : 1;
        }

        private static void printSummary(Map<String, Object> data) {
            System.out.println("Package: " + Inspection.safeText((String) data.get("packageName")));
            System.out.println("Debuggable: " + declared(data.get("debuggable")));
            System.out.println("Extract native libs: " + declared(data.get("extractNativeLibs")));
            System.out.println("Application enabled: " + declared(data.get("applicationEnabled")));
            System.out.println("Permissions:");
            for (Object item : (List<?>) data.get("permissions")) {
                Map<?, ?> permission = (Map<?, ?>) item;
                System.out.println("  " + declared(permission.get("name")) + " (" + permission.get("tag")
                        + (Boolean.TRUE.equals(((Map<?, ?>) permission.get("maxSdkVersion")).get("present"))
                        ? ", maxSdk=" + declared(permission.get("maxSdkVersion")) : "") + ")");
            }
            System.out.println("Components:");
            for (Object item : (List<?>) data.get("components")) {
                Map<?, ?> component = (Map<?, ?>) item;
                System.out.println("  " + component.get("kind") + " " + componentName(component)
                        + " exported=" + declared(component.get("exported")) + " enabled=" + declared(component.get("enabled"))
                        + ("activity-alias".equals(component.get("kind")) ? " target=" + declared(component.get("targetActivity")) : ""));
            }
            System.out.println("Launch candidates:");
            for (Object item : (List<?>) data.get("launchCandidates")) {
                Map<?, ?> candidate = (Map<?, ?>) item;
                System.out.println("  " + candidate.get("kind") + " " + componentName(candidate)
                        + ("activity-alias".equals(candidate.get("kind")) ? " -> " + declared(candidate.get("targetActivity")) : ""));
            }
            System.out.println(Inspection.safeText((String) data.get("launchCandidateScope")));
        }

        private static String componentName(Map<?, ?> component) {
            Object full = component.get("qualifiedName");
            return full == null ? declared(component.get("name")) : Inspection.safeText(full.toString());
        }

        private static String declared(Object value) {
            Map<?, ?> declaration = (Map<?, ?>) value;
            if (!Boolean.TRUE.equals(declaration.get("present"))) return "undeclared";
            Object literal = declaration.get("declared");
            return literal == null ? "unresolved " + declaration.get("type") + " " + declaration.get("data")
                    : Inspection.safeText(literal.toString());
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Append a Manifest node/subtree, including activity/service/receiver/provider",
            footer = {"", "ed4apk manifest add app.apk --node activity.json -o edited.apk",
                    "ed4apk manifest add app.apk --parent /manifest --node permission.json -o edited.apk",
                    "node JSON: {\"tag\":\"activity\",\"attributes\":{\"android:name\":\".Extra\",\"android:exported\":false}}",
                    "Optional children is an array of nodes (intent-filter, meta-data, action, category, data, etc.).",
                    "Component declarations do not add DEX code. Combine with dex.add using apply when needed.",
                    "See examples manifest and --help-all for typed attributes and nested patches."})
    static class Add implements Callable<Integer> {
        @Mixin Edit edit;
        @Option(names = "--parent", defaultValue = "/manifest/application", description = "XPath selecting the parent element")
        String parent;
        @Option(names = "--node", required = true, description = "UTF-8 JSON file containing one node object") Path node;
        public Integer call() throws Exception {
            var op = JsonNodeFactory.instance.objectNode().put("op", "manifest.add").put("parent", parent);
            op.set("node", PatchPlan.readJson(node));
            return edit.apply(op);
        }
    }

    @Command(name = "update", mixinStandardHelpOptions = true,
            description = "Set/remove selected attributes, preserving the element's other attributes and children",
            footer = {"", "ed4apk manifest update app.apk --component activity --name .Main --attribute android:exported=false -o edited.apk",
                    "ed4apk manifest update app.apk --path /manifest/application --attribute android:debuggable=true -o edited.apk",
                    "Repeat --attribute and --remove-attribute. Values use Android XML syntax (enums, flags, references).",
                    "--attributes accepts a JSON attribute object; typed values can force a literal STRING.",
                    "Choose --path OR --component with --name. Missing/ambiguous targets and missing removed attributes fail.",
                    "Changing android:name changes the declaration only; use dex rename for a class rename."})
    static class Update implements Callable<Integer> {
        @Mixin Edit edit;
        @Mixin Target target;
        @Option(names = "--attributes", description = "JSON object of attribute names to values") Path attributes;
        @Option(names = "--attribute", paramLabel = "NAME=VALUE", description = "Set one attribute; repeatable")
        List<String> set = new ArrayList<>();
        @Option(names = "--remove-attribute", paramLabel = "NAME", description = "Remove an existing attribute; repeatable")
        List<String> remove = new ArrayList<>();
        public Integer call() throws Exception {
            var op = JsonNodeFactory.instance.objectNode().put("op", "manifest.update");
            target.put(op);
            ObjectNode values = JsonNodeFactory.instance.objectNode();
            if (attributes != null) {
                var input = PatchPlan.readJson(attributes);
                if (input == null || !input.isObject()) throw new IOException("--attributes requires a JSON object");
                values.setAll((ObjectNode) input);
            }
            for (String item : set) {
                int split = item.indexOf('=');
                if (split < 1) throw new IOException("Expected NAME=VALUE: " + item);
                String name = item.substring(0, split);
                if (values.has(name)) throw new IOException("Repeated attribute: " + name);
                values.put(name, item.substring(split + 1));
            }
            op.set("attributes", values);
            var removed = op.putArray("removeAttributes");
            remove.forEach(removed::add);
            return edit.apply(op);
        }
    }

    @Command(name = "replace", mixinStandardHelpOptions = true,
            description = "Replace one Manifest element and all its children, keeping its sibling position",
            footer = {"", "ed4apk manifest replace app.apk --component activity --name .Main --node activity.json -o edited.apk",
                    "Choose --path OR --component with --name. --node uses the same format as manifest add.",
                    "Replacement is a complete subtree; omitted attributes/children are removed. DEX code is unchanged."})
    static class Replace implements Callable<Integer> {
        @Mixin Edit edit;
        @Mixin Target target;
        @Option(names = "--node", required = true, description = "JSON file containing the replacement node") Path node;
        public Integer call() throws Exception {
            var op = JsonNodeFactory.instance.objectNode().put("op", "manifest.replace");
            target.put(op);
            op.set("node", PatchPlan.readJson(node));
            return edit.apply(op);
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true,
            description = "Delete one Manifest element/subtree; leaves DEX classes intact",
            footer = {"", "ed4apk manifest delete app.apk --component service --name .LegacyService -o edited.apk",
                    "Choose --path OR --component with --name. Exactly one element must match.",
                    "Use apply to combine manifest.delete and dex.delete; class references are checked on the final state.",
                    "The manifest root cannot be deleted. No automatic removal of activity-alias or other references."})
    static class Delete implements Callable<Integer> {
        @Mixin Edit edit;
        @Mixin Target target;
        public Integer call() throws Exception {
            var op = JsonNodeFactory.instance.objectNode().put("op", "manifest.delete");
            target.put(op);
            return edit.apply(op);
        }
    }
}
