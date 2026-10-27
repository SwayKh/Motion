package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.config.MotionProperties;
import com.motion.backend.media.MediaCrypto;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real-HTTP verification of the Go-compatible encrypted media lifecycle. */
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
class EncryptedMediaIntegrationTest {

    private static final String MASTER_KEY = MediaCrypto.generateMasterKey();

    @TempDir
    static Path tempDir;

    private static Path videoPath;
    private static byte[] video;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("encrypted-media.db"));
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
    private ObjectMapper objectMapper;

    @Autowired
    private MediaCrypto crypto;

    @BeforeAll
    static void generateFixture() throws Exception {
        Files.createDirectories(tempDir.resolve("fixtures"));
        videoPath = tempDir.resolve("fixtures/large.mp4");
        Process process = new ProcessBuilder(
                "ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                "-t", "3", "-c:v", "mpeg4", "-b:v", "20M", "-minrate", "20M",
                "-maxrate", "20M", "-bufsize", "2M", videoPath.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        assertThat(exit).as("ffmpeg fixture generation: %s", output).isZero();
        video = Files.readAllBytes(videoPath);
        assertThat(video.length).isGreaterThan(MediaCrypto.CHUNK_SIZE);
    }

    @Test
    void encryptedUploadStoresCiphertextAndStreamsExactPlaintextRanges() throws Exception {
        TestHttpClient client = authenticatedClient();
        String id = upload(client);

        Stored stored = stored(id, false);
        assertThat(stored.encrypted()).isTrue();
        assertThat(stored.wrappedKey()).isNotBlank();
        byte[] onDisk = Files.readAllBytes(Path.of(stored.path()));
        assertThat(onDisk).startsWith("MOTIONE1".getBytes(StandardCharsets.US_ASCII));
        assertThat(onDisk).isNotEqualTo(video);
        assertThat(onDisk.length).isEqualTo(16 + chunks(video.length) * 20L + video.length);
        assertThat(new String(onDisk, StandardCharsets.ISO_8859_1)).doesNotContain(MASTER_KEY, stored.wrappedKey());

        String originalPath = "/api/videos/" + id + "/original";
        ResponseEntity<byte[]> full = client.getBytes(originalPath, new HttpHeaders());
        assertThat(full.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(full.getBody()).isEqualTo(video);
        assertThat(full.getHeaders().getContentLength()).isEqualTo(video.length);

        int chunkBoundary = MediaCrypto.CHUNK_SIZE;
        assertRange(client, originalPath, chunkBoundary - 8, chunkBoundary + 12);
        assertRange(client, originalPath, video.length - 64, video.length - 1);
        assertRange(client, originalPath, "bytes=-97",
                Arrays.copyOfRange(video, video.length - 97, video.length));

        HttpHeaders multi = new HttpHeaders();
        multi.set(HttpHeaders.RANGE, "bytes=0-15," + (chunkBoundary + 4) + "-" + (chunkBoundary + 19));
        ResponseEntity<byte[]> multipart = client.getBytes(originalPath, multi);
        assertThat(multipart.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(multipart.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith("multipart/byteranges");
        String body = new String(multipart.getBody(), StandardCharsets.ISO_8859_1);
        assertThat(body).contains("bytes 0-15/" + video.length,
                "bytes " + (chunkBoundary + 4) + "-" + (chunkBoundary + 19) + "/" + video.length);

        HttpHeaders invalid = new HttpHeaders();
        invalid.set(HttpHeaders.RANGE, "bytes=" + (video.length + 10) + "-");
        ResponseEntity<byte[]> unsatisfiable = client.getBytes(originalPath, invalid);
        assertThat(unsatisfiable.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(unsatisfiable.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes */" + video.length);

        ResponseEntity<String> head = client.head(originalPath, new HttpHeaders());
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getHeaders().getContentLength()).isEqualTo(video.length);
        assertThat(head.getBody()).isNullOrEmpty();
    }

    @Test
    void processedOutputIsEncryptedAndTransparentlyServed() throws Exception {
        TestHttpClient client = authenticatedClient();
        String id = upload(client);
        byte[] outputBytes = "encrypted-output-payload".getBytes(StandardCharsets.UTF_8);
        Path plainOutput = tempDir.resolve("plain-output.mp4");
        Path cipherOutput = tempDir.resolve("outputs").resolve(id + "-output.mp4");
        Files.createDirectories(cipherOutput.getParent());
        Files.write(plainOutput, outputBytes);
        byte[] dek = crypto.newDek();
        crypto.encryptFile(plainOutput, cipherOutput, dek);
        String wrapped = crypto.wrapDek(dek);
        jdbc.update("UPDATE videos SET status = 'completed', output_path = ?, output_encrypted = 1, "
                        + "output_enc_key = ? WHERE id = ?",
                cipherOutput.toString(), wrapped, id);

        for (String route : List.of("/api/videos/" + id + "/output", "/api/videos/" + id + "/download")) {
            ResponseEntity<byte[]> response = client.getBytes(route, new HttpHeaders());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(outputBytes);
            assertThat(response.getHeaders().getContentLength()).isEqualTo(outputBytes.length);
        }
        assertThat(Files.readAllBytes(cipherOutput)).startsWith("MOTIONE1".getBytes(StandardCharsets.US_ASCII));
        Files.delete(plainOutput);
    }

    @Test
    void wrongWrappedKeyAndCorruptedCiphertextReturnGenericErrors() throws Exception {
        TestHttpClient client = authenticatedClient();
        String id = upload(client);
        Stored stored = stored(id, false);
        String route = "/api/videos/" + id + "/original";

        MotionProperties otherProperties = new MotionProperties();
        otherProperties.getEncryption().setKey(MediaCrypto.generateMasterKey());
        MediaCrypto other = new MediaCrypto(otherProperties);
        byte[] actualDek = crypto.unwrapDek(stored.wrappedKey());
        String wrongWrapped = other.wrapDek(actualDek);
        jdbc.update("UPDATE videos SET enc_key = ? WHERE id = ?", wrongWrapped, id);
        assertGenericFailure(client, route, wrongWrapped);
        jdbc.update("UPDATE videos SET enc_key = ? WHERE id = ?", stored.wrappedKey(), id);

        byte[] ciphertext = Files.readAllBytes(Path.of(stored.path()));
        ciphertext[MediaCrypto.HEADER_SIZE + 4 + 100] ^= 0x40;
        Files.write(Path.of(stored.path()), ciphertext);
        assertGenericFailure(client, route, MASTER_KEY);
    }

    @Test
    void concurrentEncryptedUploadsRemainIsolatedAndCleanUpScratchFiles() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                tasks.add(() -> {
                    TestHttpClient client = authenticatedClient();
                    String id = upload(client);
                    ResponseEntity<byte[]> streamed = client.getBytes(
                            "/api/videos/" + id + "/original", new HttpHeaders());
                    assertThat(streamed.getBody()).isEqualTo(video);
                    return id;
                });
            }
            List<Future<String>> futures = executor.invokeAll(tasks);
            List<String> ids = new ArrayList<>();
            for (Future<String> future : futures) {
                ids.add(future.get());
            }
            assertThat(ids).hasSize(4).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
        }
        assertThat(countFiles(tempDir.resolve("data/tmp"))).isZero();
    }

    @Test
    void deletingEncryptedMediaRemovesCiphertextAndTemporaryFiles() throws Exception {
        TestHttpClient client = authenticatedClient();
        String id = upload(client);
        Stored original = stored(id, false);
        Path output = tempDir.resolve("outputs").resolve(id + "-delete.mp4");
        Files.createDirectories(output.getParent());
        byte[] dek = crypto.newDek();
        crypto.encryptFile(videoPath, output, dek);
        jdbc.update("UPDATE videos SET output_path = ?, output_encrypted = 1, output_enc_key = ? WHERE id = ?",
                output.toString(), crypto.wrapDek(dek), id);

        ResponseEntity<String> deleted = client.deleteWithCsrf("/api/videos/" + id);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Path.of(original.path())).doesNotExist();
        assertThat(output).doesNotExist();
        assertThat(countFiles(tempDir.resolve("data/tmp"))).isZero();
        assertThat(countFiles(tempDir.resolve("data/migration"))).isZero();
    }

    private void assertRange(TestHttpClient client, String path, long start, long end) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, "bytes=" + start + "-" + end);
        ResponseEntity<byte[]> response = client.getBytes(path, headers);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getBody()).isEqualTo(Arrays.copyOfRange(video, (int) start, (int) end + 1));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes " + start + "-" + end + "/" + video.length);
    }

    private void assertRange(TestHttpClient client, String path, String header, byte[] expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RANGE, header);
        ResponseEntity<byte[]> response = client.getBytes(path, headers);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(response.getBody()).isEqualTo(expected);
    }

    private void assertGenericFailure(TestHttpClient client, String path, String secret) {
        ResponseEntity<byte[]> response = client.getBytes(path, new HttpHeaders());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String body = new String(response.getBody() == null ? new byte[0] : response.getBody(),
                StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("{\"error\":\"failed to read file\"}\n");
        assertThat(body).doesNotContain(secret, MASTER_KEY, "MOTIONE1", "GCM", "tag");
    }

    private String upload(TestHttpClient client) throws IOException {
        ResponseEntity<String> response = client.postMultipart(
                "/api/upload", "video", "large.mp4", video, true, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode json = objectMapper.readTree(response.getBody());
        String id = json.path("id").asText();
        assertThat(id).isNotBlank();
        assertThat(json.path("fileSize").asLong()).isEqualTo(video.length);
        return id;
    }

    private Stored stored(String id, boolean output) {
        String pathColumn = output ? "output_path" : "original_path";
        String flagColumn = output ? "output_encrypted" : "encrypted";
        String keyColumn = output ? "output_enc_key" : "enc_key";
        String path = jdbc.queryForObject(
                "SELECT " + pathColumn + " FROM videos WHERE id = ?", String.class, id);
        Integer encrypted = jdbc.queryForObject(
                "SELECT " + flagColumn + " FROM videos WHERE id = ?", Integer.class, id);
        String wrappedKey = jdbc.queryForObject(
                "SELECT " + keyColumn + " FROM videos WHERE id = ?", String.class, id);
        return new Stored(path, encrypted != null && encrypted != 0, wrappedKey);
    }

    private record Stored(String path, boolean encrypted, String wrappedKey) {
    }

    private TestHttpClient authenticatedClient() {
        return MediaTestSupport.authenticatedClient(restTemplate);
    }

    private static long chunks(long plaintextLength) {
        return (plaintextLength + MediaCrypto.CHUNK_SIZE - 1) / MediaCrypto.CHUNK_SIZE;
    }

    private static long countFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.count();
        }
    }
}
