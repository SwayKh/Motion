package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** In-place migration coverage with real filesystem/crypto and a mocked database boundary. */
class MediaMigrationServiceTest {

    @TempDir
    Path tempDir;

    private MotionProperties properties;
    private StorageService storage;
    private MediaCrypto crypto;
    private MediaStore store;
    private MediaMigrationService service;

    @BeforeEach
    void setUp() {
        properties = new MotionProperties();
        properties.getStorage().setDataDir(tempDir.resolve("data").toString());
        properties.getStorage().setUploadDir(tempDir.resolve("uploads").toString());
        properties.getStorage().setOutputDir(tempDir.resolve("outputs").toString());
        properties.getStorage().setTempDir(tempDir.resolve("data/tmp").toString());
        properties.getStorage().setPreviewDir(tempDir.resolve("data/previews").toString());
        properties.getEncryption().setKey(MediaCrypto.generateMasterKey());

        storage = new StorageService(properties);
        storage.initialize();
        crypto = storage.crypto();
        store = mock(MediaStore.class);
        when(store.markEncryptedByPath(anyString(), anyString())).thenReturn(1);
        service = new MediaMigrationService(properties, storage, store);
    }

    @Test
    void dryRunReportsThePlanWithoutTouchingFilesOrDatabase() throws Exception {
        byte[] plaintext = "video payload for dry run".getBytes(StandardCharsets.UTF_8);
        Path video = writeVideo("dry-run.mp4", plaintext);
        stubPlainVideo(video, plaintext.length);
        byte[] before = Files.readAllBytes(video);

        MediaMigrationService.MigrationPlan plan = service.dryRun();

        assertThat(plan.candidates()).isEqualTo(1);
        assertThat(plan.paths()).containsExactly(video.toString());
        assertThat(plan.alreadyEncrypted()).isZero();
        assertThat(plan.active()).isZero();
        assertThat(Files.readAllBytes(video)).isEqualTo(before);
        assertThat(crypto.hasMagic(video)).isFalse();
        assertThat(listNames(properties.uploadDir())).containsExactly("dry-run.mp4");
        assertNoScratchFiles(video);
        verify(store, never()).markEncryptedByPath(anyString(), anyString());
    }

