package dev.aqe;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "aqe", mixinStandardHelpOptions = true,
        description = "APK Quick Editor. Runs with Java 11+; no Android SDK required.",
        footerHeading = "%nExamples:%n",
        footer = {"  java -jar aqe.jar info app.apk",
                "  java -jar aqe.jar apply app.apk --patch patch.json -o edited.apk",
                "  java -jar aqe.jar dex export app.apk com.example.Main -o Main.smali",
                "  java -jar aqe.jar help apply",
                "", "LLM / first-time use: java -jar aqe.jar --help-all",
                "Copyable examples: java -jar aqe.jar examples",
                "Raw template: java -jar aqe.jar examples dex-call --file patch.json > patch.json",
                "", "Editing removes old signatures. Use sign, or apply --ks KEYSTORE --alias ALIAS.",
                "Output must differ from input; --force only replaces an existing output.",
                "Exit codes: 0 success/help; 1 operation failed; 2 invalid command line."},
        subcommands = {Main.Info.class, Main.Apply.class, Main.Replace.class, Main.Dex.class, Main.Manifest.class,
                Main.Resource.class, Main.Sign.class, Main.Verify.class, HelpDocs.Examples.class, CommandLine.HelpCommand.class})
public final class Main implements Runnable {
    @Option(names = "--help-all", description = "Print the complete offline guide and every command's help (for LLMs)")
    boolean helpAll;
    @CommandLine.Spec CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    static CommandLine commandLine() {
        CommandLine cli = new CommandLine(new Main()).setExecutionExceptionHandler((error, command, result) -> {
            command.getErr().println("Error: " + error.getMessage());
            if (Boolean.getBoolean("aqe.debug")) error.printStackTrace(command.getErr());
            return 1;
        });
        configureHelp(cli);
        return cli;
    }

    private static void configureHelp(CommandLine cli) {
        String version = Main.class.getPackage().getImplementationVersion();
        cli.getCommandSpec().version("aqe " + (version == null ? "dev" : version));
        cli.getCommandSpec().usageMessage().width(110).showDefaultValues(true);
        cli.getSubcommands().values().forEach(Main::configureHelp);
    }

    @Override public void run() {
        if (helpAll) HelpDocs.printAll(spec.commandLine());
        else spec.commandLine().usage(spec.commandLine().getOut());
    }

    static class Output {
        @Option(names = {"-o", "--output"}, required = true, description = "Output path (must differ from input)")
        Path path;
        @Option(names = "--force", description = "Replace an existing output file") boolean force;
        void unsigned() { System.out.println("Wrote unsigned APK: " + path + " (use aqe sign to install)"); }
    }

