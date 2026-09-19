package dev.aqe;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.zipflinger.ZipArchive;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A patch is an ordered in-memory overlay, published only after every operation succeeds. */
final class BatchEditor {
    @FunctionalInterface interface Signer { void sign(Path unsigned, Path output) throws Exception; }
    static final class Result {
        private final int operations, writtenEntries, deletedEntries, rebuiltDex;
        Result(int operations, int writtenEntries, int deletedEntries, int rebuiltDex) {
            this.operations = operations;
            this.writtenEntries = writtenEntries;
            this.deletedEntries = deletedEntries;
            this.rebuiltDex = rebuiltDex;
        }
        int operations() { return operations; }
        int writtenEntries() { return writtenEntries; }
        int deletedEntries() { return deletedEntries; }
        int rebuiltDex() { return rebuiltDex; }
    }

    static Result apply(Path input, Path patch, Path output, boolean force, Signer signer) throws Exception {
        return apply(input, PatchPlan.read(patch), output, force, signer);
    }

    static Result apply(Path input, PatchPlan plan, Path output, boolean force, Signer signer) throws Exception {
        Session session = new Session(input);
        // Output validation and the final rename encompass editing AND optional signing.
        int[] rebuilt = new int[1];
        Outputs.write(input, output, force, temporary -> {
            for (PatchPlan.Operation operation : plan.operations()) {
                try {
                    session.execute(operation);
                } catch (Exception e) {
                    throw new IOException(operation.context() + ": " + e.getMessage(), e);
                }
            }
            rebuilt[0] = session.finish();
            if (signer == null) {
                ApkArchive.rewrite(input, temporary, session.replacements, session.deletions, true);
            } else {
                Path unsigned = Files.createTempFile(temporary.getParent(), ".aqe-unsigned-", ".apk");
                try {
                    ApkArchive.rewrite(input, unsigned, session.replacements, session.deletions, true);
                    signer.sign(unsigned, temporary);
                } finally {
                    Files.deleteIfExists(unsigned);
                }
            }
        });
        return new Result(plan.operations().size(), session.replacements.size(), session.deletions.size(), rebuilt[0]);
    }

    private static final class DexState {
        final Opcodes opcodes;
        final Map<String, ClassDef> classes = new LinkedHashMap<>();
        String lastEdit;
        DexState(DexBackedDexFile dex) throws IOException {
            opcodes = dex.getOpcodes();
            for (ClassDef cls : dex.getClasses()) {
                if (classes.putIfAbsent(cls.getType(), cls) != null)
                    throw new IOException("Duplicate class in DEX: " + cls.getType());
            }
        }
    }

    private static final class Session {
        final Path input;
        final Set<String> originalEntries;
        final Set<String> entries;
        final Map<String, ApkArchive.Replacement> replacements = new LinkedHashMap<>();
        final Set<String> deletions = new LinkedHashSet<>();
        final Map<String, DexState> dexStates = new LinkedHashMap<>();
        // Classes whose last explicit deletion still requires a reference check.
        final Map<String, String> guardedDeletions = new LinkedHashMap<>();
        Map<String, String> owners;
        AndroidManifestBlock manifest;
        TableBlock resources;
        boolean manifestDirty, resourcesDirty;

        Session(Path input) throws IOException {
            this.input = input;
            ApkArchive.validate(input);
            originalEntries = new LinkedHashSet<>(ZipArchive.listEntries(input).keySet());
            entries = new LinkedHashSet<>(originalEntries);
            entries.removeIf(ApkArchive::signatureEntry);
        }

        byte[] read(String name) throws IOException {
            if (!entries.contains(name)) throw new IOException("APK entry not found: " + name);
            var replacement = replacements.get(name);
            if (replacement == null) return ApkArchive.read(input, name);
            return replacement.file() == null ? replacement.bytes() : Files.readAllBytes(replacement.file());
        }

        AndroidManifestBlock manifest() throws IOException {
            if (manifest == null) manifest = ResourceEditor.manifest(read("AndroidManifest.xml"));
            return manifest;
        }

        void execute(PatchPlan.Operation op) throws Exception {
            switch (op.kind()) {
                case "file.add": case "file.replace": case "file.delete": file(op); break;
                case "dex.add": case "dex.replace": case "dex.delete": dex(op); break;
                case "manifest.set": {
                    ResourceEditor.setManifest(manifest(), op.optionalText("label", null),
                            op.optionalText("versionName", null), op.integer("versionCode"));
                    manifestDirty = true;
                    break;
                }
                case "resource.set-string": {
                    if (resources == null) resources = TableBlock.load(new ByteArrayInputStream(read("resources.arsc")));
                    long id = Long.decode(op.text("id"));
                    if (id < 1 || id > 0xffff_ffffL) throw new IOException("Resource ID must fit an unsigned 32-bit integer");
                    ResourceEditor.setString(resources, (int) id, op.optionalText("config", ""), op.text("value"));
                    resourcesDirty = true;
                    break;
                }
                default: throw new IOException("Unknown operation: " + op.kind());
            }
        }