    @Test
    void encryptsInPlaceMarksOnlyThePathAndWrappedKey() throws Exception {
        byte[] plaintext = "video payload for migration".getBytes(StandardCharsets.UTF_8);
        Path video = writeVideo("video.mp4", plaintext);
        MediaRecord original = stubPlainVideo(video, plaintext.length);

        MediaMigrationService.MigrationResult result = service.encrypt();

        assertThat(result.encrypted()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(crypto.hasMagic(video)).isTrue();
        assertThat(Files.size(video))
                .isEqualTo(MediaCrypto.HEADER_SIZE + 4 + plaintext.length + 16);
        // The DB file_size semantics are untouched: only the flag and wrapped key are written.
        assertThat(original.fileSize()).isEqualTo(plaintext.length);

        ArgumentCaptor<String> wrappedKey = ArgumentCaptor.forClass(String.class);
        verify(store).markEncryptedByPath(eq(video.toString()), wrappedKey.capture());
        byte[] dek = crypto.unwrapDek(wrappedKey.getValue());
        try (EncryptedMediaSource view = crypto.openEncrypted(video, dek)) {
            assertThat(view.size()).isEqualTo(plaintext.length);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            MediaCrypto.copy(view, stream, null);
            assertThat(stream.toByteArray()).isEqualTo(plaintext);
        }

        assertThat(listNames(properties.uploadDir())).containsExactly("video.mp4");
        assertNoScratchFiles(video);
        verify(store, never()).insert(any(MediaRecord.class));
        verify(store, never()).delete(anyString());
    }

    @Test
    void wrongEncryptedFlagSkipsPlaintextFile() throws Exception {
        byte[] plaintext = "wrong flag should not encrypt this".getBytes(StandardCharsets.UTF_8);
        Path video = writeVideo("flagged.mp4", plaintext);
        MediaRecord flagged = record(video, plaintext.length, true, "stale-key");
        when(store.listAllVideos()).thenReturn(List.of(flagged));
        when(store.listAllJobArtifacts()).thenReturn(List.of());
        when(store.hasActiveJobs("video-1")).thenReturn(false);
        byte[] before = Files.readAllBytes(video);

        MediaMigrationService.MigrationResult result = service.encrypt();

        assertThat(result.encrypted()).isZero();
        assertThat(Files.readAllBytes(video)).isEqualTo(before);
        assertThat(crypto.hasMagic(video)).isFalse();
        assertNoScratchFiles(video);
        verify(store, never()).markEncryptedByPath(anyString(), anyString());
    }

    @Test
    void alreadyEncryptedFileIsNotDoubleEncrypted() throws Exception {
        byte[] plaintext = "already encrypted payload".getBytes(StandardCharsets.UTF_8);
        Path source = tempDir.resolve("pre-encrypted-source.mp4");
        Files.write(source, plaintext);
        Path video = properties.uploadDir().resolve("already.mp4");
        crypto.encryptFile(source, video, crypto.newDek());
        byte[] before = Files.readAllBytes(video);
        stubPlainVideo(video, plaintext.length);

        MediaMigrationService.MigrationPlan plan = service.plan();
        MediaMigrationService.MigrationResult result = service.encrypt();

        assertThat(plan.paths()).isEmpty();
        assertThat(plan.alreadyEncrypted()).isEqualTo(1);
        assertThat(result.encrypted()).isZero();
        assertThat(Files.readAllBytes(video)).isEqualTo(before);
        assertNoScratchFiles(video);
        verify(store, never()).markEncryptedByPath(anyString(), anyString());
    }

    @Test
    void recoverCompletesAnInterruptedEncryptionFromItsManifest() throws Exception {
        byte[] plaintext = "resumable migration payload".getBytes(StandardCharsets.UTF_8);
        Path target = writeVideo("resume.mp4", plaintext);
        byte[] dek = crypto.newDek();
        String wrapped = crypto.wrapDek(dek);
        Path encryptedTemp = Path.of(target + ".enc");
        crypto.encryptFile(target, encryptedTemp, dek);
        Path manifest = writeManifest(target, wrapped, crypto.sha256(target));
        Path lock = Path.of(target + ".migration.lock");

        MediaMigrationService.RecoveryResult result = service.recover();

        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.discarded()).isZero();
        assertThat(result.leftUnrecognized()).isZero();
        assertThat(result.plaintextTempsRemoved()).isZero();
        assertThat(Files.exists(encryptedTemp)).isFalse();
        assertThat(Files.exists(manifest)).isFalse();
        assertThat(Files.exists(lock)).isFalse();
        assertThat(crypto.hasMagic(target)).isTrue();
        try (EncryptedMediaSource view = crypto.openEncrypted(target, dek)) {
            assertThat(view.size()).isEqualTo(plaintext.length);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            MediaCrypto.copy(view, stream, null);
            assertThat(stream.toByteArray()).isEqualTo(plaintext);
        }
        verify(store).markEncryptedByPath(target.toString(), wrapped);
    }

    @Test
    void recoverDeletesPlaintextTempsAndLeavesUnknownEncFilesUntouched() throws Exception {
        Path orphan = writeVideo("orphan.bin.enc", "not a MOTIONE1 file".getBytes(StandardCharsets.UTF_8));
        Path dec = writeVideo("leftover.mp4.dec", "plaintext scratch".getBytes(StandardCharsets.UTF_8));
        byte[] orphanBefore = Files.readAllBytes(orphan);

        MediaMigrationService.RecoveryResult result = service.recover();

        assertThat(result.completed()).isZero();
        assertThat(result.discarded()).isZero();
        assertThat(result.leftUnrecognized()).isEqualTo(1);
        assertThat(result.plaintextTempsRemoved()).isEqualTo(1);
        assertThat(Files.readAllBytes(orphan)).isEqualTo(orphanBefore);
        assertThat(Files.exists(dec)).isFalse();
        assertThat(listNames(properties.uploadDir())).containsExactly("orphan.bin.enc");
        assertNoScratchFiles(orphan);
        verify(store, never()).markEncryptedByPath(anyString(), anyString());
    }

    @Test
    void activeVideoJobsAreSkipped() throws Exception {
        byte[] plaintext = "active video payload".getBytes(StandardCharsets.UTF_8);
        Path video = writeVideo("active.mp4", plaintext);
        when(store.listAllVideos()).thenReturn(List.of(record(video, plaintext.length, false, null)));
        when(store.listAllJobArtifacts()).thenReturn(List.of());
        when(store.hasActiveJobs("video-1")).thenReturn(true);
        byte[] before = Files.readAllBytes(video);

        MediaMigrationService.MigrationPlan plan = service.plan();
        MediaMigrationService.MigrationResult result = service.encrypt();

        assertThat(plan.paths()).isEmpty();
        assertThat(plan.active()).isGreaterThanOrEqualTo(1);
        assertThat(result.encrypted()).isZero();
        assertThat(Files.readAllBytes(video)).isEqualTo(before);
        assertNoScratchFiles(video);
        verify(store, never()).markEncryptedByPath(anyString(), anyString());
    }

