package com.motion.backend.processing;

import com.motion.backend.media.StorageService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;

/** Atomically places a verified plaintext part file into encrypted or plain storage. */
@Component
public class MediaOutputWriter {

    private final StorageService storage;

    public MediaOutputWriter(StorageService storage) {
        this.storage = storage;
    }

    public StoredOutput store(Path plaintextPart, String jobId, String extension) {
        try {
            long size = Files.size(plaintextPart);
            if (size <= 0) {
                throw new ProcessingException("video processing produced no output");
            }
            Files.createDirectories(storage.outputDir());
            Path finalPath = storage.outputDir().resolve(jobId + extension).normalize();
            if (!storage.contains(finalPath)) {
                throw new ProcessingException("invalid processing output path");
            }
            Path staged = Files.createTempFile(storage.outputDir(), ".output-", ".bin");
            try {
                restrict(staged);
                boolean encrypted = storage.crypto().enabled();
                String wrappedKey = null;
                if (encrypted) {
                    byte[] dek = storage.crypto().newDek();
                    try {
                        storage.crypto().encryptFile(plaintextPart, staged, dek);
                        wrappedKey = storage.crypto().wrapDek(dek);
                    } finally {
                        Arrays.fill(dek, (byte) 0);
                    }
                } else {
                    Files.copy(plaintextPart, staged, StandardCopyOption.REPLACE_EXISTING);
                }
                moveIntoPlace(staged, finalPath);
                staged = null;
                return new StoredOutput(finalPath.toString(), encrypted, wrappedKey, size);
            } finally {
                if (staged != null) {
                    Files.deleteIfExists(staged);
                }
            }
        } catch (ProcessingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ProcessingException("saving processing output failed", exception);
        }
    }

    public record StoredOutput(String path, boolean encrypted, String wrappedKey, long size) {
    }

    private static void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Temp files already use restrictive platform defaults.
        }
    }
}
