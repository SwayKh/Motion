package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resumable plaintext-to-{@code MOTIONE1} migration for existing media.
 *
 * <p>The original is replaced only after the encrypted temporary file has been fully decrypted and
 * compared by streaming SHA-256. A private manifest contains only the wrapped DEK, so an interrupted
 * encryption can be completed or discarded safely. No plaintext replacement file is ever created.</p>
 */
@Component
public class MediaMigrationService {

    private static final Logger log = LoggerFactory.getLogger(MediaMigrationService.class);
    private static final String ENC_SUFFIX = ".enc";
    private static final String DEC_SUFFIX = ".dec";
    private static final String LOCK_SUFFIX = ".migration.lock";
    private static final String MANIFEST_DIRECTORY = "migration";

    public record MigrationPlan(int candidates, int alreadyEncrypted, int missing, int active,
                                List<String> paths) {
    }

    public record MigrationResult(int encrypted, int skipped, int failed) {
    }

    public record RecoveryResult(int completed, int discarded, int leftUnrecognized, int plaintextTempsRemoved) {
    }

    private final MotionProperties properties;
    private final StorageService storage;
    private final MediaStore store;
    private final MediaCrypto crypto;

    public MediaMigrationService(MotionProperties properties, StorageService storage, MediaStore store) {
        this.properties = properties;
        this.storage = storage;
        this.store = store;
        this.crypto = storage.crypto();
    }

    public MigrationPlan plan() {
        int already = 0;
        int missing = 0;
        int active = 0;
        Set<String> paths = new LinkedHashSet<>();

        for (MediaRecord video : store.listAllVideos()) {
            boolean videoActive = store.hasActiveJobs(video.id());
            if (!video.encrypted()) {
                Classification result = classify(video.originalPath(), videoActive, paths);
                already += result == Classification.ALREADY_ENCRYPTED ? 1 : 0;
                missing += result == Classification.MISSING ? 1 : 0;
                active += result == Classification.ACTIVE ? 1 : 0;
            }
            if (!video.outputEncrypted()) {
                Classification result = classify(video.outputPath(), videoActive, paths);
                already += result == Classification.ALREADY_ENCRYPTED ? 1 : 0;
                missing += result == Classification.MISSING ? 1 : 0;
                active += result == Classification.ACTIVE ? 1 : 0;
            }
        }
        for (MediaStore.JobArtifact job : store.listAllJobArtifacts()) {
            if (!job.encrypted()) {
                boolean jobActive = isActiveStatus(job.status()) || store.isActiveJob(job.id());
                Classification result = classify(job.outputPath(), jobActive, paths);
                already += result == Classification.ALREADY_ENCRYPTED ? 1 : 0;
                missing += result == Classification.MISSING ? 1 : 0;
                active += result == Classification.ACTIVE ? 1 : 0;
            }
        }
        return new MigrationPlan(paths.size(), already, missing, active, List.copyOf(paths));
    }

    /** Dry run: computes the plan without writing any media or database state. */
    public MigrationPlan dryRun() {
        return plan();
    }

    public MigrationResult encrypt() {
        requireEncryption();
        recover();
        MigrationPlan plan = plan();
        int encrypted = 0;
        int skipped = 0;
        int failed = 0;
        for (String path : plan.paths()) {
            switch (encryptPath(Path.of(path))) {
                case ENCRYPTED -> encrypted++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        log.info("media migration: {} encrypted, {} skipped, {} failed", encrypted, skipped, failed);
        return new MigrationResult(encrypted, skipped, failed);
    }

    public RecoveryResult recover() {
        int completed = 0;
        int discarded = 0;
        int leftUnrecognized = 0;
        int plaintextTempsRemoved = 0;

        for (Path root : List.of(storage.uploadDir(), storage.outputDir())) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(root)) {
                for (Path path : entries.toList()) {
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    String name = path.getFileName().toString();
                    if (name.endsWith(ENC_SUFFIX)) {
                        switch (recoverEncryptedTemp(path)) {
                            case COMPLETED -> completed++;
                            case DISCARDED -> discarded++;
                            case LEFT_UNRECOGNIZED -> leftUnrecognized++;
                        }
                    } else if (name.endsWith(DEC_SUFFIX)) {
                        try {
                            Files.deleteIfExists(path);
                            plaintextTempsRemoved++;
                        } catch (IOException exception) {
                            log.warn("could not remove plaintext migration scratch file");
                        }
                    }
                }
            } catch (IOException exception) {
                log.warn("could not scan {} for interrupted migration files", root);
            }
        }
        if (completed + discarded + leftUnrecognized + plaintextTempsRemoved > 0) {
            log.info("migration recovery: {} completed, {} discarded, {} unrecognized, {} plaintext temps removed",
                    completed, discarded, leftUnrecognized, plaintextTempsRemoved);
        }
        return new RecoveryResult(completed, discarded, leftUnrecognized, plaintextTempsRemoved);
    }