    @Test
    void activeJobArtifactsAreSkipped() throws Exception {
        byte[] plaintext = "active job output payload".getBytes(StandardCharsets.UTF_8);
        Path output = writeVideo("job-output.mp4", plaintext);
        when(store.listAllVideos())
                .thenReturn(List.of(record(output, plaintext.length, true, "already-encrypted")));
        when(store.listAllJobArtifacts())
                .thenReturn(List.of(new MediaStore.JobArtifact("job-1", output.toString(), "processing", false)));
        when(store.isActiveJob("job-1")).thenReturn(true);
        byte[] before = Files.readAllBytes(output);

        MediaMigrationService.MigrationPlan plan = service.plan();
        MediaMigrationService.MigrationResult result = service.encrypt();

        assertThat(plan.paths()).isEmpty();
        assertThat(plan.active()).isEqualTo(1);
        assertThat(result.encrypted()).isZero();
        assertThat(Files.readAllBytes(output)).isEqualTo(before);
        assertNoScratchFiles(output);
        verify(store, never()).markEncryptedByPath(anyString(), anyString());
    }

    @Test
    void encryptionDisabledFailsBeforeAnyMigration() {
        MotionProperties disabledProperties = new MotionProperties();
        disabledProperties.getStorage().setDataDir(tempDir.resolve("disabled-data").toString());
        disabledProperties.getStorage().setUploadDir(tempDir.resolve("disabled-uploads").toString());
        disabledProperties.getStorage().setOutputDir(tempDir.resolve("disabled-outputs").toString());
        disabledProperties.getEncryption().setKey("");
        StorageService disabledStorage = new StorageService(disabledProperties);
        disabledStorage.initialize();
        MediaMigrationService disabledService =
                new MediaMigrationService(disabledProperties, disabledStorage, store);

        assertThatThrownBy(disabledService::encrypt)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MOTION_ENCRYPTION_KEY must be set to migrate media");
        verify(store, never()).markEncryptedByPath(anyString(), anyString());
    }

    private Path writeVideo(String name, byte[] content) throws IOException {
        return Files.write(properties.uploadDir().resolve(name), content);
    }

    private MediaRecord stubPlainVideo(Path video, long fileSize) {
        MediaRecord record = record(video, fileSize, false, null);
        when(store.listAllVideos()).thenReturn(List.of(record));
        when(store.listAllJobArtifacts()).thenReturn(List.of());
        when(store.hasActiveJobs("video-1")).thenReturn(false);
        return record;
    }

    private static MediaRecord record(Path path, long fileSize, boolean encrypted, String wrappedKey) {
        return new MediaRecord("video-1", path.toString(), path.getFileName().toString(), fileSize,
                null, "completed", 1.0, "[]", 1.0, 1920, 1080, null, "2026-01-01T00:00:00Z",
                "user-1", encrypted, wrappedKey, false, null);
    }

    private Path writeManifest(Path target, String wrappedKey, String digest) throws Exception {
        String targetPath = target.toAbsolutePath().normalize().toString();
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(targetPath.getBytes(StandardCharsets.UTF_8));
        Path directory = properties.dataDir().resolve("migration");
        Files.createDirectories(directory);
        Path manifest = directory.resolve(HexFormat.of().formatHex(hash) + ".properties");

        Properties data = new Properties();
        data.setProperty("target", targetPath);
        data.setProperty("wrappedKey", wrappedKey);
        data.setProperty("digest", digest);
        data.setProperty("version", "1");
        try (var output = Files.newOutputStream(manifest, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            data.store(output, "Motion migration recovery metadata");
        }
        return manifest;
    }

    private void assertNoScratchFiles(Path video) throws IOException {
        assertThat(Files.exists(Path.of(video + ".enc"))).isFalse();
        assertThat(Files.exists(Path.of(video + ".dec"))).isFalse();
        assertThat(Files.exists(Path.of(video + ".migration.lock"))).isFalse();
        Path migrationDir = properties.dataDir().resolve("migration");
        if (Files.isDirectory(migrationDir)) {
            assertThat(listNames(migrationDir)).isEmpty();
        }
    }

    private static List<String> listNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
