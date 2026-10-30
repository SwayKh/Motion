package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.processing.AsyncJobManager;
import com.motion.backend.processing.JobExecutionRegistry;
import com.motion.backend.processing.JobExecutionRegistry.ExecutionHandle;
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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-HTTP evidence for persistence-before-execution, progress, and bounded workers. */
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
                "motion.jobs.max-global-concurrent-jobs=20",
                "motion.jobs.worker-count=2",
                "motion.jobs.poll-interval=100ms",
                "motion.jobs.progress-interval=25ms",
                "motion.jobs.shutdown-timeout=5s",
                "spring.servlet.multipart.max-file-size=40MB",
                "spring.servlet.multipart.max-request-size=40MB",
                "server.tomcat.max-http-form-post-size=45MB"
        }
)
class AsyncJobWorkerIntegrationTest {

    @TempDir
    static Path tempDir;
    private static Path longVideo;
    private static AsyncJobManager staticManager;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("async-workers.db"));
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
    private AsyncJobManager manager;
    @Autowired
    private JobExecutionRegistry registry;

    @BeforeAll
    static void generateFixture() throws Exception {
        MediaTestSupport.assumeFfmpegAvailable();
        longVideo = MediaTestSupport.generateLongVideo(tempDir.resolve("fixtures"), "long.mp4", 60);
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
    void jobIsPersistedBeforeAnyWorkerRunsFfmpeg() throws Exception {
        manager.stop();
        try {
            TestHttpClient client = authenticatedClient();
            String videoId = uploadId(client, "queued.mp4");
            assertThat(registry.activeExecutions()).isEmpty();

            long started = System.nanoTime();
            JsonNode submitted = submitFlip(client, videoId);
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(submitted.path("status").asText()).isEqualTo("queued");
            assertThat(elapsedMillis).isLessThan(2_000L);
            assertThat(status(submitted.path("id").asText())).isEqualTo("queued");
            assertThat(registry.activeExecutions()).isEmpty();

            manager.start();
            JsonNode terminal = awaitTerminal(client, submitted.path("id").asText());
            assertThat(terminal.path("status").asText()).isEqualTo("completed");
        } finally {
            if (!manager.isRunning()) {
                manager.start();
            }
        }
    }

    @Test
    void pollingObservesRealProgressBetweenZeroAndCompletion() throws Exception {
        TestHttpClient client = authenticatedClient();
        String videoId = uploadId(client, "progress.mp4");
        String jobId = submitFlip(client, videoId).path("id").asText();

        boolean sawProcessing = false;
        boolean sawProgress = false;
        double maxObserved = 0D;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = job(client, jobId);
            String status = last.path("status").asText();
            if ("processing".equals(status)) {
                sawProcessing = true;
            }
            if (!JobState.isTerminal(status)) {
                double progress = last.path("progress").asDouble();
                maxObserved = Math.max(maxObserved, progress);
                if (progress > 0D && progress < 100D) {
                    sawProgress = true;
                }
            }
            if (JobState.isTerminal(status)) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(last).isNotNull();
        assertThat(last.path("status").asText()).isEqualTo("completed");
        assertThat(sawProcessing).isTrue();
        assertThat(sawProgress).as("observed non-terminal progress values: max=%s last=%s", maxObserved, last)
                .isTrue();
        assertThat(last.path("progress").asInt()).isEqualTo(100);
        assertThat(outputPath(jobId)).isNotNull();
    }

    @Test
    void atMostConfiguredNumberOfWorkersExecuteConcurrently() throws Exception {
        TestHttpClient client = authenticatedClient();
        List<String> jobIds = List.of(
                submitFlip(client, uploadId(client, "concurrent-1.mp4")).path("id").asText(),
                submitFlip(client, uploadId(client, "concurrent-2.mp4")).path("id").asText(),
                submitFlip(client, uploadId(client, "concurrent-3.mp4")).path("id").asText());

        List<ExecutionHandle> active = awaitActiveExecutions(new HashSet<>(jobIds), 2);
        assertThat(active).hasSizeLessThanOrEqualTo(2);
        assertThat(active).extracting(ExecutionHandle::workerId).doesNotHaveDuplicates();
        assertThat(active).allSatisfy(handle -> assertThat(handle.processes()).isNotEmpty());

        for (String jobId : jobIds) {
            client.postWithCsrf("/api/jobs/" + jobId + "/cancel", "{}");
        }
        for (String jobId : jobIds) {
            assertThat(awaitTerminal(client, jobId).path("status").asText()).isEqualTo("cancelled");
        }
        assertNoScratch();
    }

    private List<ExecutionHandle> awaitActiveExecutions(Set<String> jobIds, int expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            List<ExecutionHandle> matching = registry.activeExecutions().stream()
                    .filter(handle -> jobIds.contains(handle.jobId()) && !handle.processes().isEmpty())
                    .toList();
            if (matching.size() >= expected) {
                return matching;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("expected " + expected + " active executions for " + jobIds);
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

    private JsonNode job(TestHttpClient client, String jobId) throws Exception {
        ResponseEntity<String> response = client.get("/api/jobs/" + jobId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private JsonNode awaitTerminal(TestHttpClient client, String jobId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = job(client, jobId);
            if (JobState.isTerminal(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("job " + jobId + " did not terminate: " + last);
    }

    private String status(String jobId) {
        return jdbc.queryForObject("SELECT status FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String outputPath(String jobId) {
        return jdbc.queryForObject("SELECT output_path FROM jobs WHERE id = ?", String.class, jobId);
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
