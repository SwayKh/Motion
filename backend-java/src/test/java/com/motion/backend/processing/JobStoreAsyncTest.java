package com.motion.backend.processing;

import com.motion.backend.time.LegacyTimestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Durable queue, lease, quota, cancellation, and recovery primitives without workers. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=test",
                "motion.jobs.max-concurrent-jobs-per-user=2",
                "motion.jobs.max-global-concurrent-jobs=10",
                "motion.jobs.max-queued-jobs=10"
        }
)
class JobStoreAsyncTest {

    @TempDir
    static Path tempDir;

    private static Path source;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("job-store.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
    }

    @Autowired
    private JobStore jobs;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM jobs");
        jdbc.update("DELETE FROM videos");
        Files.createDirectories(tempDir.resolve("uploads"));
        source = tempDir.resolve("uploads/source.mp4");
        if (!Files.exists(source)) {
            Files.write(source, new byte[] {1, 2, 3, 4});
        }
    }

    @Test
    void claimNextIsFifoAndExecutesEachJobOnce() {
        String first = createQueued("user-a", "first");
        String second = createQueued("user-a", "second");
        jdbc.update("UPDATE jobs SET created_at = ? WHERE id = ?",
                LegacyTimestamp.format(Instant.now().minusSeconds(10)), first);

        JobLease firstLease = jobs.claimNext("worker-1").orElseThrow();
        JobLease secondLease = jobs.claimNext("worker-2").orElseThrow();

        assertThat(firstLease.job().id()).isEqualTo(first);
        assertThat(secondLease.job().id()).isEqualTo(second);
        assertThat(firstLease.token()).isNotBlank().isNotEqualTo(secondLease.token());
        assertThat(firstLease.attempt()).isEqualTo(1);
        assertThat(jobs.claimNext("worker-3")).isEmpty();
        assertThat(status(first)).isEqualTo("processing");
    }

    @Test
    void staleLeaseCannotWriteProgressOrTerminalState() {
        String id = createQueued("user-a", "lease");
        JobLease lease = jobs.claimNext("worker-1").orElseThrow();

        jobs.updateProgress(id, "stale-token", 75);
        assertThat(progress(id)).isZero();

        assertThat(jobs.complete(id, "stale-token", "/outputs/fake.mp4", false, null)).isEmpty();
        jobs.updateProgress(id, lease.token(), 25);
        assertThat(progress(id)).isEqualTo(25D);

        JobRecord completed = jobs.complete(id, lease.token(), "/outputs/fake.mp4", false, null)
                .orElseThrow();
        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.progress()).isEqualTo(100D);
        assertThat(jobs.fail(id, lease.token(), "late failure")).isEmpty();
        assertThat(status(id)).isEqualTo("completed");
    }

    @Test
    void progressIsClampedAndCancellationBlocksProgressAndCompletion() {
        String id = createQueued("user-a", "progress");
        JobLease lease = jobs.claimNext("worker-1").orElseThrow();

        jobs.updateProgress(id, lease.token(), 500);
        assertThat(progress(id)).isEqualTo(100D);
        jobs.updateProgress(id, lease.token(), -25);
        assertThat(progress(id)).isZero();

        assertThat(jobs.requestCancel(id).outcome()).isEqualTo(CancelResult.Outcome.RUNNING_CANCEL_REQUESTED);
        jobs.updateProgress(id, lease.token(), 50);
        assertThat(progress(id)).isZero();
        assertThat(jobs.complete(id, lease.token(), "/outputs/fake.mp4", false, null)).isEmpty();
        assertThat(jobs.fail(id, lease.token(), "late failure")).isEmpty();
        assertThat(jobs.markCancelled(id, lease.token())).isTrue();
        assertThat(status(id)).isEqualTo("cancelled");
        assertThat(output(id)).isNull();
    }

    @Test
    void queuedAndRunningCancellationFollowGoSemantics() {
        String queuedId = createQueued("user-a", "queued-cancel");
        CancelResult queuedCancel = jobs.requestCancel(queuedId);
        assertThat(queuedCancel.outcome()).isEqualTo(CancelResult.Outcome.QUEUED_CANCELLED);
        JobRecord queued = jobs.findById(queuedId).orElseThrow();
        assertThat(queued.status()).isEqualTo("cancelled");
        assertThat(queued.cancelRequestedAt()).isNull();
        assertThat(queued.finishedAt()).isNotNull();
        assertThat(videoStatus(queued.videoId())).isEqualTo("cancelled");
        assertThat(jobs.requestCancel(queuedId).outcome())
                .isEqualTo(CancelResult.Outcome.NOT_CANCELLABLE);

        String runningId = createQueued("user-a", "running-cancel");
        JobLease lease = jobs.claimNext("worker-1").orElseThrow();
        assertThat(lease.job().id()).isEqualTo(runningId);
        CancelResult runningCancel = jobs.requestCancel(runningId);
        assertThat(runningCancel.outcome()).isEqualTo(CancelResult.Outcome.RUNNING_CANCEL_REQUESTED);
        assertThat(status(runningId)).isEqualTo("cancel_requested");
        assertThat(jobs.findById(runningId).orElseThrow().cancelRequestedAt()).isNotNull();
        assertThat(jobs.requestCancel(runningId).outcome())
                .isEqualTo(CancelResult.Outcome.NOT_CANCELLABLE);
        assertThat(jobs.markCancelled(runningId, lease.token())).isTrue();
        assertThat(status(runningId)).isEqualTo("cancelled");
    }

    @Test
    void recoveryRequeuesInterruptedJobsAndInvalidatesOldLease() {
        String id = createQueued("user-a", "recovery");
        JobLease lease = jobs.claimNext("worker-1").orElseThrow();

        List<JobLease> interrupted = jobs.listInterrupted();
        assertThat(interrupted).extracting(value -> value.job().id()).containsExactly(id);
        assertThat(jobs.requeueInterrupted(id, lease.token())).isTrue();

        JobRecord requeued = jobs.findById(id).orElseThrow();
        assertThat(requeued.status()).isEqualTo("queued");
        assertThat(requeued.startedAt()).isNull();
        assertThat(requeued.progress()).isZero();
        jobs.updateProgress(id, lease.token(), 80);
        assertThat(progress(id)).isZero();

        JobLease next = jobs.claimNext("worker-2").orElseThrow();
        assertThat(next.job().id()).isEqualTo(id);
        assertThat(next.attempt()).isEqualTo(2);
        assertThat(next.token()).isNotEqualTo(lease.token());
    }

    @Test
    void activeQuotasAndPerVideoUniquenessAreEnforced() {
        String videoId = createVideo("user-a", "quota-source");
        String operations = "{\"operations\":[{\"type\":\"flip\",\"orientation\":1}]}";
        jobs.createQueued(videoId, "quota-source.mp4", source.toString(), operations, "user-a");
        String otherVideo = createVideo("user-a", "second-source");
        jobs.createQueued(otherVideo, "second-source.mp4", source.toString(), operations, "user-a");

        assertThatThrownBy(() -> jobs.createQueued(
                createVideo("user-a", "third-source"), "third", source.toString(), operations, "user-a"))
                .isInstanceOf(JobQuotaException.class)
                .hasMessage("too many active jobs; try again later");
        assertThatThrownBy(() -> jobs.createQueued(
                videoId, "duplicate", source.toString(), operations, "user-a"))
                .isInstanceOf(JobConflictException.class)
                .hasMessage("video already has an active job");

        String otherUser = createQueued("user-b", "allowed");
        assertThat(status(otherUser)).isEqualTo("queued");
    }

    private String createQueued(String userId, String name) {
        String videoId = createVideo(userId, name);
        JobRecord job = jobs.createQueued(videoId, name + ".mp4", source.toString(),
                "{\"operations\":[{\"type\":\"flip\",\"orientation\":1}]}", userId);
        return job.id();
    }

    private void ensureUser(String id) {
        jdbc.update("INSERT OR IGNORE INTO users (id, email, normalized_email, password_hash) "
                + "VALUES (?, ?, ?, 'x')", id, id + "@test.example", id + "@test.example");
    }

    private String createVideo(String userId, String name) {
        ensureUser(userId);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO videos (id, original_path, original_name, status, progress, "
                        + "created_at, user_id, encrypted, output_encrypted) "
                        + "VALUES (?, ?, ?, 'uploaded', 0, ?, ?, 0, 0)",
                id, source.toString(), name, LegacyTimestamp.format(Instant.now()), userId);
        return id;
    }

    private String status(String jobId) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId);
    }

    private Double progress(String jobId) {
        return jdbc.queryForObject("SELECT progress FROM jobs WHERE id = ?", Double.class, jobId);
    }

    private String output(String jobId) {
        return jdbc.queryForObject("SELECT output_path FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String videoStatus(String videoId) {
        return jdbc.queryForObject("SELECT status FROM videos WHERE id = ?", String.class, videoId);
    }
}
