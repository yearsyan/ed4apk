package dev.ed4apk;

import java.util.*;

/** Stable, dependency-free result shapes shared by read-only commands. */
final class Inspection {
    private Inspection() {}

    static LinkedHashMap<String, Object> object(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static Map<String, Object> diagnostic(String code, String entry, String message) {
        return object("code", code, "entry", entry, "message", message);
    }

    static Map<String, Object> report(Object data, List<Map<String, Object>> diagnostics) {
        return object("schemaVersion", 1, "data", data, "diagnostics", diagnostics);
    }

    static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /** Escape even line breaks in names and diagnostics; never emit terminal escape sequences. */
    static String safeText(String value) { return escape(value, false); }
    static String safeContent(String value) { return escape(value, true); }

    private static String escape(String value, boolean multiline) {
        if (value == null) return "null";
        StringBuilder text = new StringBuilder();
        value.codePoints().forEach(c -> {
            if (multiline && (c == '\n' || c == '\t')) text.appendCodePoint(c);
            else if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || c == 0x2028 || c == 0x2029) text.append(String.format(Locale.ROOT, "\\u%04x", c));
            else text.appendCodePoint(c);
        });
        return text.toString();
    }

    static void printDiagnostics(List<Map<String, Object>> diagnostics) {
        for (var diagnostic : diagnostics) System.err.println(safeText(
                diagnostic.get("code") + ": " + diagnostic.get("entry") + ": " + diagnostic.get("message")));
    }
}
