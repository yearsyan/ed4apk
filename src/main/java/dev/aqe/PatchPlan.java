package dev.aqe;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Strict JSON input: typos and ambiguous duplicate keys must not silently change a patch. */
final class PatchPlan {
    private final List<Operation> operations;
    private PatchPlan(List<Operation> operations) { this.operations = List.copyOf(operations); }
    List<Operation> operations() { return operations; }

    static PatchPlan deleteClasses(List<String> classes) {
        return deleteClasses(classes, false);
    }

    static PatchPlan deleteClasses(List<String> classes, boolean allowReferenced) {
        var value = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        value.put("op", "dex.delete");
        if (allowReferenced) value.put("allowReferenced", true);
        var names = value.putArray("classes");
        classes.forEach(names::add);
        return new PatchPlan(List.of(new Operation(value, Path.of(".").toAbsolutePath(), 1, "dex.delete")));
    }

    static PatchPlan read(Path file) throws IOException {
        var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        var mapper = JsonMapper.builder(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        JsonNode root = mapper.readTree(file.toFile());
        fields(root, Set.of("version", "operations"));
        if (!root.path("version").isIntegralNumber() || !root.path("version").canConvertToInt()
                || root.path("version").intValue() != 1)
            throw new IOException("Patch version must be the integer 1");
        JsonNode items = root.path("operations");
        if (!items.isArray() || items.isEmpty()) throw new IOException("operations must be a nonempty array");
        List<Operation> operations = new ArrayList<>();
        Path base = file.toAbsolutePath().normalize().getParent();
        for (JsonNode item : items) {
            int number = operations.size() + 1;
            try {
                String kind = text(item, "op");
                Set<String> allowed;
                switch (kind) {
                    case "file.add": case "file.replace": allowed = Set.of("op", "path", "source"); break;
                    case "file.delete": allowed = Set.of("op", "path"); break;
                    case "dex.add": allowed = Set.of("op", "inputs", "dex", "api", "libraries"); break;
                    case "dex.replace": allowed = Set.of("op", "inputs", "api", "libraries"); break;
                    case "dex.delete": allowed = Set.of("op", "classes", "allowReferenced"); break;
                    case "manifest.set": allowed = Set.of("op", "label", "versionName", "versionCode"); break;
                    case "resource.set-string": allowed = Set.of("op", "id", "config", "value"); break;
                    default: throw new IOException("Unknown operation: " + kind);
                }
                fields(item, allowed);
                operations.add(new Operation(item, base, number, kind));
            } catch (IOException e) {
                throw new IOException("Operation #" + number + ": " + e.getMessage(), e);
            }
        }
        return new PatchPlan(operations);
    }

    private static void fields(JsonNode node, Set<String> allowed) throws IOException {
        if (node == null || !node.isObject()) throw new IOException("Expected a JSON object");
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) throw new IOException("Unknown field: " + name);
        }
    }

    private static String text(JsonNode node, String name) throws IOException {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) throw new IOException(name + " must be a string");
        return value.textValue();
    }

    static final class Operation {
        private final JsonNode value;
        private final Path base;
        private final int number;
        private final String kind;
        Operation(JsonNode value, Path base, int number, String kind) {
            this.value = value;
            this.base = base;
            this.number = number;
            this.kind = kind;
        }
        String kind() { return kind; }
        String text(String name) throws IOException { return PatchPlan.text(value, name); }
        String optionalText(String name, String fallback) throws IOException {
            return value.has(name) ? text(name) : fallback;
        }
        Integer integer(String name) throws IOException {
            if (!value.has(name)) return null;
            JsonNode node = value.get(name);
            if (!node.isIntegralNumber() || !node.canConvertToInt())
                throw new IOException(name + " must be a 32-bit integer");
            return node.intValue();
        }
        boolean optionalFlag(String name) throws IOException {
            if (!value.has(name)) return false;
            JsonNode node = value.get(name);
            if (!node.isBoolean()) throw new IOException(name + " must be a boolean");
            return node.booleanValue();
        }
        List<String> strings(String name, boolean required) throws IOException {
            if (!required && !value.has(name)) return List.of();
            JsonNode nodes = value.get(name);
            if (nodes == null || !nodes.isArray() || (required && nodes.isEmpty()))
                throw new IOException(name + " must be " + (required ? "a nonempty" : "an") + " array of strings");
            List<String> result = new ArrayList<>();
            for (JsonNode node : nodes) {
                if (!node.isTextual() || node.textValue().isBlank())
                    throw new IOException(name + " must contain nonempty strings");
                result.add(node.textValue());
            }
            return result;
        }
        Path resolve(String name) throws IOException {
            if (name.isBlank()) throw new IOException("Input path must not be empty");
            return base.resolve(name).normalize();
        }
        List<Path> paths(String name, boolean required) throws IOException {
            List<Path> paths = new ArrayList<>();
            for (String item : strings(name, required)) paths.add(resolve(item));
            return paths;
        }
        String context() { return "Operation #" + number + " (" + kind + ")"; }
    }
}
