package dev.aqe;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.MethodHandleType;
import com.android.tools.smali.dexlib2.immutable.*;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.*;
import com.android.tools.smali.dexlib2.immutable.value.*;
import com.android.zipflinger.BytesSource;
import com.android.zipflinger.ZipArchive;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ClassDeletionTest {
    @TempDir Path temporary;
    private static final String TARGET = """
            .class public Lsample/Target;
            .super Ljava/lang/Exception;
            .field public static number:I
            .method public static value()I
                .registers 1
                const/4 v0, 1
                return v0
            .end method
            """;
    private static final String CALLER = ".class public Lsample/Caller;\n.super Ljava/lang/Object;\n";
    private static final String CALL = CALLER + """
            .method public static call()I
                .registers 1
                invoke-static {}, Lsample/Target;->value()I
                move-result v0
                return v0
            .end method
            """;
    private static final String CLEAN = CALLER + """
            .method public static call()I
                .registers 1
                const/4 v0, 2
                return v0
            .end method
            """;

    private Path source(String text) throws IOException {
        Path path = Files.createTempFile(temporary, "class-", ".smali");
        Files.writeString(path, text);
        return path;
    }

    private byte[] dex(String... classes) throws Exception {
        List<Path> sources = new ArrayList<>();
        for (String cls : classes) sources.add(source(cls));
        return DexEditor.writeClasses(DexEditor.importClasses(sources, 28, List.of()).values(), Opcodes.forApi(28));
    }

    private Path fixture(String target, String caller, Consumer<AndroidManifestBlock> configure) throws Exception {
        return fixture(target, dex(caller), configure);
    }

    private Path fixture(String target, byte[] caller, Consumer<AndroidManifestBlock> configure) throws Exception {
        Path apk = temporary.resolve(UUID.randomUUID() + ".apk");
        AndroidManifestBlock manifest = new AndroidManifestBlock();
        manifest.setPackageName("sample");
        manifest.setMinSdkVersion(28);
        manifest.setTargetSdkVersion(28);
        manifest.setApplicationLabel("Deletion test");
        configure.accept(manifest);
        manifest.refreshFull();
        try (ZipArchive zip = new ZipArchive(apk)) {
            zip.add(new BytesSource(manifest.getBytes(), "AndroidManifest.xml", 1));
            zip.add(new BytesSource(dex(target), "classes.dex", 1));
            zip.add(new BytesSource(caller, "classes2.dex", 1));
        }
        return apk;
    }

    private Path fixture(String caller) throws Exception { return fixture(TARGET, caller, m -> {}); }

    private Path patch(List<Map<String, Object>> operations) throws IOException {
        Path path = Files.createTempFile(temporary, "patch-", ".json");
        new ObjectMapper().writeValue(path.toFile(), Map.of("version", 1, "operations", operations));
        return path;
    }

    private Map<String, Object> delete(String... names) { return Map.of("op", "dex.delete", "classes", List.of(names)); }
    private Map<String, Object> replace(Path input) { return Map.of("op", "dex.replace", "inputs", List.of(input.toString())); }

    @Test void rejectsCrossDexCallAndPreservesInputAndExistingOutputEvenWithForce() throws Exception {
        Path apk = fixture(CALL);
        byte[] before = Files.readAllBytes(apk);
        Path output = temporary.resolve("existing.apk");
        Files.writeString(output, "existing output");
        IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(apk,
                PatchPlan.deleteClasses(List.of("sample.Target")), output, true,
                (unsigned, signed) -> fail("Signing must not begin on a rejected deletion")));
        for (String expected : List.of("Refusing class deletion", "Operation #1 (dex.delete)", "classes2.dex",
                "Lsample/Caller;->call()I", "invoke-static", "Lsample/Target;"))
            assertTrue(error.getMessage().contains(expected), error.getMessage());
        assertArrayEquals(before, Files.readAllBytes(apk));
        assertEquals("existing output", Files.readString(output));
    }

    @Test void validatesFinalStateRegardlessOfDeleteAndRepairOrder() throws Exception {
        Path apk = fixture(CALL);
        Path clean = source(CLEAN);
        for (List<Map<String, Object>> order : List.of(List.of(delete("sample.Target"), replace(clean)),
                List.of(replace(clean), delete("sample.Target")))) {
            Path output = temporary.resolve(UUID.randomUUID() + ".apk");
            BatchEditor.apply(apk, patch(order), output, false, null);
            assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(output));
            assertFalse(DexEditor.exportClass(output, "sample.Caller").contains("Lsample/Target;"));
        }
    }

    @Test void allowsMutualReferencesToBeRemovedTogetherAndReaddedClassesToRemainReferenced() throws Exception {
        Path mutual = fixture(TARGET.replace(".super Ljava/lang/Exception;",
                ".super Ljava/lang/Exception;\n.field public caller:Lsample/Caller;"), CALL, m -> {});
        Path empty = temporary.resolve("empty.apk");
        BatchEditor.apply(mutual, PatchPlan.deleteClasses(List.of("sample.Target", "sample.Caller")), empty, false, null);
        assertTrue(DexEditor.listClasses(empty).isEmpty());
        Path source = source(TARGET);
        Path readded = temporary.resolve("readded.apk");
        BatchEditor.apply(mutual, patch(List.of(delete("sample.Target"),
                Map.of("op", "dex.add", "inputs", List.of(source.toString()), "dex", "classes2.dex"))), readded, false, null);
        assertTrue(DexEditor.listClasses(readded).contains("classes2.dex | Lsample/Target;"));
    }

    @Test void checksFinalWholeDexReplacementsAndNewReferencesAfterDeletion() throws Exception {
        Path cleanDex = temporary.resolve("clean.dex"), badDex = temporary.resolve("bad.dex");
        Files.write(cleanDex, dex(CLEAN));
        Files.write(badDex, dex(CALL));
        Path badInput = fixture(CALL), goodInput = fixture(CLEAN);
        Path repaired = temporary.resolve("repaired.apk");
        BatchEditor.apply(badInput, patch(List.of(delete("sample.Target"),
                Map.of("op", "file.replace", "path", "classes2.dex", "source", cleanDex.toString()))), repaired, false, null);
        Path rejected = temporary.resolve("rejected.apk");
        assertThrows(IOException.class, () -> BatchEditor.apply(goodInput, patch(List.of(delete("sample.Target"),
                Map.of("op", "file.replace", "path", "classes2.dex", "source", badDex.toString()))), rejected, false, null));
        assertFalse(Files.exists(rejected));
        Path added = source(CALL.replace("Lsample/Caller;", "Lsample/NewCaller;"));
        assertThrows(IOException.class, () -> BatchEditor.apply(goodInput, patch(List.of(delete("sample.Target"),
                Map.of("op", "dex.add", "inputs", List.of(added.toString())))), rejected, false, null));
        assertFalse(Files.exists(rejected));
    }

    @Test void doesNotTreatStringsUnrelatedMissingTypesOrSharedPrefixesAsDirectReferences() throws Exception {
        Path input = fixture(CALLER + """
                .field public external:Lnot/in/ThisApk;
                .field public similarlyNamed:Lsample/TargetSuffix;
                .method public static text()Ljava/lang/String;
                    .registers 1
                    const-string v0, "Lsample/Target; sample.Target"
                    return-object v0
                .end method
                """);
        Path output = temporary.resolve("strings.apk");
        BatchEditor.apply(input, PatchPlan.deleteClasses(List.of("sample.Target")), output, false, null);
        assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(output));
    }

    static Stream<Arguments> referenceKinds() {
        return Stream.of(
                Arguments.of("superclass", CALLER.replace("Ljava/lang/Object;", "Lsample/Target;")),
                Arguments.of("interface", CALLER + ".implements Lsample/Target;\n"),
                Arguments.of("field array", CALLER + ".field public items:[[Lsample/Target;\n"),
                Arguments.of("parameter", CALLER + ".method public abstract accept([Lsample/Target;)V\n.end method\n"),
                Arguments.of("return", CALLER + ".method public abstract fetch()Lsample/Target;\n.end method\n"),
                Arguments.of("annotation type", CALLER + ".annotation runtime Lsample/Target;\n.end annotation\n"),
                Arguments.of("annotation array", CALLER + ".annotation runtime Lsample/Marker;\n classes = { [[Lsample/Target; }\n.end annotation\n"),
                Arguments.of("nested annotation", CALLER + ".annotation runtime Lsample/Marker;\n nested = .subannotation Lsample/Marker;\n type = Lsample/Target;\n.end subannotation\n.end annotation\n"),
                Arguments.of("enum annotation", CALLER + ".annotation runtime Lsample/Marker;\n item = .enum Lsample/Target;->ITEM:Lsample/Target;\n.end annotation\n"),
                Arguments.of("field annotation value", CALLER + ".annotation runtime Lsample/Marker;\n item = Lsample/Target;->number:I\n.end annotation\n"),
                Arguments.of("method annotation value", CALLER + ".annotation runtime Lsample/Marker;\n item = Lsample/Target;->value()I\n.end annotation\n"),
                Arguments.of("method type annotation value", CALLER + ".annotation runtime Lsample/Marker;\n item = (Lsample/Target;)V\n.end annotation\n"),
                Arguments.of("method handle annotation value", CALLER + ".annotation runtime Lsample/Marker;\n item = invoke-static@Lsample/Target;->value()I\n.end annotation\n"),
                Arguments.of("field annotation", CALLER + ".field public value:I\n.annotation runtime Lsample/Marker;\n type = Lsample/Target;\n.end annotation\n.end field\n"),
                Arguments.of("method annotation", CALLER + ".method public abstract value()V\n.annotation runtime Lsample/Marker;\n type = Lsample/Target;\n.end annotation\n.end method\n"),
                Arguments.of("parameter annotation", CALLER + ".method public abstract value(I)V\n.param p1\n.annotation runtime Lsample/Marker;\n type = Lsample/Target;\n.end annotation\n.end param\n.end method\n"),
                Arguments.of("static value", CALLER + ".field public static final token:Ljava/lang/Class; = Lsample/Target;\n"),
                Arguments.of("const-class", method("const-class v0, [[Lsample/Target;")),
                Arguments.of("new-instance", method("new-instance v0, Lsample/Target;")),
                Arguments.of("cast", method("const/4 v0, 0\ncheck-cast v0, Lsample/Target;")),
                Arguments.of("field owner", method("sget v0, Lsample/Target;->number:I")),
                Arguments.of("field reference type", method("sget-object v0, Lsample/External;->value:[Lsample/Target;")),
                Arguments.of("method reference signature", method("invoke-static {}, Lsample/External;->make()[Lsample/Target;")),
                Arguments.of("catch", method(":start\nconst/4 v0, 0\n:end\n.catch Lsample/Target; {:start .. :end} :handler\nreturn-void\n:handler\nmove-exception v0")),
                Arguments.of("debug local", method("const/4 v0, 0\n.local v0, \"item\":Lsample/Target;")),
                Arguments.of("method type", method("const-method-type v0, (Lsample/Target;)V")),
                Arguments.of("method handle", method("const-method-handle v0, invoke-static@Lsample/Target;->value()I")),
                Arguments.of("second reference", method("invoke-polymorphic {v0}, Ljava/lang/invoke/MethodHandle;->invoke()Ljava/lang/Object;, ()Lsample/Target;"))
        );
    }

    private static String method(String instructions) {
        return CALLER + ".method public static run()V\n.registers 1\n" + instructions + "\nreturn-void\n.end method\n";
    }

    @ParameterizedTest(name = "blocks {0}") @MethodSource("referenceKinds")
    void blocksEveryDirectReferenceKind(String name, String caller) throws Exception {
        Path apk = fixture(caller);
        IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(apk,
                PatchPlan.deleteClasses(List.of("sample.Target")), temporary.resolve("blocked.apk"), false, null));
        assertTrue(error.getMessage().contains("Refusing class deletion"), error.getMessage());
        assertTrue(error.getMessage().contains("Lsample/Target;"), error.getMessage());
        assertTrue(error.getMessage().contains("classes2.dex"), error.getMessage());
        assertFalse(Files.exists(temporary.resolve("blocked.apk")));
    }

    @Test void blocksLiteralManifestClassNamesButNotAnActivityAliasName() throws Exception {
        for (String name : List.of("sample.Target", ".Target", "Target")) {
            Path apk = fixture(TARGET, CLEAN, m -> m.getOrCreateMainActivity(name));
            IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(apk,
                    PatchPlan.deleteClasses(List.of("sample.Target")), temporary.resolve("manifest.apk"), false, null));
            assertTrue(error.getMessage().contains("AndroidManifest.xml"), error.getMessage());
        }
        Path alias = fixture(TARGET, CLEAN, m -> {
            var element = m.getApplicationElement().newElement("activity-alias");
            element.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("sample.Target");
            element.getOrCreateAndroidAttribute("targetActivity", 0x01010202).setValueAsString("sample.Caller");
        });
        BatchEditor.apply(alias, PatchPlan.deleteClasses(List.of("sample.Target")), temporary.resolve("alias.apk"), false, null);
    }

    @Test void checksInvokeCustomBootstrapPrototypeAndNestedArgumentsAfterDexRoundTrip() throws Exception {
        for (String position : List.of("bootstrap", "prototype", "argument")) {
            var bootstrap = new ImmutableMethodHandleReference(MethodHandleType.INVOKE_STATIC,
                    new ImmutableMethodReference(position.equals("bootstrap") ? "Lsample/Target;" : "Lsample/External;",
                            "bootstrap", List.of("Ljava/lang/invoke/MethodHandles$Lookup;", "Ljava/lang/String;",
                                    "Ljava/lang/invoke/MethodType;"), "Ljava/lang/invoke/CallSite;"));
            var prototype = new ImmutableMethodProtoReference(List.of(),
                    position.equals("prototype") ? "Lsample/Target;" : "V");
            var argument = new ImmutableArrayEncodedValue(List.of(new ImmutableTypeEncodedValue(
                    position.equals("argument") ? "Lsample/Target;" : "Ljava/lang/String;")));
            var callSite = new ImmutableCallSiteReference("site", bootstrap, "run", prototype, List.of(argument));
            var code = new ImmutableMethodImplementation(1, List.of(
                    new ImmutableInstruction35c(Opcode.INVOKE_CUSTOM, 0, 0, 0, 0, 0, 0, callSite),
                    new ImmutableInstruction10x(Opcode.RETURN_VOID)), List.of(), List.of());
            var method = new ImmutableMethod("Lsample/Caller;", "run", List.of(), "V", 0x9, Set.of(), Set.of(), code);
            var caller = new ImmutableClassDef("Lsample/Caller;", 1, "Ljava/lang/Object;", List.of(), null,
                    Set.of(), List.of(), List.of(method));
            Path apk = fixture(TARGET, DexEditor.writeClasses(List.of(caller), Opcodes.forApi(28)), m -> {});
            Path output = temporary.resolve(position + ".apk");
            IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(apk,
                    PatchPlan.deleteClasses(List.of("sample.Target")), output, false, null));
            assertTrue(error.getMessage().contains("Refusing class deletion"), error.getMessage());
            assertTrue(error.getMessage().contains("invoke-custom"), error.getMessage());
            assertFalse(Files.exists(output));
        }
    }

    @Test void checksFinalManifestReplacementAndAllowsRemovingItsComponentDeclaration() throws Exception {
        Path linked = fixture(TARGET, CLEAN, m -> m.getOrCreateMainActivity(".Target"));
        Path clean = fixture(CLEAN);
        Path linkedManifest = temporary.resolve("linked.xml"), cleanManifest = temporary.resolve("clean.xml");
        Files.write(linkedManifest, ApkArchive.read(linked, "AndroidManifest.xml"));
        Files.write(cleanManifest, ApkArchive.read(clean, "AndroidManifest.xml"));
        Path rejected = temporary.resolve("manifest-rejected.apk");
        IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(clean,
                patch(List.of(delete("sample.Target"), Map.of("op", "file.replace", "path", "AndroidManifest.xml",
                        "source", linkedManifest.toString()))), rejected, false, null));
        assertTrue(error.getMessage().contains("AndroidManifest.xml"), error.getMessage());
        assertFalse(Files.exists(rejected));
        Path output = temporary.resolve("manifest-repaired.apk");
        BatchEditor.apply(linked, patch(List.of(delete("sample.Target"),
                Map.of("op", "file.replace", "path", "AndroidManifest.xml", "source", cleanManifest.toString()))),
                output, false, null);
        assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(output));
    }

    @Test void standaloneJarRejectsLinkedDeletionAndAcceptsRepairedBatch() throws Exception {
        Path apk = fixture(CALL), output = temporary.resolve("cli.apk");
        String error = cli(1, "dex", "delete", apk.toString(), "sample.Target", "-o", output.toString());
        assertTrue(error.contains("Refusing class deletion"), error);
        assertFalse(Files.exists(output));
        Path plan = patch(List.of(delete("sample.Target"), replace(source(CLEAN))));
        cli(0, "apply", apk.toString(), "--patch", plan.toString(), "-o", output.toString());
        assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(output));
        cli(0, "dex", "delete", output.toString(), "sample.Caller", "-o", temporary.resolve("empty-cli.apk").toString());
    }

    @Test void allowReferencedSkipsTheGuardViaPatchFactoryAndJson() throws Exception {
        Path apk = fixture(CALL);
        Path output = temporary.resolve("allowed-factory.apk");
        BatchEditor.apply(apk, PatchPlan.deleteClasses(List.of("sample.Target"), true), output, false, null);
        assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(output));
        assertTrue(DexEditor.exportClass(output, "sample.Caller").contains("Lsample/Target;"));

        Path jsonOutput = temporary.resolve("allowed-json.apk");
        BatchEditor.apply(apk, patch(List.of(Map.of("op", "dex.delete",
                "classes", List.of("sample.Target"), "allowReferenced", true))), jsonOutput, false, null);
        assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(jsonOutput));

        Path rejected = temporary.resolve("still-rejected.apk");
        assertThrows(IOException.class, () -> BatchEditor.apply(apk,
                patch(List.of(Map.of("op", "dex.delete", "classes", List.of("sample.Target"),
                        "allowReferenced", "yes"))), rejected, false, null));
        assertFalse(Files.exists(rejected));
    }

    @Test void classesFileAndAllowReferencedFlagsDriveTheCli() throws Exception {
        Path apk = fixture(CALL), output = temporary.resolve("bulk-cli.apk");
        Path list = Files.createTempFile(temporary, "removal-", ".txt");
        Files.writeString(list, "# bulk removal\n\nsample.Target\n");
        cli(1, "dex", "delete", apk.toString(), "--classes-file", list.toString(), "-o", output.toString());
        assertFalse(Files.exists(output));
        cli(0, "dex", "delete", apk.toString(), "--classes-file", list.toString(),
                "--allow-referenced", "-o", output.toString());
        assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(output));
        String none = cli(1, "dex", "delete", apk.toString(), "-o", temporary.resolve("none.apk").toString());
        assertTrue(none.contains("No classes given"), none);
    }

    static Stream<Arguments> mixedDeletionModes() {
        return Stream.of(Arguments.of(true, false), Arguments.of(false, false),
                Arguments.of(true, true), Arguments.of(false, true));
    }

    @ParameterizedTest(name = "optOutFirst={0}, explicitFalse={1}")
    @MethodSource("mixedDeletionModes")
    void optOutMustNotDisableOtherDeletionGuards(boolean optOutFirst, boolean explicitFalse) throws Exception {
        Path apk = fixture(CALL);
        Path unused = source(CALLER.replace("Lsample/Caller;", "Lsample/Unused;"));
        Map<String, Object> permissive = Map.of("op", "dex.delete", "classes", List.of("sample.Unused"),
                "allowReferenced", true);
        Map<String, Object> guarded = new LinkedHashMap<>(delete("sample.Target"));
        if (explicitFalse) guarded.put("allowReferenced", false);
        List<Map<String, Object>> operations = new ArrayList<>();
        operations.add(Map.of("op", "dex.add", "inputs", List.of(unused.toString())));
        operations.add(optOutFirst ? permissive : guarded);
        operations.add(optOutFirst ? guarded : permissive);
        Path output = temporary.resolve("must-stay-unchanged.apk");
        Files.writeString(output, "existing output");
        IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(apk, patch(operations), output, true, null),
                "sample.Caller still references sample.Target; opting out for sample.Unused must not allow its deletion");
        assertTrue(error.getMessage().contains("Refusing class deletion"), error.getMessage());
        assertTrue(error.getMessage().contains("Lsample/Target;"), error.getMessage());
        assertEquals("existing output", Files.readString(output));
    }

    @ParameterizedTest(name = "optOutFirst={0}, explicitFalse={1}")
    @MethodSource("mixedDeletionModes")
    void mixedDeletionsStillAllowReferencesToExemptClasses(boolean optOutFirst, boolean explicitFalse) throws Exception {
        Path apk = fixture(CALL);
        Path unused = source(CALLER.replace("Lsample/Caller;", "Lsample/Unused;"));
        Map<String, Object> permissive = Map.of("op", "dex.delete", "classes", List.of("sample.Target"),
                "allowReferenced", true);
        Map<String, Object> guarded = new LinkedHashMap<>(delete("sample.Unused"));
        if (explicitFalse) guarded.put("allowReferenced", false);
        List<Map<String, Object>> operations = new ArrayList<>();
        operations.add(Map.of("op", "dex.add", "inputs", List.of(unused.toString())));
        operations.add(optOutFirst ? permissive : guarded);
        operations.add(optOutFirst ? guarded : permissive);
        Path output = temporary.resolve("mixed-allowed.apk");
        BatchEditor.apply(apk, patch(operations), output, false, null);
        assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(output));
        assertTrue(DexEditor.exportClass(output, "sample.Caller").contains("Lsample/Target;"));
    }

    @ParameterizedTest(name = "lastDeletionAllowsReferences={0}")
    @ValueSource(booleans = {true, false})
    void lastDeletionControlsTheGuardAfterReaddingAClass(boolean lastAllowsReferences) throws Exception {
        Path apk = fixture(CALL);
        Path target = source(TARGET);
        Path plan = patch(List.of(
                Map.of("op", "dex.delete", "classes", List.of("sample.Target"), "allowReferenced", !lastAllowsReferences),
                Map.of("op", "dex.add", "inputs", List.of(target.toString())),
                Map.of("op", "dex.delete", "classes", List.of("sample.Target"), "allowReferenced", lastAllowsReferences)));
        Path output = temporary.resolve("readded-policy.apk");
        if (lastAllowsReferences) {
            BatchEditor.apply(apk, plan, output, false, null);
            assertEquals(List.of("classes2.dex | Lsample/Caller;"), DexEditor.listClasses(output));
            assertTrue(DexEditor.exportClass(output, "sample.Caller").contains("Lsample/Target;"));
        } else {
            IOException error = assertThrows(IOException.class, () -> BatchEditor.apply(apk, plan, output, false, null));
            assertTrue(error.getMessage().contains("Refusing class deletion"), error.getMessage());
            assertTrue(error.getMessage().contains("Operation #3 (dex.delete)"), error.getMessage());
            assertFalse(Files.exists(output));
        }
    }

    private String cli(int expected, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(System.getProperty("aqe.java",
                Path.of(System.getProperty("java.home"), "bin", "java").toString()), "-jar", System.getProperty("aqe.jar")));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporary, "cli-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        for (String name : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT", "CLASSPATH")) builder.environment().remove(name);
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("CLI timed out"); }
        String text = Files.readString(log);
        assertEquals(expected, process.exitValue(), text);
        return text;
    }
}