    @Command(name = "info", mixinStandardHelpOptions = true, description = "Show APK metadata and DEX entries")
    static class Info implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        public Integer call() throws Exception {
            ApkArchive.validate(apk);
            var manifest = ResourceEditor.manifest(apk);
            System.out.println("Package: " + manifest.getPackageName());
            System.out.println("Version: " + manifest.getVersionName() + " (" + manifest.getVersionCode() + ")");
            System.out.println("Min SDK: " + ResourceEditor.minSdk(apk));
            ApkArchive.dexNames(apk).forEach(System.out::println);
            return 0;
        }
    }

    @Command(name = "replace", mixinStandardHelpOptions = true,
            description = "Replace/add APK entries; repeat ENTRY=FILE to batch changes",
            footer = {"", "Example: aqe replace app.apk assets/config.json=./config.json lib/arm64-v8a/libx.so=./libx.so -o edited.apk",
                    "ENTRY is the exact path inside the APK. FILE is a local file; missing entries are added.",
                    "Compiled resources must already be compiled (binary XML / compiled nine-patch).",
                    "For explicit add/replace/delete operations together, use aqe apply --help.",
                    "Complete workflow and payload files: java -jar aqe.jar examples files"})
    static class Replace implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Parameters(index = "1..*", arity = "1..*", paramLabel = "ENTRY=FILE",
                description = "APK entry path and local source file; repeat for multiple files") List<String> files;
        @Mixin Output output;
        public Integer call() throws Exception {
            Map<String, ApkArchive.Replacement> replacements = new LinkedHashMap<>();
            for (String item : files) {
                int split = item.indexOf('=');
                if (split < 1 || split == item.length() - 1)
                    throw new IllegalArgumentException("Expected ENTRY=FILE: " + item);
                String name = item.substring(0, split);
                if (replacements.putIfAbsent(name, ApkArchive.Replacement.file(Path.of(item.substring(split + 1)))) != null)
                    throw new IllegalArgumentException("Repeated replacement: " + name);
            }
            ApkArchive.rewrite(apk, output.path, replacements, output.force);
            output.unsigned();
            return 0;
        }
    }

    @Command(name = "apply", mixinStandardHelpOptions = true,
            description = "Apply ordered JSON file/class/resource operations in one transaction",
            footerHeading = "%nExamples:%n",
            footer = {"  aqe apply app.apk --patch patch.json -o edited.apk",
                    "  aqe apply app.apk --patch patch.json --ks release.p12 --alias release -o signed.apk",
                    "", "patch.json (adjust class names and paths to your APK):",
                    "  {\"version\":1,\"operations\":[",
                    "    {\"op\":\"dex.add\",\"inputs\":[\"Helper.smali\"],\"dex\":\"classes.dex\"},",
                    "    {\"op\":\"dex.replace\",\"inputs\":[\"Main.smali\"]},",
                    "    {\"op\":\"dex.delete\",\"classes\":[\"com.example.Legacy\"]},",
                    "    {\"op\":\"file.replace\",\"path\":\"assets/config.json\",\"source\":\"config.json\"},",
                    "    {\"op\":\"file.add\",\"path\":\"assets/new.txt\",\"source\":\"new.txt\"},",
                    "    {\"op\":\"file.delete\",\"path\":\"assets/old.txt\"},",
                    "    {\"op\":\"manifest.set\",\"label\":\"Edited\",\"versionName\":\"2.0\",\"versionCode\":2},",
                    "    {\"op\":\"resource.set-string\",\"id\":\"0x7f010000\",\"config\":\"en\",\"value\":\"Hello\"}",
                    "  ]}",
                    "", "Class inputs: Smali file/directory, DEX, .class or JAR; Java source is not accepted.",
                    "dex.add/replace optionally accept api (integer; default APK minSdk) and libraries (JAR path array).",
                    "dex.add requires an existing destination DEX; default classes.dex. No automatic DEX splitting.",
                    "resource.set-string requires an existing simple string/config; omitted config means the default config.",
                    "Input paths inside JSON are relative to the JSON file. CLI paths are relative to the working directory.",
                    "Operations run in order: add requires absence; replace/delete require existence.",
                    "Whole-file replacement supersedes earlier edits to that file. Each changed DEX is written once.",
                    "Any failure (including signing) leaves input and existing output intact; errors identify the operation.",
                    "dex.delete checks all final DEX classes and literal Manifest component names; remaining direct references reject the entire output.",
                    "Fix/remove callers in the same patch. The final state is checked, so delete/repair order does not matter. --force cannot bypass this check.",
                    "Reflection/JNI/dynamic loading, XML/resource class strings and member compatibility are outside the check. Raw DEX file operations alone do not enable it.",
                    "Calls to added classes and resource references must be updated by your patch.",
                    "Without --ks/--alias the result is unsigned. Signing password defaults to env AQE_KS_PASS.",
                    "Unknown fields/operations, duplicate JSON keys and an empty operations array are rejected.",
                    "Full workflow: java -jar aqe.jar examples dex-call; all operation templates: java -jar aqe.jar examples batch",
                    "One-call offline reference for LLMs: java -jar aqe.jar --help-all"})
    static class Apply implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Option(names = "--patch", required = true, description = "Patch JSON; source paths are relative to this file")
        Path patch;
        @Mixin Output output;
        @ArgGroup(exclusive = false, heading = "Optional signing (requires --ks and --alias):%n")
        SigningOptions signing;

        public Integer call() throws Exception {
            var result = BatchEditor.apply(apk, patch, output.path, output.force,
                    signing == null ? null : (unsigned, target) -> signing.sign(unsigned, target, true));
            System.out.printf("Applied %d operations; wrote %d entries, deleted %d entries, rebuilt %d DEX.%n",
                    result.operations(), result.writtenEntries(), result.deletedEntries(), result.rebuiltDex());
            if (signing == null) output.unsigned();
            else System.out.println("Signed and verified: " + output.path);
            return 0;
        }
    }

    @Command(name = "dex", mixinStandardHelpOptions = true, description = "Read and edit DEX classes",
            footer = {"", "Class names accept com.example.Main or 'Lcom/example/Main;'.",
                    "Use aqe dex COMMAND --help for examples. Safe deletion: dex delete, or dex.delete in apply."},
            subcommands = {DexList.class, DexExport.class, DexReplace.class, DexAdd.class, DexDelete.class})
    static class Dex {}

    @Command(name = "delete", mixinStandardHelpOptions = true,
            description = "Delete classes only if no remaining static references target them",
            footer = {"", "Example: aqe dex delete app.apk com.example.Legacy 'Lcom/example/Unused;' -o edited.apk",
                    "Scans all remaining DEX classes (including signatures, annotations and instructions) and literal Manifest component names.",
                    "Any reference rejects the entire output, even with --force. --force only permits replacing an existing output file.",
                    "Use apply with dex.replace + dex.delete to fix callers and delete classes in one transaction.",
                    "The check uses the final patch state; mutually referring classes can be deleted together.",
                    "Reflection strings, JNI, dynamic code and resource/XML class names are outside this static check.",
                    "This is a class-deletion guard, not a general method/field linker or a guarantee of runtime correctness."})
    static class DexDelete implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Parameters(index = "1..*", arity = "1..*", paramLabel = "CLASS", description = "Existing class names or DEX descriptors")
        List<String> classes;
        @Mixin Output output;
        public Integer call() throws Exception {
            BatchEditor.apply(apk, PatchPlan.deleteClasses(classes), output.path, output.force, null);
            output.unsigned();
            return 0;
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "List class descriptors and owning DEX")
    static class DexList implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        public Integer call() throws Exception { DexEditor.listClasses(apk).forEach(System.out::println); return 0; }
    }

    @Command(name = "export", mixinStandardHelpOptions = true, description = "Export one class as Smali",
            footer = {"", "Example: aqe dex export app.apk com.example.Main -o Main.smali",
                    "Edit the exported Smali, then use dex replace or a dex.replace operation in apply."})
    static class DexExport implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Parameters(index = "1", paramLabel = "CLASS", description = "Class name or DEX descriptor") String className;
        @Mixin Output output;
        public Integer call() throws Exception {
            String source = DexEditor.exportClass(apk, className);
            Outputs.write(apk, output.path, output.force, path -> Files.writeString(path, source));
            System.out.println("Wrote " + output.path);
            return 0;
        }
    }

    static class ClassInputs {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Parameters(index = "1..*", arity = "1..*", paramLabel = "CLASS_INPUT",
                description = "Smali file/directory, DEX, .class or JAR (all contained classes); not .java") List<Path> inputs;
        @Option(names = "--api", description = "Smali/D8 API level (default: APK minSdk)") Integer api;
        @Option(names = "--lib", description = "Library JAR for D8; repeat for multiple libraries")
        List<Path> libraries = new ArrayList<>();
        @Mixin Output output;
        int api() throws IOException {
            int level = api == null ? ResourceEditor.minSdk(apk) : api;
            if (level < 1) throw new IllegalArgumentException("API level must be positive");
            return level;
        }
    }

    @Command(name = "replace", mixinStandardHelpOptions = true,
            description = "Replace existing classes from Smali, DEX, .class or JAR inputs",
            footer = {"", "Example: aqe dex replace app.apk Main.smali Other.smali -o edited.apk",
                    "Every imported class must already exist. Unaffected DEX entries retain their compressed bytes.",
                    "DEX 035-040 only; no automatic DEX splitting. Output is unsigned.",
                    "Add a helper and update its caller together: java -jar aqe.jar examples dex-call"})
    static class DexReplace implements Callable<Integer> {
        @Mixin ClassInputs args;
        public Integer call() throws Exception {
            DexEditor.edit(args.apk, args.output.path, args.inputs, false, null,
                    args.api(), args.libraries, args.output.force);
            args.output.unsigned();
            return 0;
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Add new classes to an existing DEX; rejects class name collisions",
            footer = {"", "Examples:", "  aqe dex add app.apk Helper.smali --dex classes.dex -o edited.apk",
                    "  aqe dex add app.apk helper.jar --lib android.jar -o edited.apk",
                    "Every imported class must be new. The destination DEX must already exist.",
                    "DEX 035-040 only; no automatic DEX splitting. Call sites must be edited separately or via apply.",
                    "Output is unsigned. Libraries are for D8 resolution and are not imported into the APK.",
                    "Complete workflows: java -jar aqe.jar examples dex-call / java -jar aqe.jar examples java-class"})
    static class DexAdd implements Callable<Integer> {
        @Mixin ClassInputs args;
        @Option(names = "--dex", defaultValue = "classes.dex", description = "Existing destination DEX") String dex;
        public Integer call() throws Exception {
            DexEditor.edit(args.apk, args.output.path, args.inputs, true, dex,
                    args.api(), args.libraries, args.output.force);
            args.output.unsigned();
            return 0;
        }
    }

    @Command(name = "manifest", mixinStandardHelpOptions = true,
            description = "Edit binary AndroidManifest.xml", subcommands = ManifestSet.class)
    static class Manifest {}

    @Command(name = "set", mixinStandardHelpOptions = true, description = "Set application label or version",
            footer = {"", "Example: aqe manifest set app.apk --label Edited --version-name 2.0 --version-code 2 -o edited.apk",
                    "Specify at least one field. --label sets a literal; use resource set-string to keep language variants."})
    static class ManifestSet implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Option(names = "--label", description = "Literal application label (replaces a resource reference)") String label;
        @Option(names = "--version-name", description = "Human-readable version string") String versionName;
        @Option(names = "--version-code", description = "Positive integer version code") Integer versionCode;
        @Mixin Output output;
        public Integer call() throws Exception {
            ResourceEditor.setManifest(apk, output.path, label, versionName, versionCode, output.force);
            output.unsigned();
            return 0;
        }
    }

    @Command(name = "resource", mixinStandardHelpOptions = true,
            description = "Edit compiled resources", subcommands = SetString.class)
    static class Resource {}

    @Command(name = "set-string", mixinStandardHelpOptions = true,
            description = "Replace a string value by resource ID and configuration",
            footer = {"", "Example: aqe resource set-string app.apk 0x7f010000 'New name' --config en -o edited.apk",
                    "The resource/config must exist and be a simple string. Other language/config variants are preserved."})
    static class SetString implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Parameters(index = "1", paramLabel = "ID", description = "Resource ID, e.g. 0x7f010000") String id;
        @Parameters(index = "2", paramLabel = "VALUE", description = "Replacement string (quote spaces)") String value;
        @Option(names = "--config", defaultValue = "", description = "Exact qualifiers, e.g. en; default configuration if omitted")
        String config;
        @Mixin Output output;
        public Integer call() throws Exception {
            ResourceEditor.setString(apk, output.path, Long.decode(id).intValue(), config, value, output.force);
            output.unsigned();
            return 0;
        }
    }

    @Command(name = "sign", mixinStandardHelpOptions = true, description = "Align, sign and verify an APK using a JKS/PKCS12 key",
            footer = {"", "Example: aqe sign edited.apk --ks release.p12 --alias release -o signed.apk",
                    "Set AQE_KS_PASS or enter the password at an interactive terminal; no plaintext password arguments.",
                    "Uses v1 when minSdk < 24, plus v2/v3; no v4 idsig. Verifies signatures and ZIP alignment before publishing."})
    static class Sign implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Input APK file") Path apk;
        @Mixin SigningOptions signing;
        @Mixin Output output;
        public Integer call() throws Exception {
            signing.sign(apk, output.path, output.force);
            System.out.println("Signed and verified: " + output.path);
            return 0;
        }
    }

    static class SigningOptions {
        @Option(names = "--ks", required = true, description = "JKS or PKCS12 keystore file") Path keyStore;
        @Option(names = "--alias", required = true, description = "Private-key alias in the keystore") String alias;
        @Option(names = "--ks-pass-env", defaultValue = "AQE_KS_PASS", description = "Environment variable containing the keystore password")
        String storePasswordEnv;
        @Option(names = "--key-pass-env", description = "Environment variable containing key password; defaults to store password")
        String keyPasswordEnv;
        void sign(Path apk, Path output, boolean force) throws Exception {
            char[] storePassword = password(storePasswordEnv);
            char[] keyPassword = null;
            try {
                keyPassword = keyPasswordEnv == null ? storePassword.clone() : password(keyPasswordEnv);
                Signing.sign(apk, output, keyStore, alias, storePassword, keyPassword, force);
            } finally {
                Arrays.fill(storePassword, '\0');
                if (keyPassword != null) Arrays.fill(keyPassword, '\0');
            }
        }
        private char[] password(String name) throws IOException {
            String value = System.getenv(name);
            if (value != null) return value.toCharArray();
            if (System.console() != null) {
                char[] entered = System.console().readPassword("Password (%s): ", name);
                if (entered != null) return entered;
            }
            throw new IOException("Set " + name + " or run from an interactive terminal");
        }
    }

    @Command(name = "verify", mixinStandardHelpOptions = true, description = "Verify APK signatures")
    static class Verify implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "APK", description = "Signed APK to verify") Path apk;
        public Integer call() throws Exception {
            var result = Signing.verify(apk);
            if (!result.isVerified()) throw new IOException("Signature verification failed: " + result.getAllErrors());
            System.out.printf("Verified: v1=%s v2=%s v3=%s%n", result.isVerifiedUsingV1Scheme(),
                    result.isVerifiedUsingV2Scheme(), result.isVerifiedUsingV3Scheme());
            return 0;
        }
    }
}
