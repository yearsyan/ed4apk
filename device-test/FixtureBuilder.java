package dev.aqe;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.zipflinger.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.util.*;

/** Test fixture generation only; not included in the distributed AQE JAR. */
public final class FixtureBuilder {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), androidJar = Path.of(args[1]), output = Path.of(args[2]);
        Path base = Files.createDirectory(output.resolve("base-classes"));
        compile(androidJar, base, List.of(source.resolve("app/MainActivity.java")));
        List<Path> classes;
        try (var paths = Files.walk(base)) {
            classes = paths.filter(p -> p.toString().endsWith(".class")).toList();
        }
        var imported = DexEditor.importClasses(classes, 21, List.of(androidJar));
        var secondary = imported.remove("Ldev/aqe/smoketest/OtherDex;");
        if (secondary == null) throw new IllegalStateException("Missing secondary DEX fixture");
        AndroidManifestBlock manifest = new AndroidManifestBlock();
        manifest.setPackageName("dev.aqe.smoketest");
        manifest.setMinSdkVersion(21);
        manifest.setTargetSdkVersion(34);
        manifest.setVersionCode(1);
        manifest.setVersionName("1.0");
        manifest.setExtractNativeLibs(false);
        manifest.setApplicationLabel("AQE Original");
        manifest.setDebuggable(true);
        manifest.getOrCreateMainActivity("dev.aqe.smoketest.MainActivity")
                .getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true);
        manifest.refreshFull();
        TableBlock table = new TableBlock();
        var pkg = table.newPackage(0x7f, "dev.aqe.smoketest");
        var title = pkg.getOrCreate("", "string", "title");
        title.setValueAsString("Original resource");
        pkg.getOrCreate("", "raw", "probe").setValueAsString("res/raw/probe.txt");
        table.refresh();
        try (ZipArchive zip = new ZipArchive(output.resolve("base-unsigned.apk"))) {
            zip.add(new BytesSource(manifest.getBytes(), "AndroidManifest.xml", 1));
            zip.add(new BytesSource(table.getBytes(), "resources.arsc", 0));
            zip.add(new BytesSource(DexEditor.writeClasses(imported.values(), Opcodes.forApi(21)), "classes.dex", 1));
            zip.add(new BytesSource(DexEditor.writeClasses(List.of(secondary), Opcodes.forApi(21)), "classes2.dex", 1));
            zip.add(new BytesSource("old-config".getBytes(), "assets/config.txt", 1));
            zip.add(new BytesSource("obsolete".getBytes(), "assets/obsolete.txt", 1));
            zip.add(new BytesSource("keep".getBytes(), "assets/keep.txt", 1));
            zip.add(new BytesSource("old-raw".getBytes(), "res/raw/probe.txt", 1));
            zip.add(new BytesSource(Files.readAllBytes(output.resolve("original.so")), "lib/arm64-v8a/libaqeprobe.so", 0));
        }
        Path patchSource = Files.createDirectory(output.resolve("patch-source"));
        Files.writeString(patchSource.resolve("Added.java"), """
                package dev.aqe.smoketest;
                public class Added { public static String message() { return "helper-called"; } }
                """);
        Files.writeString(patchSource.resolve("PatchTarget.java"), """
                package dev.aqe.smoketest;
                class PatchTarget { static String message() { return Added.message(); } }
                """);
        Path patchClasses = Files.createDirectory(output.resolve("patch-classes"));
        compile(androidJar, patchClasses, List.of(patchSource.resolve("Added.java"), patchSource.resolve("PatchTarget.java")));
        Files.writeString(output.resolve("config.txt"), "new-config");
        Files.writeString(output.resolve("added.txt"), "new-asset");
        Files.writeString(output.resolve("raw.txt"), "new-raw");
        var operations = List.of(
                Map.of("op", "dex.add", "inputs", List.of("patch-classes/dev/aqe/smoketest/Added.class"), "dex", "classes2.dex"),
                Map.of("op", "dex.replace", "inputs", List.of("patch-classes/dev/aqe/smoketest/PatchTarget.class")),
                Map.of("op", "dex.delete", "classes", List.of("dev.aqe.smoketest.Legacy")),
                Map.of("op", "file.replace", "path", "assets/config.txt", "source", "config.txt"),
                Map.of("op", "file.add", "path", "assets/added.txt", "source", "added.txt"),
                Map.of("op", "file.delete", "path", "assets/obsolete.txt"),
                Map.of("op", "file.replace", "path", "res/raw/probe.txt", "source", "raw.txt"),
                Map.of("op", "file.replace", "path", "lib/arm64-v8a/libaqeprobe.so", "source", "replacement.so"),
                Map.of("op", "manifest.set", "label", "AQE Edited", "versionCode", 2, "versionName", "2.0"),
                Map.of("op", "resource.set-string", "id", String.format("0x%08x", title.getResourceId()), "value", "New resource"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("patch.json").toFile(),
                Map.of("version", 1, "operations", operations));
        System.out.println("Generated device fixture and 10-operation patch: " + output);
    }

    private static void compile(Path androidJar, Path output, List<Path> sources) {
        List<String> options = new ArrayList<>(List.of("--release", "8", "-classpath", androidJar.toString(),
                "-d", output.toString()));
        sources.forEach(p -> options.add(p.toString()));
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new));
        if (result != 0) throw new IllegalStateException("Fixture javac failed: " + result);
    }
}
