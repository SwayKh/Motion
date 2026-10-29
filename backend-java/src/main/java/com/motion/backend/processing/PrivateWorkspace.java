package com.motion.backend.processing;

import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.StorageService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.FileAttribute;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Unique owner-only scratch directory for one processing run.
 *
 * <p>Encrypted media is decrypted here only for the lifetime of the FFmpeg process. Plaintext
 * legacy media is read in place because no plaintext copy is necessary. The output part file is
 * always created here and is moved or encrypted into final storage after verification.</p>
 */
public final class PrivateWorkspace implements AutoCloseable {

    private final Path root;
    private final StorageService storage;

    private PrivateWorkspace(Path root, StorageService storage) {
        this.root = root;
        this.storage = storage;
    }

    public static PrivateWorkspace create(StorageService storage) {
        try {
            Path tempRoot = storage.tempDir();
            Files.createDirectories(tempRoot);
            Files.setPosixFilePermissions(tempRoot,
                    PosixFilePermissions.fromString("rwx------"));
            Path root = Files.createTempDirectory(tempRoot, "processing-",
                    new FileAttribute<?>[] {
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")) });
            return new PrivateWorkspace(root, storage);
        } catch (UnsupportedOperationException ignored) {
            try {
                return new PrivateWorkspace(Files.createTempDirectory("processing-"), storage);
            } catch (IOException exception) {
                throw new ProcessingException("could not create processing workspace", exception);
            }
        } catch (IOException exception) {
            throw new ProcessingException("could not create processing workspace", exception);
        }
    }

    public Path root() {
        return root;
    }

    /** Materializes a file FFmpeg can read, decrypting private plaintext only when needed. */
    public Path materialize(MediaRecord source, String role) {
        Path original = Path.of(source.originalPath());
        if (!source.encrypted()) {
            if (!Files.isRegularFile(original)) {
                throw new ProcessingException("source file is no longer available");
            }
            return original;
        }
        byte[] dek = null;
        try {
            dek = storage.crypto().unwrapDek(source.encKey());
            Path plain = newFile(role + "-", suffixOf(original));
            storage.crypto().decryptFile(original, plain, dek);
            return plain;
        } catch (Exception exception) {
            throw new ProcessingException("source file is no longer available", exception);
        } finally {
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
        }
    }

    public Path newFile(String prefix, String suffix) {
        try {
            Path file = Files.createTempFile(root, prefix, suffix);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return file;
        } catch (UnsupportedOperationException ignored) {
            try {
                return Files.createTempFile(root, prefix, suffix);
            } catch (IOException exception) {
                throw new ProcessingException("could not create processing scratch file", exception);
            }
        } catch (IOException exception) {
            throw new ProcessingException("could not create processing scratch file", exception);
        }
    }

    public Path write(String filename, byte[] content) {
        Path file = resolve(filename);
        try {
            Files.write(file, content);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return file;
        } catch (UnsupportedOperationException ignored) {
            try {
                return Files.write(file, content);
            } catch (IOException exception) {
                throw new ProcessingException("could not write processing scratch file", exception);
            }
        } catch (IOException exception) {
            throw new ProcessingException("could not write processing scratch file", exception);
        }
    }

    public Path resolve(String filename) {
        Path candidate = root.resolve(filename).normalize();
        if (!candidate.startsWith(root)) {
            throw new ProcessingException("invalid processing scratch path");
        }
        return candidate;
    }

    @Override
    public void close() {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup; the next startup sweep also removes stale scratch.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static String suffixOf(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : ".bin";
    }
}