    private Classification classify(String path, boolean active, Set<String> candidates) {
        if (active) {
            return Classification.ACTIVE;
        }
        if (path == null || path.isBlank()) {
            return Classification.MISSING;
        }
        Path resolved = Path.of(path).toAbsolutePath().normalize();
        if (!storage.contains(resolved) || !Files.isRegularFile(resolved)) {
            return Classification.MISSING;
        }
        try {
            if (crypto.hasMagic(resolved)) {
                return Classification.ALREADY_ENCRYPTED;
            }
            // Preserve the exact DB path spelling (often relative to the working directory) so
            // markEncryptedByPath updates the rows that actually reference this file.
            candidates.add(path);
            return Classification.CANDIDATE;
        } catch (IOException exception) {
            return Classification.MISSING;
        }
    }

    private Outcome encryptPath(Path path) {
        Path lock = Path.of(path + LOCK_SUFFIX);
        Path encryptedTemp = Path.of(path + ENC_SUFFIX);
        Path manifest = manifestPath(path);
        boolean moved = false;
        try {
            try (FileChannel lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock ignored = lockChannel.lock()) {
                if (!Files.isRegularFile(path) || crypto.hasMagic(path) || Files.exists(encryptedTemp)) {
                    return Outcome.SKIPPED;
                }
                String originalDigest = crypto.sha256(path);
                byte[] dek = crypto.newDek();
                String wrapped = crypto.wrapDek(dek);
                writeManifest(manifest, path, wrapped, originalDigest);
                crypto.encryptFile(path, encryptedTemp, dek);
                verify(encryptedTemp, dek, originalDigest, Files.size(path));
                copyAttributes(path, encryptedTemp);
                moveIntoPlace(encryptedTemp, path);
                moved = true;
                store.markEncryptedByPath(path.toString(), wrapped);
                Files.deleteIfExists(manifest);
                Arrays.fill(dek, (byte) 0);
                return Outcome.ENCRYPTED;
            }
        } catch (Exception exception) {
            log.error("media migration failed for {}", safeName(path));
            if (!moved) {
                deleteQuietly(encryptedTemp);
                deleteQuietly(manifest);
            }
            return Outcome.FAILED;
        } finally {
            deleteQuietly(lock);
        }
    }

    private RecoveryOutcome recoverEncryptedTemp(Path encryptedTemp) {
        String targetName = encryptedTemp.getFileName().toString();
        Path target = encryptedTemp.resolveSibling(
                targetName.substring(0, targetName.length() - ENC_SUFFIX.length()));
        Path lock = Path.of(target + LOCK_SUFFIX);
        Path manifest = manifestPath(target);

        try (FileChannel lockChannel = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock acquired = lockChannel.tryLock()) {
            if (acquired == null) {
                return RecoveryOutcome.LEFT_UNRECOGNIZED;
            }
            if (!Files.isRegularFile(encryptedTemp) || !crypto.hasMagic(encryptedTemp)
                    || !Files.exists(manifest)) {
                if (Files.exists(manifest)) {
                    deleteQuietly(manifest);
                }
                return RecoveryOutcome.LEFT_UNRECOGNIZED;
            }
            RecoveryManifest data = readManifest(manifest);
            if (data == null || !Path.of(data.target()).toAbsolutePath().normalize().equals(target)) {
                deleteQuietly(manifest);
                return RecoveryOutcome.LEFT_UNRECOGNIZED;
            }

            if (!Files.isRegularFile(target) || crypto.hasMagic(target)) {
                deleteQuietly(encryptedTemp);
                deleteQuietly(manifest);
                return RecoveryOutcome.DISCARDED;
            }

            byte[] dek = crypto.unwrapDek(data.wrappedKey());
            String originalDigest = crypto.sha256(target);
            verify(encryptedTemp, dek, originalDigest, Files.size(target));
            copyAttributes(target, encryptedTemp);
            moveIntoPlace(encryptedTemp, target);
            store.markEncryptedByPath(target.toString(), data.wrappedKey());
            deleteQuietly(manifest);
            return RecoveryOutcome.COMPLETED;
        } catch (Exception exception) {
            log.error("migration recovery failed for {}", safeName(encryptedTemp));
            return RecoveryOutcome.LEFT_UNRECOGNIZED;
        } finally {
            deleteQuietly(lock);
        }
    }

