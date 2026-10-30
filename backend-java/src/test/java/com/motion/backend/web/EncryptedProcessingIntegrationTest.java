package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.media.MediaCrypto;
import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaStore;
import com.motion.backend.media.StorageService;
import com.motion.backend.processing.ComposeAudio;
import com.motion.backend.processing.ComposeLayer;
import com.motion.backend.processing.ComposePlan;
import com.motion.backend.processing.FFprobeService;
import com.motion.backend.processing.JobRecord;
import com.motion.backend.processing.JobState;
import com.motion.backend.processing.MediaMetadata;
import com.motion.backend.processing.Operation;
import com.motion.backend.processing.ProcessingRequest;
import com.motion.backend.processing.ProcessingResult;
import com.motion.backend.processing.ProcessingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Encryption-on processing coverage and direct engine coverage for internally generated compose plans. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.rate-limit.reset=10000",
                "motion.rate-limit.verify=10000",
                "motion.upload.max-bytes=33554432",
                "spring.servlet.multipart.max-file-size=40MB",
                "spring.servlet.multipart.max-request-size=40MB",
                "server.tomcat.max-http-form-post-size=45MB",
                "server.tomcat.max-swallow-size=45MB"
        }
)
class EncryptedProcessingIntegrationTest {

    private static final String MASTER_KEY = MediaCrypto.generateMasterKey();

    @TempDir
    static Path tempDir;

    private static Path videoPath;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("encrypted-process.db"));
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
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private StorageService storage;
    @Autowired
    private MediaStore mediaStore;
    @Autowired
    private ProcessingService processing;
    @Autowired
    private FFprobeService ffprobe;

    @BeforeAll
    static void generateFixture() throws Exception {
        MediaTestSupport.assumeFfmpegAvailable();
        videoPath = MediaTestSupport.generateAudioVideo(tempDir.resolve("fixtures"), "audio.mp4");
    }

    @Test
    void processedOutputIsEncryptedAtRestAndStreamsDecrypted() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String id = uploadId(client, MediaTestSupport.bytes(videoPath));
        ResponseEntity<String> response = client.postWithCsrf("/api/videos/" + id + "/process",
                "{\"operations\":[{\"type\":\"trim\",\"start\":\"0.2\",\"end\":\"1\"}]}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String jobId = mapper.readTree(response.getBody()).path("id").asText();
        JsonNode job = awaitTerminal(client, jobId);
        assertThat(job.path("status").asText()).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT encrypted FROM jobs WHERE id = ?", Integer.class, jobId))
                .isEqualTo(1);

        Path stored = tempDir.resolve("outputs").resolve(jobId + ".mp4");
        byte[] raw = Files.readAllBytes(stored);
        assertThat(new String(raw, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("MOTIONE1");
        assertThat(new String(raw, StandardCharsets.ISO_8859_1)).doesNotContain("ftyp");

        ResponseEntity<byte[]> streamed = client.getBytes("/api/jobs/" + jobId + "/output", new HttpHeaders());
        assertThat(streamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] decoded = streamed.getBody();
        assertThat(decoded).isNotNull();
        assertThat(new String(decoded, 4, 4, StandardCharsets.US_ASCII)).isEqualTo("ftyp");

        assertNoScratch();
    }

    @Test
    void engineRendersInternalComposePlanWithVideoTextAndAudio() throws Exception {
        MediaRecord media = uploadRecord();
        ComposePlan plan = new ComposePlan(320, 240, 30, "1",
                List.of(
                        new ComposeLayer("video", "", null, "0", "1", "0", "1",
                                null, null, null, 1D, true),
                        new ComposeLayer("text", null, "hello", null, null, "0.2", "0.8",
                                20D, 30D, 1D, 1D, false)),
                List.of(new ComposeAudio("", "0", "1", "0", 1D)), true);
        String jobId = UUID.randomUUID().toString();
        JobRecord job = new JobRecord(jobId, media.id(), "audio.mp4", media.originalPath(),
                "[]", "processing", 0, null, null, null, null, null, null, media.userId(), false, null);

        ProcessingResult result = processing.process(job, media,
                new ProcessingRequest(List.of(new Operation("compose", null, null, null, null,
                        null, null, null, null, null, null, plan))), value -> { });

        assertThat(result.encrypted()).isTrue();
        assertThat(result.encKey()).isNotBlank();
        Path decrypted = tempDir.resolve("fixtures").resolve("compose-decoded.mp4");
        byte[] dek = storage.crypto().unwrapDek(result.encKey());
        try {
            storage.crypto().decryptFile(Path.of(result.outputPath()), decrypted, dek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
        MediaMetadata metadata = ffprobe.probe(decrypted);
        assertThat(metadata.width()).isEqualTo(320);
        assertThat(metadata.height()).isEqualTo(240);
        assertThat(metadata.hasVideo()).isTrue();
        assertThat(metadata.hasAudio()).isTrue();
        assertThat(metadata.duration()).isPositive();
        assertNoScratch();
    }

    @Test
    void publicComposeRemainsRejectedByValidation() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String id = uploadId(client, MediaTestSupport.bytes(videoPath));
        ResponseEntity<String> response = client.postWithCsrf("/api/videos/" + id + "/process",
                "{\"operations\":[{\"type\":\"compose\",\"compose\":{\"layers\":[]}}]}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(response.getBody()).path("error").asText())
                .isEqualTo("operation 0 unknown type=compose");
    }

    private MediaRecord uploadRecord() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String id = uploadId(client, MediaTestSupport.bytes(videoPath));
        return mediaStore.findById(id).orElseThrow();
    }

    private String uploadId(TestHttpClient client, byte[] video) throws Exception {
        ResponseEntity<String> response = client.postMultipart("/api/upload", "video", "audio.mp4",
                video, true, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(response.getBody()).path("id").asText();
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
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " did not reach a terminal state: " + last);
    }

    private void assertNoScratch() throws Exception {
        try (var paths = Files.list(tempDir.resolve("data/tmp"))) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith("processing-")))
                    .isEmpty();
        }
    }
}
