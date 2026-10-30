package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.config.MotionProperties;
import com.motion.backend.processing.AsyncJobManager;
import com.motion.backend.processing.JobState;
import org.junit.jupiter.api.AfterEach;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Atomic quota and durable queue-capacity behavior through the real HTTP boundary. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.upload.max-bytes=33554432",
                "motion.quota.min-free-disk-bytes=0",
                "motion.quota.max-active-uploads-per-user=20",
                "motion.quota.max-source-files-per-user=100",
                "motion.quota.max-outputs-per-user=100",
                "motion.jobs.max-concurrent-jobs-per-user=10",
                "motion.jobs.max-global-concurrent-jobs=10",
                "motion.jobs.max-queued-jobs=10",
                "motion.jobs.worker-count=1",
                "motion.jobs.poll-interval=100ms",
                "motion.jobs.progress-interval=25ms",
                "spring.servlet.multipart.max-file-size=40MB",
                "spring.servlet.multipart.max-request-size=40MB",
                "server.tomcat.max-http-form-post-size=45MB"
        }
)
class JobQuotaAndQueueIntegrationTest {

    @TempDir
    static Path tempDir;
    private static Path video;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("quotas.db"));
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
    private MotionProperties properties;
    @Autowired
    private AsyncJobManager manager;

    private int originalPerUser;
    private int originalGlobal;
    private int originalQueued;

    @BeforeAll
    static void generateFixture() throws Exception {
        MediaTestSupport.assumeFfmpegAvailable();
        video = MediaTestSupport.generateVideo(tempDir.resolve("fixtures"), "short.mp4");
    }

    @BeforeEach
    void freezeWorkersAndRememberConfig() {
        originalPerUser = properties.maxConcurrentJobsPerUser();
        originalGlobal = properties.maxGlobalConcurrentJobs();
        originalQueued = properties.maxQueuedJobs();
        manager.stop();
    }

    @AfterEach
    void restoreConfigAndStopWorkers() {
        properties.getJobs().setMaxConcurrentJobsPerUser(Integer.toString(originalPerUser));
        properties.getJobs().setMaxGlobalConcurrentJobs(Integer.toString(originalGlobal));
        properties.getJobs().setMaxQueuedJobs(Integer.toString(originalQueued));
        // Keep the cached context from polling a @TempDir database that JUnit will delete.
        if (manager.isRunning()) {
            manager.stop();
        }
    }

    @Test
    void perUserAndGlobalQuotasReturnTheExactGoMessage() throws Exception {
        setLimits(1, 2, 10);
        TestHttpClient firstUser = authenticatedClient();
        TestHttpClient secondUser = authenticatedClient();
        TestHttpClient thirdUser = authenticatedClient();

        JsonNode first = submit(firstUser, uploadId(firstUser, "a.mp4"));
        assertThat(first.path("status").asText()).isEqualTo("queued");
        assertQuotaError(submitError(firstUser, uploadId(firstUser, "b.mp4")));

        JsonNode second = submit(secondUser, uploadId(secondUser, "c.mp4"));
        assertThat(second.path("status").asText()).isEqualTo("queued");
        assertQuotaError(submitError(thirdUser, uploadId(thirdUser, "d.mp4")));

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE status IN ('queued','processing','cancel_requested')",
                Integer.class);
        assertThat(active).isEqualTo(2);

        manager.start();
        assertThat(awaitTerminal(firstUser, first.path("id").asText()).path("status").asText())
                .isEqualTo("completed");
        assertThat(awaitTerminal(secondUser, second.path("id").asText()).path("status").asText())
                .isEqualTo("completed");
    }

    @Test
    void queuedSubmissionsAreLimitedByActiveQuotasLikeGo() throws Exception {
        // Go has no separate queued-only capacity; queued jobs count toward active limits.
        setLimits(10, 2, 1);
        TestHttpClient client = authenticatedClient();
        List<String> accepted = new ArrayList<>();
        accepted.add(submit(client, uploadId(client, "q1.mp4")).path("id").asText());
        accepted.add(submit(client, uploadId(client, "q2.mp4")).path("id").asText());
        assertQuotaError(submitError(client, uploadId(client, "q3.mp4")));

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE status IN ('queued','processing','cancel_requested')",
                Integer.class);
        assertThat(active).isEqualTo(2);

        manager.start();
        for (String jobId : accepted) {
            assertThat(awaitTerminal(client, jobId).path("status").asText()).isEqualTo("completed");
        }
    }

    private void setLimits(int perUser, int global, int queued) {
        properties.getJobs().setMaxConcurrentJobsPerUser(Integer.toString(perUser));
        properties.getJobs().setMaxGlobalConcurrentJobs(Integer.toString(global));
        properties.getJobs().setMaxQueuedJobs(Integer.toString(queued));
    }

    private TestHttpClient authenticatedClient() {
        return MediaTestSupport.authenticatedClient(restTemplate);
    }

    private String uploadId(TestHttpClient client, String filename) throws Exception {
        ResponseEntity<String> response = client.postMultipart("/api/upload", "video", filename,
                MediaTestSupport.bytes(video), true, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(response.getBody()).path("id").asText();
    }

    private JsonNode submit(TestHttpClient client, String videoId) throws Exception {
        ResponseEntity<String> response = submitError(client, videoId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return mapper.readTree(response.getBody());
    }

    private ResponseEntity<String> submitError(TestHttpClient client, String videoId) {
        return client.postWithCsrf("/api/videos/" + videoId + "/process",
                "{\"operations\":[{\"type\":\"flip\",\"orientation\":1}]}");
    }

    private void assertQuotaError(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(mapper.readTree(response.getBody()).path("error").asText())
                .isEqualTo("too many active jobs; try again later");
    }

    private JsonNode awaitTerminal(TestHttpClient client, String jobId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
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
}
