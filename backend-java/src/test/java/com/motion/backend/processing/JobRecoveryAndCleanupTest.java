package com.motion.backend.processing;

import com.motion.backend.processing.JobState;
import com.motion.backend.time.LegacyTimestamp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic recovery, retention, and ownership-safeguard coverage on a disposable database. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=test",
                "motion.jobs.worker-count=1",
                "motion.jobs.poll-interval=1h"
        }
)
class JobRecoveryAndCleanupTest {

    private static final String VALID_OPERATIONS =
            "{\"operations\":[{\"type\":\"flip\",\"orientation\":1}]}";

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("recovery.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AsyncJobManager manager;
    @Autowired
    private JobRecoveryService recovery;
    @Autowired
    private JobCleanupService cleanup;

    @BeforeEach
    void freezeWorkersAndCleanTables() {
        manager.stop();
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM videos");
    }

    @AfterEach
    void leaveWorkersStopped() {
        // This context is cached by Spring while JUnit removes the @TempDir database. Keeping
        // workers stopped avoids writing to a moved/deleted SQLite file after the class ends.
        if (manager.isRunning()) {
            manager.stop();
        }
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM videos");
    }

    @Test
    void startupRecoveryResolvesInterruptedJobsWithoutTouchingHistory() throws Exception {
        String now = LegacyTimestamp.format(Instant.now());
        String old = LegacyTimestamp.format(Instant.now().minus(Duration.ofHours(48)));

        String requeueId = job(video("user-a", "requeue.mp4", null, now), "user-a",
                "processing", null, VALID_OPERATIONS, "token-requeue", null, null, now);
        String cancelId = job(video("user-a", "cancel.mp4", null, now), "user-a",
                "cancel_requested", null, VALID_OPERATIONS, "token-cancel", null, now, now);
        String missingVideo = video("user-a", "missing.mp4", null, now);
        String missingSource = tempDir.resolve("uploads/missing-source.mp4").toString();
        String missingId = job(missingVideo, "user-a", "processing", missingSource,
                VALID_OPERATIONS, "token-missing", null, null, now);
        String invalidId = job(video("user-a", "invalid.mp4", null, now), "user-a",
                "processing", null, "not-json", "token-invalid", null, null, now);
        String partialVideo = video("user-a", "partial.mp4", null, now);
        Path partial = outputFile(partialVideo, ".mp4");
        String partialId = job(partialVideo, "user-a", "processing", null,
                VALID_OPERATIONS, "token-partial", partial.toString(), null, now);
        String queuedId = job(video("user-a", "queued.mp4", null, now), "user-a",
                "queued", null, VALID_OPERATIONS, null, null, null, now);
        String terminalId = job(video("user-a", "history.mp4", null, now), "user-a",
                "completed", null, VALID_OPERATIONS, "token-history", null, null, old);

        recovery.recoverOnStartup();

        assertThat(status(requeueId)).isEqualTo("queued");
        assertThat(token(requeueId)).isNull();
        assertThat(startedAt(requeueId)).isNull();
        // attempt is incremented when a worker claims the job, not when recovery requeues it.
        assertThat(attempt(requeueId)).isZero();

        assertThat(status(cancelId)).isEqualTo("cancelled");
        assertThat(outputPath(cancelId)).isNull();
        assertThat(error(cancelId)).isNull();
        assertThat(finishedAt(cancelId)).isNotNull();

        assertThat(status(missingId)).isEqualTo("failed");
        assertThat(error(missingId)).isEqualTo("source file is no longer available");
        assertThat(status(invalidId)).isEqualTo("failed");
        assertThat(error(invalidId)).isEqualTo("invalid saved operations");
        assertThat(status(partialId)).isEqualTo("failed");
        assertThat(error(partialId)).isEqualTo("recovery found an incomplete output");
        assertThat(partial).doesNotExist();

        assertThat(status(queuedId)).isEqualTo("queued");
        assertThat(status(terminalId)).isEqualTo("completed");
        assertThat(finishedAt(terminalId)).isEqualTo(old);
    }

    @Test
    void retentionCleanupProtectsActiveJobsCurrentOutputsAndRecentHistory() throws Exception {
        String now = LegacyTimestamp.format(Instant.now());
        String old = LegacyTimestamp.format(Instant.now().minus(Duration.ofHours(48)));

        String eligibleVideo = video("user-a", "eligible.mp4", null, now);
        Path eligibleOutput = outputFile(eligibleVideo, ".mp4");
        String eligibleJob = job(eligibleVideo, "user-a", "completed", null,
                VALID_OPERATIONS, null, eligibleOutput.toString(), null, old);

        String currentVideo = video("user-b", "current.mp4", null, now);
        Path currentOutput = outputFile(currentVideo, ".mp4");
        jdbc.update("UPDATE videos SET output_path = ?, status = 'completed' WHERE id = ?",
                currentOutput.toString(), currentVideo);
        String currentJob = job(currentVideo, "user-b", "completed", null,
                VALID_OPERATIONS, null, currentOutput.toString(), null, old);

        String activeVideo = video("user-c", "active.mp4", null, now);
        Path activeOutput = outputFile(activeVideo, ".mp4");
        String oldJobOnActiveVideo = job(activeVideo, "user-c", "completed", null,
                VALID_OPERATIONS, null, activeOutput.toString(), null, old);
        String activeJob = job(activeVideo, "user-c", "queued", null,
                VALID_OPERATIONS, null, null, null, now);

        String recentVideo = video("user-d", "recent.mp4", null, now);
        String recentJob = job(recentVideo, "user-d", "failed", null,
                VALID_OPERATIONS, null, null, null, now);

        String failedVideo = video("user-e", "old-failed.mp4", null, now);
        String oldFailedJob = job(failedVideo, "user-e", "failed", null,
                VALID_OPERATIONS, null, null, null, old);

        for (Path output : new Path[] {eligibleOutput, currentOutput, activeOutput}) {
            Files.writeString(output, "generated-output");
        }

        int removed = cleanup.cleanupOnce();

        assertThat(removed).isEqualTo(2);
        assertThat(countJobs(eligibleJob)).isZero();
        assertThat(eligibleOutput).doesNotExist();
        assertThat(countJobs(oldFailedJob)).isZero();

        assertThat(countJobs(currentJob)).isEqualTo(1);
        assertThat(currentOutput).exists();
        assertThat(countJobs(oldJobOnActiveVideo)).isEqualTo(1);
        assertThat(countJobs(activeJob)).isEqualTo(1);
        assertThat(status(activeJob)).isEqualTo("queued");
        assertThat(activeOutput).exists();
        assertThat(countJobs(recentJob)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM videos")).isEqualTo(5);
        for (String videoId : new String[] {eligibleVideo, currentVideo, activeVideo, recentVideo, failedVideo}) {
            assertThat(Path.of(sourcePath(videoId))).exists();
        }
    }

    @Test
    void staleScratchSweepRemovesOnlyOldPrivateProcessingDirectories() throws Exception {
        Path root = tempDir.resolve("data/tmp");
        Files.createDirectories(root);
        Path stale = Files.createDirectories(root.resolve("processing-stale"));
        Path staleFile = stale.resolve("partial.mp4");
        Files.writeString(staleFile, "partial");
        Path fresh = Files.createDirectories(root.resolve("processing-fresh"));
        Path unrelated = Files.createDirectories(root.resolve("upload-old"));
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(48)));
        Files.setLastModifiedTime(stale, old);
        Files.setLastModifiedTime(unrelated, old);

        cleanup.sweepStaleScratch();

        assertThat(stale).doesNotExist();
        assertThat(staleFile).doesNotExist();
        assertThat(fresh).exists();
        assertThat(unrelated).exists();
    }

