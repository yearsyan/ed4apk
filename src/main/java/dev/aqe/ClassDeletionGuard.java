package dev.aqe;

import java.io.IOException;
import java.util.*;

/** Deletion policy consumes the same reference locations as query and rename. */
final class ClassDeletionGuard {
    private final Map<String, String> deleted;
    private final List<String> details = new ArrayList<>();
    private long count;
    ClassDeletionGuard(Map<String, String> deleted) { this.deleted = deleted; }
    void accept(ClassReferences.Hit hit) {
        if (hit.definition) return;
        count++;
        if (details.size() < 30)
            details.add("  " + hit.target + " (" + deleted.get(hit.target) + ") <- " + hit.location);
    }
    void requireNoReferences() throws IOException {
        if (count == 0) return;
        String remaining = count > details.size() ? "\n  ... " + (count - details.size()) + " more reference(s)" : "";
        throw new IOException("Refusing class deletion: " + count + " remaining direct reference(s).\n"
                + String.join("\n", details) + remaining
                + "\nUpdate/remove the referring classes or XML entries in the same patch, or keep the target classes. No output published.");
    }
}
