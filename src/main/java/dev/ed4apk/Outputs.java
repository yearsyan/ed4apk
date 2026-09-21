package dev.ed4apk;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Collectors;

final class Outputs {
    @FunctionalInterface interface Writer { void write(Path temporary) throws Exception; }

    static void write(Path input, Path output, boolean force, Writer writer) throws Exception {
        Path target = output.toAbsolutePath().normalize();
        if (input != null && (input.toAbsolutePath().normalize().equals(target)
                || (Files.exists(target) && Files.isSameFile(input, target)))) {
            throw new IOException("Output must differ from input: " + output);
        }
        if (!force && Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".ed4apk-", ".tmp");
        try {
            writer.write(temporary);
            if (!force && Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
            // A same-directory rename publishes only a complete output.
            if (force) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
        }
    }
}