    private void verify(Path encryptedTemp, byte[] dek, String expectedDigest, long expectedLength)
            throws IOException {
        try (EncryptedMediaSource decrypted = crypto.openEncrypted(encryptedTemp, dek)) {
            if (decrypted.size() != expectedLength) {
                throw new MediaEncryptionException("encrypted replacement length does not match the original");
            }
            String actual = crypto.sha256(decrypted, decrypted.size());
            if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    expectedDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw new MediaEncryptionException("encrypted replacement content does not match the original");
            }
        }
    }

    private Path manifestPath(Path target) {
        String fingerprint;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(target.toAbsolutePath().normalize().toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : hash) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            fingerprint = result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        Path directory = properties.dataDir().resolve(MANIFEST_DIRECTORY);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("could not create migration manifest directory", exception);
        }
        return directory.resolve(fingerprint + ".properties");
    }

    private void writeManifest(Path manifest, Path target, String wrappedKey, String digest) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("target", target.toAbsolutePath().normalize().toString());
        properties.setProperty("wrappedKey", wrappedKey);
        properties.setProperty("digest", digest);
        properties.setProperty("version", "1");
        try (OutputStream output = Files.newOutputStream(manifest, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(output, "Motion migration recovery metadata");
        }
        try {
            Files.setPosixFilePermissions(manifest,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Platform default is used when POSIX permissions are unavailable.
        }
    }

    private RecoveryManifest readManifest(Path path) {
        Properties loaded = new Properties();
        try (java.io.InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            loaded.load(input);
            String target = loaded.getProperty("target");
            String wrappedKey = loaded.getProperty("wrappedKey");
            String digest = loaded.getProperty("digest");
            if (target == null || wrappedKey == null || digest == null
                    || !"1".equals(loaded.getProperty("version"))) {
                return null;
            }
            return new RecoveryManifest(target, wrappedKey, digest);
        } catch (IOException exception) {
            return null;
        }
    }

    private record RecoveryManifest(String target, String wrappedKey, String digest) {
    }

    private static void copyAttributes(Path source, Path destination) {
        try {
            Files.setPosixFilePermissions(destination, Files.getPosixFilePermissions(source));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems keep the secure temp permissions.
        }
        try {
            Files.setOwner(destination, Files.getOwner(source));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Ownership changes require privileges; permissions remain preserved.
        }
        try {
            Files.setLastModifiedTime(destination, Files.getLastModifiedTime(source));
        } catch (IOException ignored) {
            // Best effort.
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

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static boolean isActiveStatus(String status) {
        return "queued".equals(status) || "processing".equals(status) || "cancel_requested".equals(status);
    }

    private void requireEncryption() {
        if (!crypto.enabled()) {
            throw new IllegalStateException("MOTION_ENCRYPTION_KEY must be set to migrate media");
        }
    }

    private static String safeName(Path path) {
        String name = path.getFileName() == null ? "unknown" : path.getFileName().toString();
        return name.endsWith(ENC_SUFFIX) || name.endsWith(DEC_SUFFIX)
                ? name : name + " (migration)";
    }

    private enum Classification { CANDIDATE, ALREADY_ENCRYPTED, MISSING, ACTIVE }

    private enum Outcome { ENCRYPTED, SKIPPED, FAILED }

    private enum RecoveryOutcome { COMPLETED, DISCARDED, LEFT_UNRECOGNIZED }
}