        void file(PatchPlan.Operation op) throws IOException {
            String name = op.text("path");
            ApkArchive.checkEntryName(name);
            boolean exists = entries.contains(name);
            if (op.kind().equals("file.add") && exists) throw new IOException("Entry already exists: " + name);
            if (!op.kind().equals("file.add") && !exists) throw new IOException("Entry not found: " + name);
            if (op.kind().equals("file.delete")) {
                entries.remove(name);
                replacements.remove(name);
                if (originalEntries.contains(name)) deletions.add(name);
            } else {
                Path source = op.resolve(op.text("source"));
                if (!Files.isRegularFile(source)) throw new IOException("Source file not found: " + source);
                replacements.put(name, ApkArchive.Replacement.file(source));
                entries.add(name);
                deletions.remove(name);
            }
            // A later whole-file operation supersedes earlier structured edits to that file.
            if (name.equals("AndroidManifest.xml")) { manifest = null; manifestDirty = false; }
            if (name.equals("resources.arsc")) { resources = null; resourcesDirty = false; }
            if (ApkArchive.DEX_NAME.matcher(name).matches()) {
                dexStates.remove(name);
                owners = null;
            }
        }

        void indexClasses() throws IOException {
            if (owners != null) return;
            Map<String, String> index = new HashMap<>();
            for (String name : entries) {
                if (!ApkArchive.DEX_NAME.matcher(name).matches()) continue;
                DexState state = dexStates.get(name);
                if (state == null) {
                    state = new DexState(DexEditor.parse(read(name)));
                    dexStates.put(name, state);
                }
                for (String type : state.classes.keySet()) {
                    if (index.putIfAbsent(type, name) != null) throw new IOException("Duplicate class in APK: " + type);
                }
            }
            owners = index;
        }

        void dex(PatchPlan.Operation op) throws Exception {
            indexClasses();
            if (op.kind().equals("dex.delete")) {
                boolean allowReferenced = op.optionalFlag("allowReferenced");
                Set<String> types = new LinkedHashSet<>();
                for (String name : op.strings("classes", true)) {
                    String type = DexEditor.descriptor(name);
                    if (!types.add(type)) throw new IOException("Repeated class: " + type);
                    if (!owners.containsKey(type)) throw new IOException("Class not found: " + type);
                }
                for (String type : types) {
                    DexState state = dexStates.get(owners.remove(type));
                    state.classes.remove(type);
                    state.lastEdit = op.context();
                    // A class may be deleted, re-added, and deleted again in one patch.
                    // Only that class's latest deletion can change its guard policy.
                    if (allowReferenced) guardedDeletions.remove(type);
                    else guardedDeletions.put(type, op.context());
                }
                return;
            }
            Integer requestedApi = op.integer("api");
            Integer minSdk = requestedApi == null ? manifest().getMinSdkVersion() : requestedApi;
            int api = minSdk == null ? 1 : minSdk;
            if (api < 1) throw new IOException("API level must be positive");
            boolean add = op.kind().equals("dex.add");
            String destination = add ? op.optionalText("dex", "classes.dex") : null;
            if (add && !dexStates.containsKey(destination)) throw new IOException("Target DEX must exist: " + destination);
            Map<String, ClassDef> imported = DexEditor.importClasses(op.paths("inputs", true), api,
                    op.paths("libraries", false));
            for (String type : imported.keySet()) {
                if (add && owners.containsKey(type)) throw new IOException("Class already exists: " + type);
                if (!add && !owners.containsKey(type)) throw new IOException("Class not found: " + type);
            }
            for (var item : imported.entrySet()) {
                String name = add ? destination : owners.get(item.getKey());
                DexState state = dexStates.get(name);
                state.classes.put(item.getKey(), item.getValue());
                state.lastEdit = op.context();
                owners.put(item.getKey(), name);
            }
        }

        int finish() throws IOException {
            if (!entries.contains("AndroidManifest.xml")) throw new IOException("Final APK must contain AndroidManifest.xml");
            if (!guardedDeletions.isEmpty()) {
                // Reindex the FINAL overlay, including any whole-file DEX replacements made after dex.delete.
                indexClasses();
                Map<String, String> absent = new LinkedHashMap<>(guardedDeletions);
                absent.keySet().removeAll(owners.keySet());
                if (!absent.isEmpty()) {
                    ClassDeletionGuard guard = new ClassDeletionGuard(absent);
                    for (var item : dexStates.entrySet()) guard.scanDex(item.getKey(), item.getValue().classes.values());
                    guard.scanManifest(manifest());
                    guard.requireNoReferences();
                }
            }
            int rebuilt = 0;
            for (var item : dexStates.entrySet()) {
                DexState state = item.getValue();
                if (state.lastEdit == null) continue;
                try {
                    replacements.put(item.getKey(), ApkArchive.Replacement.bytes(
                            DexEditor.writeClasses(state.classes.values(), state.opcodes)));
                    rebuilt++;
                } catch (Exception e) {
                    throw new IOException("Finalizing " + item.getKey() + " after " + state.lastEdit + ": " + e.getMessage(), e);
                }
            }
            if (manifestDirty) {
                manifest.refreshFull();
                replacements.put("AndroidManifest.xml", ApkArchive.Replacement.bytes(manifest.getBytes()));
            }
            if (resourcesDirty) {
                resources.refresh();
                replacements.put("resources.arsc", ApkArchive.Replacement.bytes(resources.getBytes()));
            }
            return rebuilt;
        }
    }
}
