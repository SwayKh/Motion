package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.processing.JobExecutionRegistry;
import com.motion.backend.processing.AsyncJobManager;
import com.motion.backend.processing.JobState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-HTTP cancellation evidence, including termination of the attached FFmpeg process. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.upload.max-bytes=33554432",
                "motion.quota.min-free-disk-bytes=0",
                "motion.quota.max-active-uploads-per-user=10",
                "motion.quota.max-source-files-per-user=100",
                "motion.quota.max-outputs-per-user=100",
                "motion.jobs.max-concurrent-jobs-per-user=10",
                "motion.jobs.max-global-concurrent-jobs=10",
                "motion.jobs.worker-count=1",
                "motion.jobs.poll-interval=100ms",
                "motion.jobs.progress-interval=25ms",
                "motion.jobs.shutdown-timeout=5s",
                "spring.servlet.multipart.max-file-size=40MB",
                "spring.servlet.multipart.max-request-size=40MB",
                "server.tomcat.max-http-form-post-size=45MB"
        }
)
class JobCancellationIntegrationTest {

    @TempDir
    static Path tempDir;
    private static Path longVideo;
    private static AsyncJobManager staticManager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("cancellation.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JobExecutionRegistry registry;
    @Autowired
    private AsyncJobManager manager;

    @BeforeAll
    static void generateFixture() throws Exception {
        MediaTestSupport.assumeFfmpegAvailable();
        longVideo = MediaTestSupport.generateLongVideo(tempDir.resolve("fixtures"), "long.mp4", 90);
    }

    @BeforeEach
    void captureManager() {
        staticManager = manager;
    }

    @AfterAll
    static void stopWorkers() {
        // The Spring context is cached while JUnit removes the @TempDir database after this class.
        if (staticManager != null && staticManager.isRunning()) {
            staticManager.stop();
        }
    }

    @Test
    void runningCancellationKillsFfmpegAndLeavesNoOutput() throws Exception {
        TestHttpClient client = authenticatedClient();
        TestHttpClient other = authenticatedClient();
        String videoId = uploadId(client, "cancel-running.mp4");
        String jobId = submitFlip(client, videoId).path("id").asText();

        Process ffmpeg = awaitAttachedProcess(Set.of(jobId));
        assertThat(ffmpeg.isAlive()).isTrue();
        double progress = awaitProgress(jobId);
        assertThat(progress).isGreaterThan(0D).isLessThan(100D);

        assertError(other.postWithCsrf("/api/jobs/" + jobId + "/cancel", "{}"),
                HttpStatus.NOT_FOUND, "job not found");

        ResponseEntity<String> cancelled = client.postWithCsrf("/api/jobs/" + jobId + "/cancel", "{}");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode response = mapper.readTree(cancelled.getBody());
        assertThat(response.path("status").asText()).isEqualTo("cancel_requested");
        assertThat(response.hasNonNull("cancelRequestedAt")).isTrue();
        // The worker may finalise between the response and this read; either state is correct.
        assertThat(videoStatus(videoId)).isIn("cancel_requested", "cancelled");

        awaitProcessExit(ffmpeg);
        JsonNode terminal = awaitTerminal(client, jobId);
        assertThat(terminal.path("status").asText()).isEqualTo("cancelled");
        assertThat(terminal.path("hasOutput").asBoolean()).isFalse();
        assertThat(terminal.has("outputType")).isFalse();
        assertThat(terminal.has("error")).isFalse();
        assertThat(outputPath(jobId)).isNull();
        assertThat(tempDir.resolve("outputs").resolve(jobId + ".mp4")).doesNotExist();
        assertThat(videoStatus(videoId)).isEqualTo("cancelled");

        assertError(client.postWithCsrf("/api/jobs/" + jobId + "/cancel", "{}"),
                HttpStatus.CONFLICT, "job has already finished");
        assertNoScratch();
    }

    @Test
    void queuedCancellationIsImmediateAndDoesNotSetCancelRequestedAt() throws Exception {
        TestHttpClient client = authenticatedClient();
        String firstVideo = uploadId(client, "busy.mp4");
        String firstJob = submitFlip(client, firstVideo).path("id").asText();
        awaitAttachedProcess(Set.of(firstJob));

        String secondVideo = uploadId(client, "queued-cancel.mp4");
        JsonNode queued = submitFlip(client, secondVideo);
        String secondJob = queued.path("id").asText();
        assertThat(queued.path("status").asText()).isEqualTo("queued");

        ResponseEntity<String> response = client.postWithCsrf("/api/jobs/" + secondJob + "/cancel", "{}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode cancelled = mapper.readTree(response.getBody());
        assertThat(cancelled.path("status").asText()).isEqualTo("cancelled");
        assertThat(cancelled.has("cancelRequestedAt")).isFalse();
        assertThat(cancelled.hasNonNull("finishedAt")).isTrue();
        assertThat(videoStatus(secondVideo)).isEqualTo("cancelled");
        assertThat(outputPath(secondJob)).isNull();

        assertError(client.postWithCsrf("/api/jobs/" + secondJob + "/cancel", "{}"),
                HttpStatus.CONFLICT, "job has already finished");

        client.postWithCsrf("/api/jobs/" + firstJob + "/cancel", "{}");
        assertThat(awaitTerminal(client, firstJob).path("status").asText()).isEqualTo("cancelled");
        assertThat(awaitTerminal(client, secondJob).path("status").asText()).isEqualTo("cancelled");
        assertNoScratch();
    }

    private Process awaitAttachedProcess(Set<String> jobIds) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            Process process = registry.activeExecutions().stream()
                    .filter(handle -> jobIds.contains(handle.jobId()))
                    .flatMap(handle -> handle.processes().stream())
                    .filter(Process::isAlive)
                    .findFirst()
                    .orElse(null);
            if (process != null) {
                return process;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no live FFmpeg process attached for jobs " + jobIds);
    }

    private double awaitProgress(String jobId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        double observed = 0D;
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM jobs WHERE id = ?", String.class, jobId);
            observed = jdbc.queryForObject(
                    "SELECT progress FROM jobs WHERE id = ?", Double.class, jobId);
            if ("processing".equals(status) && observed > 0D && observed < 100D) {
                return observed;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no intermediate progress observed for " + jobId + "; last=" + observed);
    }

    private void awaitProcessExit(Process process) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline && process.isAlive()) {
            Thread.sleep(25);
        }
        assertThat(process.isAlive()).as("FFmpeg process was terminated").isFalse();
    }

    private TestHttpClient authenticatedClient() {
        return MediaTestSupport.authenticatedClient(restTemplate);
    }

    private String uploadId(TestHttpClient client, String filename) throws Exception {
        ResponseEntity<String> response = client.postMultipart("/api/upload", "video", filename,
                MediaTestSupport.bytes(longVideo), true, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(response.getBody()).path("id").asText();
    }

    private JsonNode submitFlip(TestHttpClient client, String videoId) throws Exception {
        ResponseEntity<String> response = client.postWithCsrf("/api/videos/" + videoId + "/process",
                "{\"operations\":[{\"type\":\"flip\",\"orientation\":1}]}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return mapper.readTree(response.getBody());
    }

    private JsonNode awaitTerminal(TestHttpClient client, String jobId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> response = client.get("/api/jobs/" + jobId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            last = mapper.readTree(response.getBody());
            if (JobState.isTerminal(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("job " + jobId + " did not terminate: " + last);
    }

    private String videoStatus(String videoId) {
        return jdbc.queryForObject("SELECT status FROM videos WHERE id = ?", String.class, videoId);
    }

    private String outputPath(String jobId) {
        return jdbc.queryForObject("SELECT output_path FROM jobs WHERE id = ?", String.class, jobId);
    }

    private void assertError(ResponseEntity<String> response, HttpStatus status, String message) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(mapper.readTree(response.getBody()).path("error").asText()).isEqualTo(message);
    }

    private void assertNoScratch() throws Exception {
        Path scratch = tempDir.resolve("data/tmp");
        if (!Files.isDirectory(scratch)) {
            return;
        }
        try (var entries = Files.list(scratch)) {
            assertThat(entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("processing-")))
                    .isEmpty();
        }
    }
}
