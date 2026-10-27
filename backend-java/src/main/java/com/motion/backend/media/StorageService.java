package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Filesystem boundary for media files.
 *
 * <p>The directory layout matches the reference: {@code uploads/<uuid><ext>}, outputs under {@code outputs/},
 * scratch files under {@code data/tmp}. Every read or delete path is checked against the managed
 * upload/output roots before use. When a master key is configured, uploads are encrypted with the
 * application {@code MOTIONE1} format before final placement.</p>
 */
@Component
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final MotionProperties properties;
    private final MediaCrypto crypto;

    @Autowired
    public StorageService(MotionProperties properties, MediaCrypto crypto) {
        this.properties = properties;
        this.crypto = crypto;
    }

    /** Keeps direct construction in unit tests simple; key validation stays in {@link MediaCrypto}. */
    public StorageService(MotionProperties properties) {
        this(properties, new MediaCrypto(properties));
    }

    @PostConstruct
    void initialize() {
        ensureDirectory(properties.dataDir(), "rwxr-xr-x");
        ensureDirectory(properties.uploadDir(), "rwxr-xr-x");
        ensureDirectory(properties.outputDir(), "rwxr-xr-x");
        ensureDirectory(properties.tempDir(), "rwx------");
        ensureDirectory(properties.previewDir(), "rwxr-xr-x");
        sweepTempFiles();
    }

    public Path uploadDir() {
        return properties.uploadDir();
    }

    public Path outputDir() {
        return properties.outputDir();
    }

    /** Root used for private processing scratch, decrypt staging, and upload temp files. */
    public Path tempDir() {
        return properties.tempDir();
    }

    public MediaCrypto crypto() {
        return crypto;
    }

    /**
     * Containment for stored media: both the lexical path and the filesystem-resolved real
     * path (symlinks followed) must stay inside the managed upload/output roots. A candidate
     * that does not exist on disk is not contained. This prevents symlinked files or
     * directories from escaping the storage roots.
     */
    public boolean contains(Path candidate) {
        if (candidate == null) {
            return false;
        }
        return containedUnderRoot(candidate.toAbsolutePath().normalize(),
                properties.uploadDir(), properties.outputDir());
    }

    public boolean containsTemp(Path candidate) {
        if (candidate == null) {
            return false;
        }
        return containedUnderRoot(candidate.toAbsolutePath().normalize(), properties.tempDir());
    }

    /** Job cleanup is restricted to outputs; uploads and user originals are never job artifacts. */
    public boolean containsOutput(Path candidate) {
        if (candidate == null) {
            return false;
        }
        return containedUnderRoot(candidate.toAbsolutePath().normalize(), properties.outputDir());
    }

    /**
     * Returns true only when the absolute candidate is lexically inside one of the roots and its
     * location on the real filesystem (symlinks followed) stays inside that root. When the
     * candidate does not exist yet (a write destination), the nearest existing ancestor must
     * really be inside the root; nonexistent path components have no filesystem presence and
     * cannot redirect the final location.
     */
    private static boolean containedUnderRoot(Path absolute, Path... roots) {
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path rootAbsolute = root.toAbsolutePath().normalize();
            if (!absolute.startsWith(rootAbsolute)) {
                continue;
            }
            try {
                Path rootReal = rootAbsolute.toRealPath();
                return absolute.toRealPath().startsWith(rootReal);
            } catch (IOException notOnDisk) {
                Path existing = absolute;
                while (existing != null && !Files.exists(existing)) {
                    existing = existing.getParent();
                }
                if (existing == null) {
                    return false;
                }
                try {
                    Path rootReal = rootAbsolute.toRealPath();
                    return existing.toRealPath().startsWith(rootReal);
                } catch (IOException exception) {
                    return false;
                }
            }
        }
        return false;
    }

    public Path newTempFile(String prefix, String suffix) throws IOException {
        ensureDirectory(properties.tempDir(), "rwx------");
        return Files.createTempFile(properties.tempDir(), prefix, suffix);
    }

    /**
     * Places a scratch file in {@code uploads/}, encrypting it into a temporary ciphertext file
     * first when a key is configured. The caller owns the plaintext scratch file and the returned
     * final path until its database row is persisted.
     */
    public StoredUpload storeUpload(Path tempFile, String filename) throws IOException {
        ensureDirectory(properties.uploadDir(), "rwxr-xr-x");
        Path destination = properties.uploadDir().resolve(filename).normalize();
        if (!inside(properties.uploadDir(), destination.toAbsolutePath().normalize())) {
            throw new IOException("storage path escapes the upload directory");
        }

        if (!crypto.enabled()) {
            moveIntoPlace(tempFile, destination);
            return new StoredUpload(destination, false, null);
        }

        byte[] dek = null;
        Path ciphertextTemp = null;
        try {
            dek = crypto.newDek();
            ciphertextTemp = newTempFile("encrypt-", ".bin");
            crypto.encryptFile(tempFile, ciphertextTemp, dek);
            // Go keeps the probe scratch until the final move; we can remove it as soon as the
            // ciphertext is complete, so no plaintext remains in data/tmp.
            Files.deleteIfExists(tempFile);
            String wrappedKey = crypto.wrapDek(dek);
            moveIntoPlace(ciphertextTemp, destination);
            ciphertextTemp = null;
            return new StoredUpload(destination, true, wrappedKey);
        } finally {
            Arrays.fill(dek == null ? new byte[0] : dek, (byte) 0);
            if (ciphertextTemp != null) {
                Files.deleteIfExists(ciphertextTemp);
            }
        }
    }

    public boolean remove(Path path) {
        if (path == null || !contains(path)) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("best-effort media removal failed for {}: {}", path, exception.getMessage());
            return false;
        }
    }

    public void removeQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        remove(Path.of(path));
    }

    /** the startup temp sweep removes top-level scratch files older than the retention window. */
    private void sweepTempFiles() {
        Path tempDir = properties.tempDir();
        long cutoff = Instant.now().minus(properties.retention()).toEpochMilli();
        if (!Files.isDirectory(tempDir)) {
            return;
        }
        try (Stream<Path> entries = Files.list(tempDir)) {
            for (Path path : entries.toList()) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException exception) {
                    log.warn("temp sweep skipped {}: {}", path, exception.getMessage());
                }
            }
        } catch (IOException exception) {
            log.warn("temp sweep failed for {}: {}", tempDir, exception.getMessage());
        }
    }

    private static void moveIntoPlace(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean inside(Path root, Path candidate) {
        Path rootAbsolute = root.toAbsolutePath().normalize();
        return candidate.startsWith(rootAbsolute);
    }

    private static void ensureDirectory(Path path, String posixMode) {
        try {
            Files.createDirectories(path);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixMode));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystems keep the platform defaults.
        } catch (IOException exception) {
            throw new IllegalStateException("could not create directory " + path, exception);
        }
    }
}
