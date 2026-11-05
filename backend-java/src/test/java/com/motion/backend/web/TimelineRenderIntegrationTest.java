package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.media.MediaCrypto;
import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaStore;
import com.motion.backend.media.StorageService;
import com.motion.backend.processing.AsyncJobManager;
import com.motion.backend.processing.FFmpegExecutor;
import com.motion.backend.processing.FFprobeService;
import com.motion.backend.processing.JobRecoveryService;
import com.motion.backend.processing.JobState;
import com.motion.backend.processing.MediaMetadata;
import com.motion.backend.timeline.RenderSettings;
import com.motion.backend.timeline.TimelineClip;
import com.motion.backend.timeline.TimelineProject;
import com.motion.backend.timeline.TimelineRenderRequest;
import com.motion.backend.timeline.TimelineTrack;
import org.junit.jupiter.api.BeforeAll;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-HTTP timeline render coverage: snapshots, compose, encryption, jobs, and recovery. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.rate-limit.reset=10000",
                "motion.rate-limit.verify=10000",
                "motion.jobs.max-concurrent-jobs-per-user=1",
                "motion.jobs.max-global-concurrent-jobs=1",
                "motion.jobs.worker-count=1",
                "motion.jobs.poll-interval=100ms"
        }
)
class TimelineRenderIntegrationTest {

    private static final String MASTER_KEY = MediaCrypto.generateMasterKey();

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("render.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
        registry.add("motion.encryption.key", () -> MASTER_KEY);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MediaStore mediaStore;
    @Autowired
    private StorageService storage;
    @Autowired
    private FFprobeService ffprobe;
    @Autowired
    private FFmpegExecutor ffmpeg;
    @Autowired
    private AsyncJobManager manager;
    @Autowired
    private JobRecoveryService recovery;

    @BeforeAll
    static void requireFfmpeg() {
        MediaTestSupport.assumeFfmpegAvailable();
    }

    @Test
    void simpleRenderUsesEncryptedInputAndProducesVerifiedEncryptedOutput() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String videoId = upload(client, "render-simple.mp4",
                MediaTestSupport.generateAudioVideo(tempDir.resolve("fixtures"), "render-simple.mp4"));
        assertEncryptedAtRest(mediaStore.findById(videoId).orElseThrow().originalPath());

        ResponseEntity<String> submitted = render(client,
                mapper.writeValueAsString(new TimelineRenderRequest(null,
                        simpleProject(videoId, 1.5D, 0D, "Render"))));
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String jobId = mapper.readTree(submitted.getBody()).path("id").asText();
        JsonNode job = awaitTerminal(client, jobId);
        assertThat(job.path("status").asText()).isEqualTo("completed");

        Path output = decryptOutput(jobId);
        MediaMetadata metadata = ffprobe.probe(output);
        assertThat(metadata.hasVideo()).isTrue();
        assertThat(metadata.duration()).isPositive();
        ffmpeg.verify(output);
        assertNoScratch();
    }

    @Test
    void savedProjectIsSnapshottedAtSubmissionAndLaterEditsCannotChangeTheJob() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String videoId = upload(client, "render-snapshot.mp4",
                MediaTestSupport.generateVideo(tempDir.resolve("fixtures"), "render-snapshot.mp4"));

        TimelineProject original = simpleProject(videoId, 1.5D, 0D, "Snapshot");
        ResponseEntity<String> saved = client.postWithCsrf(
                "/api/timeline-projects", mapper.writeValueAsString(original));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String projectId = mapper.readTree(saved.getBody()).path("id").asText();

        String body = mapper.writeValueAsString(
                new TimelineRenderRequest(projectId, simpleProject("ignored", 0.2D, 0D, "Ignored")));
        ResponseEntity<String> submitted = render(client, body);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String jobId = mapper.readTree(submitted.getBody()).path("id").asText();

