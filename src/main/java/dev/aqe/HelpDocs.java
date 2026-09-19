package dev.aqe;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;

/** Offline documentation and text templates shipped inside the fat JAR. */
final class HelpDocs {
    private static final String ROOT = "/dev/aqe/help/";
    private static final Map<String, Example> EXAMPLES = new LinkedHashMap<>();
    static {
        EXAMPLES.put("files", new Example("Replace/add/delete assets or other APK entries",
                List.of("README.md", "patch.json", "config.json", "new.txt")));
        EXAMPLES.put("dex-call", new Example("Add a Smali helper and call it from an existing class",
                List.of("README.md", "patch.json", "Helper.smali", "call-site.txt")));
        EXAMPLES.put("java-class", new Example("Compile a Java helper, import .class with bundled D8",
                List.of("README.md", "patch.json", "Helper.java", "call-site.txt")));
        EXAMPLES.put("class-rename", new Example("Discover references, preview and rename a class within its package",
                List.of("README.md", "patch.json")));
        EXAMPLES.put("batch", new Example("All nine patch operations, with signing and verification",
                List.of("README.md", "patch.json")));
    }

    private static final class Example {
        private final String description;
        private final List<String> files;
        Example(String description, List<String> files) { this.description = description; this.files = files; }
        String description() { return description; }
        List<String> files() { return files; }
    }

    private static String read(String path) {
        try (InputStream in = HelpDocs.class.getResourceAsStream(ROOT + path)) {
            if (in == null) throw new IllegalStateException("Missing bundled help resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void printAll(CommandLine cli) {
        PrintWriter out = cli.getOut();
        out.println(read("guide.md"));
        out.println("# Complete command reference (generated from this CLI)");
        printCommand(cli, "java -jar aqe.jar", out);
        out.flush();
    }

    private static void printCommand(CommandLine cli, String invocation, PrintWriter out) {
        out.println("\n## " + invocation + " --help\n\n```text");
        out.print(cli.getUsageMessage(CommandLine.Help.Ansi.OFF));
        out.println("```");
        for (var entry : cli.getSubcommands().entrySet())
            printCommand(entry.getValue(), invocation + " " + entry.getKey(), out);
    }

    @Command(name = "examples", mixinStandardHelpOptions = true,
            description = "List or print bundled workflows; --file emits a raw template to stdout",
            footer = {"", "Examples:",
                    "  java -jar aqe.jar examples",
                    "  java -jar aqe.jar examples dex-call",
                    "  java -jar aqe.jar examples dex-call --file Helper.smali > Helper.smali",
                    "  java -jar aqe.jar examples dex-call --file patch.json > patch.json",
                    "Without TOPIC, lists available topics. With TOPIC, prints instructions and every supplied file.",
                    "--file prints only the requested file, with no banners, Markdown fences or status messages.",
                    "Examples contain placeholders. Read the prerequisites before applying them to an APK.",
                    "These commands do not edit an APK or need network access; shell redirection creates files."})
    static class Examples implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "TOPIC",
                description = "files, dex-call, java-class, class-rename or batch") String topic;
        @Option(names = "--file", paramLabel = "NAME", description = "Print one listed file verbatim; requires TOPIC")
        String file;
        @Spec CommandLine.Model.CommandSpec spec;

        @Override public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            if (topic == null) {
                if (file != null) throw new CommandLine.ParameterException(spec.commandLine(), "--file requires TOPIC");
                out.println("Bundled examples (read-only; paths/class names must match your APK):");
                for (var entry : EXAMPLES.entrySet()) {
                    out.println("  " + entry.getKey() + " - " + entry.getValue().description());
                    out.println("    Files: " + String.join(", ", entry.getValue().files()));
                }
                out.println("\nRead: java -jar aqe.jar examples dex-call");
                out.println("Save: java -jar aqe.jar examples dex-call --file patch.json > patch.json");
                out.println("Full guide: java -jar aqe.jar --help-all");
            } else {
                Example example = EXAMPLES.get(topic);
                if (example == null) throw new CommandLine.ParameterException(spec.commandLine(),
                        "Unknown example '" + topic + "'; choose " + String.join(", ", EXAMPLES.keySet()));
                if (file != null) {
                    if (!example.files().contains(file)) throw new CommandLine.ParameterException(spec.commandLine(),
                            "Unknown file '" + file + "'; available: " + String.join(", ", example.files()));
                    out.print(read("examples/" + topic + "/" + file));
                } else {
                    out.println(read("examples/" + topic + "/README.md"));
                    for (String name : example.files()) {
                        if (name.equals("README.md")) continue;
                        out.println("\n## File: " + name + "\n");
                        String language = name.endsWith(".json") ? "json" : name.endsWith(".java") ? "java"
                                : name.endsWith(".smali") ? "smali" : "text";
                        out.println("```" + language);
                        out.print(read("examples/" + topic + "/" + name));
                        out.println("```");
                    }
                }
            }
            out.flush();
            return 0;
        }
    }
}
