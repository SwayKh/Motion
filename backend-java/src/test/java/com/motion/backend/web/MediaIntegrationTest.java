package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.time.LegacyTimestamp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-HTTP compatibility tests for the Phase 2 media lifecycle. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.rate-limit.reset=10000",
                "motion.rate-limit.verify=10000",
                "motion.upload.max-bytes=1048576",
                "spring.servlet.multipart.max-file-size=3MB",
                "spring.servlet.multipart.max-request-size=3MB",
                "server.tomcat.max-http-form-post-size=10MB",
                "server.tomcat.max-swallow-size=10MB"
        }
)
class MediaIntegrationTest {

    @TempDir
    static Path tempDir;

    private static Path videoPath;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("media.db"));
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
    private ObjectMapper objectMapper;

    @BeforeAll
    static void generateFixture() throws Exception {
        videoPath = MediaTestSupport.generateVideo(tempDir.resolve("fixtures"), "sample.mp4");
    }

    @Test
    void uploadListMetadataAndStreamingUseGoWireContract() throws Exception {
        TestHttpClient client = authenticatedClient();
        byte[] video = MediaTestSupport.bytes(videoPath);

        ResponseEntity<String> uploaded = upload(client, "sample.mp4", video);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = uploaded.getBody();
        assertThat(body).isNotNull();
        JsonNode json = objectMapper.readTree(body);
        String id = json.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(body).startsWith("{\"id\":\"" + id + "\",\"name\":\"sample.mp4\","
                + "\"status\":\"uploaded\",\"progress\":0,\"duration\":");
        assertThat(body).contains("\"width\":160,\"height\":120,\"fileSize\":" + video.length + ",\"createdAt\":");
        assertThat(body).endsWith("\"hasOriginal\":true,\"hasOutput\":false}\n");
        assertThat(body).doesNotContain("\"error\"").doesNotContain("\"outputType\"");
        assertThat(json.path("duration").asDouble()).isPositive();

        for (String path : new String[]{"/api/videos", "/api/videos"}) {
            ResponseEntity<String> list = client.get(path);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode items = objectMapper.readTree(list.getBody());
            assertThat(items.isArray()).isTrue();
            assertThat(items).hasSize(1);
            assertThat(items.get(0).path("id").asText()).isEqualTo(id);
        }
        assertThat(client.get("/api/videos/" + id).getBody()).isEqualTo(body);

        for (String prefix : new String[]{"/api"}) {
            String originalPath = prefix + "/videos/" + id + "/original";
            ResponseEntity<byte[]> full = client.getBytes(originalPath, new HttpHeaders());
            assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(full.getBody()).isEqualTo(video);
            assertThat(full.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("video/mp4");
            assertThat(full.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .isEqualTo("inline; filename=sample.mp4");
            assertThat(full.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
            assertThat(full.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isNull();
            assertThat(full.getHeaders().getLastModified()).isNegative();
            assertThat(full.getHeaders().getETag()).isNull();

            HttpHeaders range = new HttpHeaders();
            range.set(HttpHeaders.RANGE, "bytes=10-19");
            ResponseEntity<byte[]> partial = client.getBytes(originalPath, range);
            assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
            assertThat(partial.getBody()).isEqualTo(Arrays.copyOfRange(video, 10, 20));
            assertThat(partial.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                    .isEqualTo("bytes 10-19/" + video.length);

            HttpHeaders multi = new HttpHeaders();
            multi.set(HttpHeaders.RANGE, "bytes=0-3,8-11");
            ResponseEntity<byte[]> multipart = client.getBytes(originalPath, multi);
            assertThat(multipart.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
            assertThat(multipart.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith("multipart/byteranges");
            assertThat(new String(multipart.getBody())).contains("bytes 0-3/", "bytes 8-11/");

            HttpHeaders invalid = new HttpHeaders();
            invalid.set(HttpHeaders.RANGE, "bytes=" + (video.length + 100) + "-");
            ResponseEntity<byte[]> unsatisfiable = client.getBytes(originalPath, invalid);
            assertThat(unsatisfiable.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            assertThat(unsatisfiable.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                    .isEqualTo("bytes */" + video.length);

            ResponseEntity<String> head = client.head(originalPath, new HttpHeaders());
            assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(head.getBody()).isNullOrEmpty();
            assertThat(head.getHeaders().getContentLength()).isEqualTo(video.length);
        }

        assertThat(delete(client, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void outputAndDownloadUseDerivedFilenameAndInlineOrAttachmentDisposition() throws Exception {
        TestHttpClient client = authenticatedClient();
        String id = uploadId(client, "sample.mp4", MediaTestSupport.bytes(videoPath));
        byte[] output = "edited-video-bytes".getBytes();
        Path outputPath = tempDir.resolve("outputs").resolve("manual-" + id + ".mp4");
        Files.createDirectories(outputPath.getParent());
        Files.write(outputPath, output);
        jdbc.update("UPDATE videos SET status = 'completed', output_path = ? WHERE id = ?",
                outputPath.toString(), id);

        JsonNode metadata = objectMapper.readTree(client.get("/api/videos/" + id).getBody());
        assertThat(metadata.path("hasOutput").asBoolean()).isTrue();
        assertThat(metadata.path("outputType").asText()).isEqualTo("video");

        for (String prefix : new String[]{"/api"}) {
            ResponseEntity<byte[]> inline = client.getBytes(prefix + "/videos/" + id + "/output", new HttpHeaders());
            assertThat(inline.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(inline.getBody()).isEqualTo(output);
            assertThat(inline.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .isEqualTo("inline; filename=sample-edited.mp4");

            ResponseEntity<byte[]> attachment = client.getBytes(
                    prefix + "/videos/" + id + "/download", new HttpHeaders());
            assertThat(attachment.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(attachment.getBody()).isEqualTo(output);
            assertThat(attachment.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .isEqualTo("attachment; filename=sample-edited.mp4");
        }
    }

    @Test
    void crossUserAccessReturnsNotFoundForEveryOwnedRoute() throws Exception {
        TestHttpClient owner = authenticatedClient();
        TestHttpClient other = authenticatedClient();
        String id = uploadId(owner, "sample.mp4", MediaTestSupport.bytes(videoPath));

        assertThat(other.get("/api/videos").getBody()).isEqualTo("[]\n");
        assertThat(other.get("/api/videos/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.get("/api/videos/" + id).getBody()).isEqualTo("{\"error\":\"video not found\"}\n");
        assertThat(other.getBytes("/api/videos/" + id + "/original", new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.getBytes("/api/videos/" + id + "/output", new HttpHeaders()).getBody())
                .isEqualTo("{\"error\":\"video not found\"}\n".getBytes());
        assertThat(other.getBytes("/api/videos/" + id + "/download", new HttpHeaders()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.deleteWithCsrf("/api/videos/" + id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(delete(owner, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void uploadValidationErrorsMatchGoMessages() throws Exception {
        TestHttpClient client = authenticatedClient();
        byte[] video = MediaTestSupport.bytes(videoPath);
        byte[] arbitrary = new byte[2 * 1024 * 1024];
        Arrays.fill(arbitrary, (byte) 'x');

        assertError(client.postMultipart("/api/upload", "other", "sample.mp4", video, true, null),
                HttpStatus.BAD_REQUEST, "missing or invalid 'video' file field");
        assertError(client.post("/api/upload", "{}", true, null),
                HttpStatus.BAD_REQUEST, "invalid multipart upload");
        assertError(upload(client, "sample.txt", video), HttpStatus.BAD_REQUEST,
                "unsupported file type .txt; use MP4, MOV, MKV, WebM, AVI, M4V, MPEG, or 3GP");
        assertError(upload(client, "sample", video), HttpStatus.BAD_REQUEST,
                "file must have a file extension");
        assertError(upload(client, "broken.mp4", "not a video".getBytes()), HttpStatus.BAD_REQUEST,
                "unable to read video metadata; the file may be unsupported or corrupt");
        assertError(upload(client, "large.mp4", arbitrary), HttpStatus.BAD_REQUEST,
                "file exceeds the 1 MB limit");

        byte[] oversizedBody = new byte[4 * 1024 * 1024];
        Arrays.fill(oversizedBody, (byte) 'y');
        assertError(upload(client, "huge.mp4", oversizedBody),
                HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds the 150 MB limit");

        TestHttpClient anonymous = new TestHttpClient(restTemplate);
        assertError(upload(anonymous, "sample.mp4", video),
                HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    @Test
    void duplicateNamesAndTraversalFilenamesAreStoredSafely() throws Exception {
        TestHttpClient client = authenticatedClient();
        byte[] video = MediaTestSupport.bytes(videoPath);

        String firstId = uploadId(client, "sample.mp4", video);
        String secondId = uploadId(client, "sample.mp4", video);
        assertThat(firstId).isNotEqualTo(secondId);

        ResponseEntity<String> traversal = upload(client, "../../evil.mp4", video);
        assertThat(traversal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(traversal.getBody());
        assertThat(json.path("name").asText()).isEqualTo("evil.mp4");
        String traversalId = json.path("id").asText();

        assertThat(tempDir.resolve("evil.mp4")).doesNotExist();
        String originalPath = jdbc.queryForObject(
                "SELECT original_path FROM videos WHERE id = ?", String.class, traversalId);
        assertThat(originalPath).isNotNull();
        assertThat(Path.of(originalPath).getParent().toAbsolutePath().normalize())
                .isEqualTo(tempDir.resolve("uploads").toAbsolutePath().normalize());

        ResponseEntity<byte[]> streamed = client.getBytes("/api/videos/" + traversalId + "/original", new HttpHeaders());
        assertThat(streamed.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline; filename=evil.mp4");

        JsonNode list = objectMapper.readTree(client.get("/api/videos").getBody());
        assertThat(list).hasSize(3);
    }

    @Test
    void activeJobBlocksDeletionAndArtifactsAreRemovedBeforeRows() throws Exception {
        TestHttpClient client = authenticatedClient();
        String id = uploadId(client, "sample.mp4", MediaTestSupport.bytes(videoPath));
        String jobId = UUID.randomUUID().toString();
        String originalPath = jdbc.queryForObject(
                "SELECT original_path FROM videos WHERE id = ?", String.class, id);

        insertJob(jobId, id, originalPath, "queued");
        assertError(delete(client, id), HttpStatus.CONFLICT,
                "cancel the active job before deleting this video");

        jdbc.update("UPDATE jobs SET status = 'cancelled' WHERE id = ?", jobId);
        Path artifact = tempDir.resolve("outputs").resolve(jobId + ".mp4");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, "artifact".getBytes());
        jdbc.update("UPDATE jobs SET output_path = ? WHERE id = ?", artifact.toString(), jobId);

        assertThat(delete(client, id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(artifact).doesNotExist();
        assertThat(Path.of(originalPath)).doesNotExist();
        assertThat(count("SELECT COUNT(*) FROM videos WHERE id = ?", id)).isZero();
        assertThat(count("SELECT COUNT(*) FROM jobs WHERE id = ?", jobId)).isZero();
    }

    @Test
    void missingFilesAndMissingOutputsReturnGoErrorsButDeleteStillSucceeds() throws Exception {
        TestHttpClient client = authenticatedClient();
        String id = uploadId(client, "sample.mp4", MediaTestSupport.bytes(videoPath));

        assertError(client.getBytes("/api/videos/" + id + "/output", new HttpHeaders()),
                HttpStatus.NOT_FOUND, "processed output is not available");
        assertError(client.getBytes("/api/videos/" + id + "/download", new HttpHeaders()),
                HttpStatus.NOT_FOUND, "processed output is not available");

        String originalPath = jdbc.queryForObject(
                "SELECT original_path FROM videos WHERE id = ?", String.class, id);
        Files.delete(Path.of(originalPath));
        assertError(client.getBytes("/api/videos/" + id + "/original", new HttpHeaders()),
                HttpStatus.NOT_FOUND, "file is no longer available");

        assertThat(delete(client, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private TestHttpClient authenticatedClient() {
        return MediaTestSupport.authenticatedClient(restTemplate);
    }

    private ResponseEntity<String> upload(TestHttpClient client, String filename, byte[] content) {
        return client.postMultipart("/api/upload", "video", filename, content, true, null);
    }

    private String uploadId(TestHttpClient client, String filename, byte[] content) throws IOException {
        ResponseEntity<String> response = upload(client, filename, content);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody()).path("id").asText();
    }

    private ResponseEntity<String> delete(TestHttpClient client, String id) {
        return client.deleteWithCsrf("/api/videos/" + id);
    }

    private void insertJob(String jobId, String videoId, String sourcePath, String status) {
        jdbc.update("INSERT INTO jobs (id, video_id, source_path, operations, status, progress, created_at, user_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, "
                        + "(SELECT user_id FROM videos WHERE id = ?))",
                jobId, videoId, sourcePath, "[]", status, 0D, LegacyTimestamp.format(Instant.now()), videoId);
    }

    private int count(String sql, String id) {
        Integer value = jdbc.queryForObject(sql, Integer.class, id);
        return value == null ? 0 : value;
    }

    private void assertError(ResponseEntity<?> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        String body = response.getBody() instanceof byte[] bytes
                ? new String(bytes)
                : response.getBody().toString();
        assertThat(body).isEqualTo("{\"error\":\"" + message + "\"}\n");
    }
}