        ResponseEntity<String> changed = client.putWithCsrf(
                "/api/timeline-projects/" + projectId,
                mapper.writeValueAsString(simpleProject(videoId, 0.2D, 0D, "Changed")
                        .withMetadata(projectId, 1, null, null)));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);

        String operations = jdbc.queryForObject(
                "SELECT operations FROM jobs WHERE id = ?", String.class, jobId);
        assertThat(operations).contains("\"end\":\"1.5\"").doesNotContain("\"end\":\"0.2\"");

        JsonNode job = awaitTerminal(client, jobId);
        assertThat(job.path("status").asText()).isEqualTo("completed");
        Path output = decryptOutput(jobId);
        MediaMetadata metadata = ffprobe.probe(output);
        assertThat(metadata.duration()).isGreaterThan(1D);
        ffmpeg.verify(output);
    }

    @Test
    void composeRenderMixesVisualTracksAndAudioWithRequestedOutputShape() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String baseId = upload(client, "render-base.mp4", MediaTestSupport.generateAudioVideo(
                tempDir.resolve("fixtures"), "render-base.mp4"));
        String overlayId = upload(client, "render-overlay.mp4", MediaTestSupport.generateVideo(
                tempDir.resolve("fixtures"), "render-overlay.mp4"));

        TimelineClip baseClip = new TimelineClip("c1", "video", baseId, 0D, 2D, 0D,
                1D, 1D, 0D, 0D, 1D, null);
        TimelineClip overlayClip = new TimelineClip("c2", "image", overlayId, 0D, 1D, 0.3D,
                1D, 1D, 24D, 18D, 0.5D, null);
        TimelineClip audioClip = new TimelineClip("c3", "audio", baseId, 0D, 2D, 0D,
                1D, 1D, 0D, 0D, 1D, null);
        TimelineProject project = new TimelineProject(
                null, "Compose", 1,
                List.of(
                        new TimelineTrack("v1", "video", null, 0, true, false, List.of(baseClip)),
                        new TimelineTrack("i1", "image", null, 1, true, false, List.of(overlayClip)),
                        new TimelineTrack("a1", "audio", null, 2, true, false, List.of(audioClip))),
                new RenderSettings(320, 240, 15, 0, 0), 0, null, null);

        ResponseEntity<String> submitted = render(client, mapper.writeValueAsString(
                new TimelineRenderRequest(null, project)));
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String jobId = mapper.readTree(submitted.getBody()).path("id").asText();
        JsonNode operations = mapper.readTree(jdbc.queryForObject(
                "SELECT operations FROM jobs WHERE id = ?", String.class, jobId));
        JsonNode compose = operations.path("operations").get(0).path("compose");
        assertThat(operations.path("operations").get(0).path("type").asText()).isEqualTo("compose");
        assertThat(compose.path("width").asInt()).isEqualTo(320);
        assertThat(compose.path("height").asInt()).isEqualTo(240);
        assertThat(compose.path("fps").asInt()).isEqualTo(15);
        assertThat(compose.path("hasAudio").asBoolean()).isTrue();
        assertThat(compose.path("layers").size()).isEqualTo(2);
        assertThat(compose.path("audio").size()).isEqualTo(2);

        JsonNode job = awaitTerminal(client, jobId);
        assertThat(job.path("status").asText()).isEqualTo("completed");
        Path output = decryptOutput(jobId);
        MediaMetadata metadata = ffprobe.probe(output);
        assertThat(metadata.width()).isEqualTo(320);
        assertThat(metadata.height()).isEqualTo(240);
        assertThat(metadata.hasVideo()).isTrue();
        assertThat(metadata.hasAudio()).isTrue();
        ffmpeg.verify(output);
        assertNoScratch();
    }

    @Test
    void validationAndBuildErrorsUseExactGoMessages() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        TestHttpClient other = MediaTestSupport.authenticatedClient(restTemplate);
        String videoId = upload(client, "render-validation.mp4", MediaTestSupport.generateVideo(
                tempDir.resolve("fixtures"), "render-validation.mp4"));

        ResponseEntity<String> missing = render(client, "{}");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody()).isEqualTo(
                "{\"error\":\"a project or projectId is required\"}\n");

        ResponseEntity<String> unknown = render(client, "{\"projectId\":\"missing\"}");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody()).isEqualTo("{\"error\":\"timeline not found\"}\n");

        ResponseEntity<String> crossUser = render(other, mapper.writeValueAsString(
                new TimelineRenderRequest(null, simpleProject(videoId, 1D, 0D, "Stolen"))));
        assertThat(crossUser.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode crossUserJson = mapper.readTree(crossUser.getBody());
        assertThat(crossUserJson.path("validationErrors").get(0).path("code").asText())
                .isEqualTo("asset_unavailable");

        TimelineProject audioOnly = new TimelineProject(
                null, "Audio only", 1,
                List.of(new TimelineTrack("a", "audio", null, 0, true, false,
                        List.of(new TimelineClip("c", "audio", videoId, 0D, 1D, 0D,
                                1D, 1D, 0D, 0D, 1D, null)))),
                RenderSettings.empty(), 0, null, null);
        ResponseEntity<String> noVideo = render(client, mapper.writeValueAsString(
                new TimelineRenderRequest(null, audioOnly)));
        assertThat(noVideo.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(noVideo.getBody()).isEqualTo(
                "{\"error\":\"the project has no visible video clip to render\"}\n");

        ResponseEntity<String> notAtOrigin = render(client, mapper.writeValueAsString(
                new TimelineRenderRequest(null, simpleProject(videoId, 1D, 0.5D, "Offset"))));
        assertThat(notAtOrigin.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(notAtOrigin.getBody()).isEqualTo(
                "{\"error\":\"the renderer needs a video clip that starts at the timeline origin\"}\n");

        jdbc.update("UPDATE videos SET original_path = ? WHERE id = ?",
                tempDir.resolve("deleted.mp4").toString(), videoId);
        ResponseEntity<String> gone = render(client, mapper.writeValueAsString(
                new TimelineRenderRequest(null, simpleProject(videoId, 1D, 0D, "Gone"))));
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(gone.getBody()).isEqualTo(
                "{\"error\":\"the original file is no longer available\"}\n");
    }

    @Test
    void activeJobAndQuotaConflictsMatchGoMessagesThenCancellationStopsTheWorker() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String firstId = upload(client, "render-long-1.mp4", MediaTestSupport.generateLongVideo(
                tempDir.resolve("fixtures"), "render-long-1.mp4", 25));
        String secondId = upload(client, "render-long-2.mp4", MediaTestSupport.generateLongVideo(
                tempDir.resolve("fixtures"), "render-long-2.mp4", 25));

        manager.stop();
        String jobId;
        try {
            ResponseEntity<String> first = render(client, mapper.writeValueAsString(
                    new TimelineRenderRequest(null, simpleProject(firstId, 20D, 0D, "Long 1"))));
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            jobId = mapper.readTree(first.getBody()).path("id").asText();

            ResponseEntity<String> duplicate = render(client, mapper.writeValueAsString(
                    new TimelineRenderRequest(null, simpleProject(firstId, 20D, 0D, "Long 1 again"))));
            assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(duplicate.getBody()).isEqualTo(
                    "{\"error\":\"the source video already has an active job\"}\n");

            ResponseEntity<String> quota = render(client, mapper.writeValueAsString(
                    new TimelineRenderRequest(null, simpleProject(secondId, 20D, 0D, "Long 2"))));
            assertThat(quota.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(quota.getBody()).isEqualTo(
                    "{\"error\":\"too many active jobs; try again later\"}\n");
        } finally {
            manager.start();
        }

        awaitStatus(client, jobId, "processing", 30_000L);
        ResponseEntity<String> cancelled = client.postWithCsrf("/api/jobs/" + jobId + "/cancel", "{}");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode terminal = awaitTerminal(client, jobId);
        assertThat(terminal.path("status").asText()).isEqualTo("cancelled");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(1) FROM jobs WHERE id = ? AND finished_at IS NOT NULL",
                Integer.class, jobId)).isEqualTo(1);
        assertNoScratch();
    }

    @Test
    void interruptedTimelineJobIsRecoveredAndRerunFromItsSnapshot() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String videoId = upload(client, "render-recovered.mp4", MediaTestSupport.generateVideo(
                tempDir.resolve("fixtures"), "render-recovered.mp4"));

        manager.stop();
        String jobId;
        try {
            ResponseEntity<String> submitted = render(client, mapper.writeValueAsString(
                    new TimelineRenderRequest(null, simpleProject(videoId, 1.5D, 0D, "Recovered"))));
            assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            jobId = mapper.readTree(submitted.getBody()).path("id").asText();

            jdbc.update("UPDATE jobs SET status = 'processing', started_at = ?, "
                            + "execution_token = ?, attempt = 1, worker_id = 'dead-worker' WHERE id = ?",
                    "2026-01-02 03:04:05", "stale-token", jobId);
            Path orphan = storage.tempDir().resolve("processing-orphan");
            Files.createDirectories(orphan.resolve("plaintext"));
            recovery.recoverOnStartup();
            assertThat(Files.exists(orphan)).isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM jobs WHERE id = ?", String.class, jobId)).isEqualTo("queued");
        } finally {
            manager.start();
        }

        JsonNode job = awaitTerminal(client, jobId);
        assertThat(job.path("status").asText()).isEqualTo("completed");
        assertThat(jdbc.queryForObject(
                "SELECT attempt FROM jobs WHERE id = ?", Integer.class, jobId)).isGreaterThanOrEqualTo(2);
        Path output = decryptOutput(jobId);
        ffmpeg.verify(output);
    }

    private String upload(TestHttpClient client, String filename, Path fixture) throws Exception {
        ResponseEntity<String> response = client.postMultipart(
                "/api/upload", "video", filename, Files.readAllBytes(fixture), true, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(response.getBody()).path("id").asText();
    }

    private ResponseEntity<String> render(TestHttpClient client, String body) {
        return client.postWithCsrf("/api/timeline/render", body);
    }

    private static TimelineProject simpleProject(String assetId, double sourceOut,
                                                 double timelineStart, String name) {
        return new TimelineProject(
                null, name, 1,
                List.of(new TimelineTrack("v1", "video", null, 0, true, false,
                        List.of(new TimelineClip("c1", "video", assetId, 0D, sourceOut,
                                timelineStart, 1D, 1D, 0D, 0D, 1D, null)))),
                RenderSettings.empty(), 0, null, null);
    }

    private JsonNode awaitTerminal(TestHttpClient client, String jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> response = client.get("/api/jobs/" + jobId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            last = mapper.readTree(response.getBody());
            if (JobState.isTerminal(last.path("status").asText())) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("job " + jobId + " did not terminate: " + last);
    }

    private void awaitStatus(TestHttpClient client, String jobId, String status, long timeoutMillis)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> response = client.get("/api/jobs/" + jobId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            last = mapper.readTree(response.getBody());
            if (status.equals(last.path("status").asText())) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " never became " + status + ": " + last);
    }

    private Path decryptOutput(String jobId) throws Exception {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT output_path, enc_key, encrypted FROM jobs WHERE id = ?", jobId);
        assertThat(row.get("encrypted")).isEqualTo(1);
        Path stored = Path.of((String) row.get("output_path"));
        byte[] raw = Files.readAllBytes(stored);
        assertThat(new String(raw, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("MOTIONE1");

        Path decoded = tempDir.resolve("fixtures").resolve("decoded-" + jobId + ".mp4");
        byte[] dek = storage.crypto().unwrapDek((String) row.get("enc_key"));
        try {
            storage.crypto().decryptFile(stored, decoded, dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
        return decoded;
    }

    private void assertEncryptedAtRest(String path) throws Exception {
        assertThat(Files.readString(Path.of(path), StandardCharsets.ISO_8859_1)).startsWith("MOTIONE1");
    }

    private void assertNoScratch() throws Exception {
        try (var paths = Files.list(tempDir.resolve("data/tmp"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith("processing-")))
                    .isEmpty();
        }
    }
}
