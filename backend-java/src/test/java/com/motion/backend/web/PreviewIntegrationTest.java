package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.media.MediaCrypto;
import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaStore;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-HTTP preview coverage for cache names, encrypted sources, ownership, and invalidation. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.rate-limit.reset=10000",
                "motion.rate-limit.verify=10000"
        }
)
class PreviewIntegrationTest {

    private static final String MASTER_KEY = MediaCrypto.generateMasterKey();

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("preview.db"));
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
    private MediaStore mediaStore;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void requireFfmpeg() {
        MediaTestSupport.assumeFfmpegAvailable();
    }

    @Test
    void thumbnailsAndWaveformUseCachedJpegAndJsonForEncryptedSource() throws Exception {
        TestHttpClient owner = MediaTestSupport.authenticatedClient(restTemplate);
        Path fixture = MediaTestSupport.generateAudioVideo(
                tempDir.resolve("fixtures"), "preview-audio-" + UUID.randomUUID() + ".mp4");
        String videoId = upload(owner, fixture);

        MediaRecord stored = mediaStore.findById(videoId).orElseThrow();
        assertThat(stored.encrypted()).isTrue();
        assertThat(Files.readString(Path.of(stored.originalPath()), StandardCharsets.ISO_8859_1))
                .startsWith("MOTIONE1");

        ResponseEntity<String> thumbnails = owner.get("/api/videos/" + videoId + "/thumbnails");
        assertThat(thumbnails.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(thumbnails.getBody()).startsWith("{\"count\":12,\"tileWidth\":");
        JsonNode meta = mapper.readTree(thumbnails.getBody());
        assertThat(meta.path("tileHeight").asInt()).isEqualTo(72);
        assertThat(meta.path("width").asInt()).isPositive();
        assertThat(meta.path("duration").asDouble()).isPositive();
        assertThat(meta.path("spriteUrl").asText())
                .isEqualTo("/api/videos/" + videoId + "/thumbnails/sprite?count=12");

        Path strip = tempDir.resolve("data/previews/strip_" + videoId + "_12_72.jpg");
        assertThat(Files.isRegularFile(strip)).isTrue();
        FileTime firstStripWrite = Files.getLastModifiedTime(strip);

        ResponseEntity<byte[]> sprite = owner.getBytes(
                "/api/videos/" + videoId + "/thumbnails/sprite?count=12", new HttpHeaders());
        assertThat(sprite.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sprite.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(sprite.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("private, max-age=3600");
        assertThat(sprite.getBody()).hasSizeGreaterThan(4);
        assertThat(sprite.getBody()[0]).isEqualTo((byte) 0xFF);
        assertThat(sprite.getBody()[1]).isEqualTo((byte) 0xD8);

        ResponseEntity<String> waveform = owner.get("/api/videos/" + videoId + "/waveform?buckets=200");
        assertThat(waveform.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(waveform.getBody()).startsWith("{\"buckets\":200,\"duration\":");
        JsonNode peaks = mapper.readTree(waveform.getBody());
        assertThat(peaks.path("audio").asBoolean()).isTrue();
        assertThat(peaks.path("peaks").size()).isEqualTo(200);
        assertThat(peaks.path("peaks")).anySatisfy(node ->
                assertThat(node.asDouble()).isGreaterThan(0D).isLessThanOrEqualTo(1D));

        Path wave = tempDir.resolve("data/previews/wave_" + videoId + "_200.json");
        assertThat(Files.isRegularFile(wave)).isTrue();
        FileTime firstWaveWrite = Files.getLastModifiedTime(wave);

        // Second requests are served from cache: no regeneration and identical cache timestamps.
        assertThat(owner.get("/api/videos/" + videoId + "/thumbnails?count=12")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(owner.get("/api/videos/" + videoId + "/waveform?buckets=200")
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Files.getLastModifiedTime(strip)).isEqualTo(firstStripWrite);
        assertThat(Files.getLastModifiedTime(wave)).isEqualTo(firstWaveWrite);
    }

    @Test
    void noAudioAndFailedGenerationUseGoMessages() throws Exception {
        TestHttpClient owner = MediaTestSupport.authenticatedClient(restTemplate);
        Path silent = MediaTestSupport.generateVideo(
                tempDir.resolve("fixtures"), "preview-silent-" + UUID.randomUUID() + ".mp4");
        String silentId = upload(owner, silent);

        ResponseEntity<String> waveform = owner.get("/api/videos/" + silentId + "/waveform?buckets=200");
        assertThat(waveform.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = mapper.readTree(waveform.getBody());
        assertThat(json.path("audio").asBoolean()).isFalse();
        assertThat(json.path("peaks").size()).isZero();
        Path wave = tempDir.resolve("data/previews/wave_" + silentId + "_200.json");
        assertThat(Files.readString(wave)).contains("\"audio\":false").contains("\"peaks\":[]");

        Path corrupt = tempDir.resolve("corrupt-" + UUID.randomUUID() + ".mp4");
        Files.write(corrupt, "not a video".getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE videos SET original_path = ?, encrypted = 0, enc_key = NULL WHERE id = ?",
                corrupt.toString(), silentId);
        ResponseEntity<String> failed = owner.get("/api/videos/" + silentId + "/thumbnails");
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failed.getBody()).isEqualTo(
                "{\"error\":\"could not generate thumbnails for this video\"}\n");
        assertThat(owner.get("/api/videos/" + silentId + "/waveform?buckets=99999").getBody())
                .isEqualTo("{\"error\":\"could not analyse the audio for this video\"}\n");
    }

    @Test
    void ownershipAndDeletionFollowGoSemantics() throws Exception {
        TestHttpClient owner = MediaTestSupport.authenticatedClient(restTemplate);
        TestHttpClient other = MediaTestSupport.authenticatedClient(restTemplate);
        Path fixture = MediaTestSupport.generateVideo(
                tempDir.resolve("fixtures"), "preview-owned-" + UUID.randomUUID() + ".mp4");
        String videoId = upload(owner, fixture);

        assertThat(owner.get("/api/videos/" + videoId + "/thumbnails").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(other.get("/api/videos/" + videoId + "/thumbnails").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.get("/api/videos/" + videoId + "/thumbnails").getBody())
                .isEqualTo("{\"error\":\"video not found\"}\n");
        assertThat(other.get("/api/videos/" + videoId + "/waveform").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        Path strip = tempDir.resolve("data/previews/strip_" + videoId + "_12_72.jpg");
        assertThat(Files.isRegularFile(strip)).isTrue();
        ResponseEntity<String> deleted = owner.deleteWithCsrf("/api/videos/" + videoId);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Files.exists(strip)).isFalse();
        assertThat(owner.get("/api/videos/" + videoId + "/thumbnails").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String upload(TestHttpClient client, Path fixture) throws IOException {
        ResponseEntity<String> response = client.postMultipart(
                "/api/upload", "video", fixture.getFileName().toString(),
                Files.readAllBytes(fixture), true, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(response.getBody()).path("id").asText();
    }
}