    @Test
    void staleOutputTempSweepRemovesOnlyOldOrphanedStagedFiles() throws Exception {
        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        Path stale = outputs.resolve(".output-abandoned.bin");
        Path fresh = outputs.resolve(".output-active.bin");
        Path regular = outputs.resolve("regular-output.mp4");
        Files.writeString(stale, "staged");
        Files.writeString(fresh, "staged");
        Files.writeString(regular, "output");
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(48)));
        Files.setLastModifiedTime(stale, old);
        Files.setLastModifiedTime(regular, old);

        cleanup.sweepStaleOutputTemps();

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
        assertThat(regular).exists();
    }
    @Test
    void expiredVideoCleanupDeletesOnlyFullyExpiredTerminalVideos() throws Exception {
        String now = LegacyTimestamp.format(Instant.now());
        String old = LegacyTimestamp.format(Instant.now().minus(Duration.ofHours(48)));

        // Eligible: old completed video with old completed job and artifacts.
        String eligibleVideo = video("user-a", "eligible.mp4", null, old);
        Path eligibleSource = Path.of(sourcePath(eligibleVideo));
        Path eligibleOutput = tempDir.resolve("outputs").resolve(eligibleVideo + "-out.mp4");
        String eligibleJob = UUID.randomUUID().toString();
        Path eligibleJobOutput = tempDir.resolve("outputs").resolve(eligibleJob + ".mp4");
        Path eligiblePart = tempDir.resolve("outputs").resolve("." + eligibleJob + ".part.mp4");
        Files.writeString(eligibleOutput, "out");
        Files.writeString(eligibleJobOutput, "job-out");
        Files.writeString(eligiblePart, "part");
        jdbc.update("UPDATE videos SET status = 'completed', output_path = ? WHERE id = ?",
                eligibleOutput.toString(), eligibleVideo);
        jdbc.update("INSERT INTO jobs (id, video_id, video_name, source_path, operations, status, "
                        + "progress, created_at, started_at, finished_at, output_path, error, "
                        + "user_id, encrypted, enc_key, execution_token, attempt, worker_id) "
                        + "VALUES (?, ?, ?, ?, ?, 'completed', 100, ?, ?, ?, ?, NULL, ?, 0, NULL, NULL, 0, 'worker-test')",
                eligibleJob, eligibleVideo, "eligible.mp4", eligibleSource.toString(),
                VALID_OPERATIONS, old, old, old, eligibleJobOutput.toString(), "user-a");

        // Eligible: old failed and cancelled videos without blocking jobs.
        String failedVideo = video("user-a", "failed.mp4", null, old);
        jdbc.update("UPDATE videos SET status = 'failed' WHERE id = ?", failedVideo);
        Path failedSource = Path.of(sourcePath(failedVideo));
        String cancelledVideo = video("user-a", "cancelled.mp4", null, old);
        jdbc.update("UPDATE videos SET status = 'cancelled' WHERE id = ?", cancelledVideo);
        Path cancelledSource = Path.of(sourcePath(cancelledVideo));

        // Blocked: old terminal video but has an active queued job.
        String queuedVideo = video("user-a", "queued.mp4", null, old);
        jdbc.update("UPDATE videos SET status = 'completed' WHERE id = ?", queuedVideo);
        String queuedJob = job(queuedVideo, "user-a", "queued", null, VALID_OPERATIONS, null, null, null, old);
        Path queuedSource = Path.of(sourcePath(queuedVideo));

        // Blocked: old terminal video with a recent completed job.
        String recentJobVideo = video("user-a", "recent-job.mp4", null, old);
        jdbc.update("UPDATE videos SET status = 'completed' WHERE id = ?", recentJobVideo);
        Path recentJobOutput = tempDir.resolve("outputs").resolve(recentJobVideo + "-recent.mp4");
        Files.writeString(recentJobOutput, "recent-output");
        String recentJob = job(recentJobVideo, "user-a", "completed", null, VALID_OPERATIONS, null,
                recentJobOutput.toString(), null, now);
        Path recentJobSource = Path.of(sourcePath(recentJobVideo));

        // Not eligible: old uploaded (non-terminal) video.
        String uploadedVideo = video("user-a", "uploaded.mp4", null, old);
        Path uploadedSource = Path.of(sourcePath(uploadedVideo));

        // Not eligible: recent terminal video.
        String recentVideo = video("user-a", "recent.mp4", null, now);
        jdbc.update("UPDATE videos SET status = 'completed' WHERE id = ?", recentVideo);
        Path recentSource = Path.of(sourcePath(recentVideo));

        cleanup.cleanupOnce();

        assertThat(count("SELECT COUNT(*) FROM videos WHERE id = ?", eligibleVideo)).isZero();
        assertThat(countJobs(eligibleJob)).isZero();
        assertThat(eligibleSource).doesNotExist();
        assertThat(eligibleOutput).doesNotExist();
        assertThat(eligibleJobOutput).doesNotExist();
        assertThat(eligiblePart).doesNotExist();

        assertThat(count("SELECT COUNT(*) FROM videos WHERE id = ?", failedVideo)).isZero();
        assertThat(failedSource).doesNotExist();
        assertThat(count("SELECT COUNT(*) FROM videos WHERE id = ?", cancelledVideo)).isZero();
        assertThat(cancelledSource).doesNotExist();

        assertThat(count("SELECT COUNT(*) FROM videos WHERE id = ?", queuedVideo)).isEqualTo(1);
        assertThat(countJobs(queuedJob)).isEqualTo(1);
        assertThat(queuedSource).exists();

        assertThat(count("SELECT COUNT(*) FROM videos WHERE id = ?", recentJobVideo)).isEqualTo(1);
        assertThat(countJobs(recentJob)).isEqualTo(1);
        assertThat(recentJobSource).exists();
        assertThat(recentJobOutput).exists();

        assertThat(count("SELECT COUNT(*) FROM videos WHERE id = ?", uploadedVideo)).isEqualTo(1);
        assertThat(uploadedSource).exists();
        assertThat(count("SELECT COUNT(*) FROM videos WHERE id = ?", recentVideo)).isEqualTo(1);
        assertThat(recentSource).exists();
    }


    private void ensureUser(String id) {
        jdbc.update("INSERT OR IGNORE INTO users (id, email, normalized_email, password_hash) "
                + "VALUES (?, ?, ?, 'x')", id, id + "@test.example", id + "@test.example");
    }

    private String video(String userId, String name, String outputPath, String createdAt) throws IOException {
        ensureUser(userId);
        String id = UUID.randomUUID().toString();
        Path source = tempDir.resolve("uploads").resolve(id + ".mp4");
        Files.createDirectories(source.getParent());
        if (!Files.exists(source)) {
            Files.write(source, new byte[] {1, 2, 3, 4});
        }
        jdbc.update("INSERT INTO videos (id, original_path, original_name, output_path, status, "
                        + "progress, created_at, user_id, encrypted, output_encrypted) "
                        + "VALUES (?, ?, ?, ?, 'uploaded', 0, ?, ?, 0, 0)",
                id, source.toString(), name, outputPath, createdAt, userId);
        return id;
    }

    private String job(String videoId, String userId, String status, String sourceOverride,
                       String operations, String token, String outputPath, String cancelAt,
                       String timestamp) throws IOException {
        String id = UUID.randomUUID().toString();
        String source = sourceOverride;
        if (source == null) {
            source = sourcePath(videoId);
        }
        String startedAt = "queued".equals(status) ? null : timestamp;
        String finishedAt = JobState.isTerminal(status) ? timestamp : null;
        jdbc.update("INSERT INTO jobs (id, video_id, video_name, source_path, operations, status, "
                        + "progress, created_at, started_at, finished_at, cancel_requested_at, "
                        + "output_path, error, user_id, encrypted, enc_key, execution_token, "
                        + "attempt, worker_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, NULL, ?, 0, NULL, ?, 0, ?)",
                id, videoId, "generated.mp4", source, operations, status, timestamp, startedAt,
                finishedAt, cancelAt, outputPath, userId, token, "worker-test");
        return id;
    }

    private Path outputFile(String videoId, String extension) throws IOException {
        Path outputs = tempDir.resolve("outputs");
        Files.createDirectories(outputs);
        return outputs.resolve(videoId + extension);
    }

    private String sourcePath(String videoId) {
        return jdbc.queryForObject("SELECT original_path FROM videos WHERE id = ?", String.class, videoId);
    }

    private String status(String jobId) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String token(String jobId) {
        return jdbc.queryForObject("SELECT execution_token FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String outputPath(String jobId) {
        return jdbc.queryForObject("SELECT output_path FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String error(String jobId) {
        return jdbc.queryForObject("SELECT error FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String finishedAt(String jobId) {
        return jdbc.queryForObject("SELECT finished_at FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String startedAt(String jobId) {
        return jdbc.queryForObject("SELECT started_at FROM jobs WHERE id = ?", String.class, jobId);
    }

    private Integer attempt(String jobId) {
        return jdbc.queryForObject("SELECT attempt FROM jobs WHERE id = ?", Integer.class, jobId);
    }

    private Integer countJobs(String jobId) {
        return count("SELECT COUNT(*) FROM jobs WHERE id = ?", jobId);
    }

    private Integer count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }
}
