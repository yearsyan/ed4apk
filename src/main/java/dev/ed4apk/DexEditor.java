package dev.ed4apk;

import com.android.tools.smali.baksmali.BaksmaliOptions;
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition;
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import com.android.tools.r8.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

final class DexEditor {
    static DexBackedDexFile parse(byte[] bytes) throws IOException {
        if (bytes.length < 112 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x')
            throw new IOException("Not a standard DEX file");
        int version;
        try { version = Integer.parseInt(new String(bytes, 4, 3, StandardCharsets.US_ASCII)); }
        catch (NumberFormatException e) { throw new IOException("Invalid DEX version", e); }
        if (version >= 41)
            throw new IOException("DEX " + version + " containers are not yet supported by ed4apk editing");
        return new DexBackedDexFile(null, bytes);
    }

    static String descriptor(String name) {
        if (name.startsWith("L") && name.endsWith(";")) return name;
        return "L" + name.replace('.', '/') + ";";
    }

    static List<String> listClasses(Path apk) throws IOException {
        List<String> result = new ArrayList<>();
        for (String name : ApkArchive.dexNames(apk)) {
            for (ClassDef cls : parse(ApkArchive.read(apk, name)).getClasses())
                result.add(name + " | " + cls.getType());
        }
        return result;
    }

    static String exportClass(Path apk, String className) throws IOException {
        String target = descriptor(className);
        String result = null;
        for (String name : ApkArchive.dexNames(apk)) {
            for (ClassDef cls : parse(ApkArchive.read(apk, name)).getClasses()) {
                if (!cls.getType().equals(target)) continue;
                if (result != null) throw new IOException("Duplicate class in APK: " + target);
                StringWriter text = new StringWriter();
                ClassDefinition definition = new ClassDefinition(new BaksmaliOptions(), cls);
                try (BaksmaliWriter writer = new BaksmaliWriter(text)) { definition.writeTo(writer); }
                if (definition.hadValidationErrors()) throw new IOException("Invalid class: " + target);
                result = text.toString();
            }
        }
        if (result == null) throw new IOException("Class not found: " + target);
        return result;
    }

    static Map<String, ClassDef> importClasses(List<Path> inputs, int api, List<Path> libraries) throws Exception {
        Map<String, ClassDef> result = new LinkedHashMap<>();
        Path temporary = Files.createTempDirectory("ed4apk-dex-");
        try {
            List<String> smali = new ArrayList<>();
            List<Path> jvm = new ArrayList<>();
            for (Path input : inputs) {
                if (!Files.exists(input)) throw new IOException("Class input not found: " + input);
                String name = input.getFileName().toString();
                if (Files.isDirectory(input) || name.endsWith(".smali")) smali.add(input.toString());
                else if (name.endsWith(".dex")) addClasses(result, parse(Files.readAllBytes(input)));
                else if (name.endsWith(".class") || name.endsWith(".jar")) jvm.add(input);
                else throw new IOException("Expected Smali file/directory, DEX, .class or JAR: " + input);
            }
            if (!smali.isEmpty()) {
                SmaliOptions options = new SmaliOptions();
                options.apiLevel = api;
                options.jobs = Math.min(8, Runtime.getRuntime().availableProcessors());
                options.outputDexFile = temporary.resolve("smali.dex").toString();
                if (!Smali.assemble(options, smali)) throw new IOException("Smali assembly failed");
                addClasses(result, parse(Files.readAllBytes(Path.of(options.outputDexFile))));
            }
            if (!jvm.isEmpty()) {
                Path converted = Files.createDirectory(temporary.resolve("d8"));
                var builder = D8Command.builder().addProgramFiles(jvm).addLibraryFiles(libraries)
                        .setMinApiLevel(api).setMode(CompilationMode.RELEASE)
                        .setOutput(converted, OutputMode.DexIndexed);
                D8.run(builder.build());
                try (var paths = Files.list(converted)) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".dex")).sorted().collect(Collectors.toList()))
                        addClasses(result, parse(Files.readAllBytes(path)));
                }
            }
            if (result.isEmpty()) throw new IOException("No classes found in input");
            return result;
        } finally {
            Outputs.deleteTree(temporary);
        }
    }

    private static void addClasses(Map<String, ClassDef> into, DexBackedDexFile dex) throws IOException {
        for (ClassDef cls : dex.getClasses())
            if (into.putIfAbsent(cls.getType(), cls) != null)
                throw new IOException("Duplicate imported class: " + cls.getType());
    }

    static byte[] writeClasses(Collection<? extends ClassDef> classes, Opcodes opcodes) throws IOException {
        DexPool pool = new DexPool(opcodes);
        classes.stream().sorted(Comparator.comparing(ClassDef::getType)).forEach(pool::internClass);
        if (pool.hasOverflowed()) throw new IOException("DEX reference limit exceeded; select another target DEX");
        MemoryDataStore data = new MemoryDataStore();
        try {
            pool.writeTo(data);
            byte[] bytes = data.getData();
            parse(bytes);
            return bytes;
        } finally {
            data.close();
        }
    }

    static void edit(Path apk, Path output, List<Path> inputs, boolean add, String targetDex,
                     int api, List<Path> libraries, boolean force) throws Exception {
        Map<String, ClassDef> imported = importClasses(inputs, api, libraries);
        Map<String, String> owners = new HashMap<>();
        Map<String, DexBackedDexFile> affected = new LinkedHashMap<>();
        List<String> names = ApkArchive.dexNames(apk);
        if (add && !names.contains(targetDex))
            throw new IOException("Target DEX must exist: " + targetDex + "; new multidex placement is not automatic");
        for (String name : names) {
            DexBackedDexFile dex = parse(ApkArchive.read(apk, name));
            for (ClassDef cls : dex.getClasses()) {
                if (!imported.containsKey(cls.getType())) continue;
                if (owners.putIfAbsent(cls.getType(), name) != null)
                    throw new IOException("Duplicate class in APK: " + cls.getType());
                if (!add) affected.put(name, dex);
            }
            if (add && name.equals(targetDex)) affected.put(name, dex);
        }
        for (String type : imported.keySet()) {
            if (add && owners.containsKey(type)) throw new IOException("Class already exists; use dex replace: " + type);
            if (!add && !owners.containsKey(type)) throw new IOException("Class not found; use dex add: " + type);
        }
        Map<String, ApkArchive.Replacement> replacements = new LinkedHashMap<>();
        for (var item : affected.entrySet()) {
            List<ClassDef> classes = new ArrayList<>();
            for (ClassDef cls : item.getValue().getClasses()) {
                classes.add(!add && imported.containsKey(cls.getType()) ? imported.get(cls.getType()) : cls);
            }
            if (add) classes.addAll(imported.values());
            replacements.put(item.getKey(), ApkArchive.Replacement.bytes(
                    writeClasses(classes, item.getValue().getOpcodes())));
        }
        ApkArchive.rewrite(apk, output, replacements, force);
    }
}
