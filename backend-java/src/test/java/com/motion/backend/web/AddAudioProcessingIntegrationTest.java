package com.motion.backend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.processing.FFprobeService;
import com.motion.backend.processing.JobState;
import com.motion.backend.processing.MediaMetadata;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real FFmpeg regression coverage for add_audio/replace_audio across channel layouts and offsets. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.rate-limit.reset=10000",
                "motion.rate-limit.verify=10000",
                "motion.jobs.poll-interval-ms=20",
                "motion.jobs.progress-throttle-ms=50",
                "motion.jobs.worker-count=1",
                "motion.jobs.max-concurrent-jobs-per-user=10",
                "motion.jobs.max-global-concurrent-jobs=10",
                "motion.jobs.max-queued-jobs=20",
                "motion.jobs.queue-capacity=20"
        }
)
class AddAudioProcessingIntegrationTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FFprobeService ffprobe;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("audio-jobs.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
        registry.add("motion.storage.encryption-enabled", () -> "false");
    }

    @BeforeAll
    static void requireFfmpeg() {
        MediaTestSupport.assumeFfmpegAvailable();
    }

    @Test
    void addAudioMixesMonoSourceWithOffsetAndPreservesDecodability() throws Exception {
        runAudioCase("mono", "add_audio", "0.5", true);
    }

    @Test
    void addAudioMixesStereoSourceWithDifferentOffset() throws Exception {
        runAudioCase("stereo", "add_audio", "1.0", true);
    }

    @Test
    void addAudioMixesMultichannelSourceWithOffset() throws Exception {
        runAudioCase("5.1", "add_audio", "0.25", true);
    }

    @Test
    void replaceAudioReplacesExistingTrackWithMonoSourceAndOffset() throws Exception {
        runAudioCase("mono", "replace_audio", "0.5", true);
    }

    @Test
    void replaceAudioReplacesTrackWithStereoSourceAndOffset() throws Exception {
        runAudioCase("stereo", "replace_audio", "0.75", true);
    }

    @Test
    void replaceAudioReplacesTrackWithMultichannelSourceAndOffset() throws Exception {
        runAudioCase("5.1", "replace_audio", "0.4", true);
    }

    @Test
    void addAudioAddsTrackToSilentVideo() throws Exception {
        runAudioCase("stereo", "add_audio", "0.5", false);
    }

    @Test
    void replaceAudioAddsTrackToSilentVideo() throws Exception {
        runAudioCase("mono", "replace_audio", "0.3", false);
    }

    private void runAudioCase(String layout, String type, String offset, boolean baseHasAudio) throws Exception {
        Path fixtures = tempDir.resolve("fixtures-" + layout + "-" + type + "-" + offset.replace('.', '_')
                + "-" + (baseHasAudio ? "mix" : "silent"));
        Files.createDirectories(fixtures);
        Path base = baseHasAudio
                ? generateAudioVideo(fixtures, "base.mp4", "stereo")
                : generateSilentVideo(fixtures, "base.mp4");
        Path source = generateAudioVideo(fixtures, "source.mp4", layout);

        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        String baseId = uploadId(client, "base.mp4", Files.readAllBytes(base));
        String sourceId = uploadId(client, "source.mp4", Files.readAllBytes(source));

        String request = "{\"operations\":[{\"type\":\"" + type + "\",\"audioSourceId\":\"" + sourceId
                + "\",\"audioVolume\":0.8,\"offset\":\"" + offset + "\",\"audioStart\":\"0\",\"audioEnd\":\"1.0\"}]}";
        ResponseEntity<String> submitted = client.postWithCsrf("/api/videos/" + baseId + "/process", request);
        assertThat(submitted.getStatusCode()).as(type + " " + layout + " submit: " + submitted.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);
        String jobId = objectMapper.readTree(submitted.getBody()).path("id").asText();
        JsonNode job = awaitTerminal(client, jobId);
        assertThat(job.path("status").asText()).as("job %s body=%s", jobId, job).isEqualTo("completed");
        assertThat(job.path("hasOutput").asBoolean()).isTrue();

        String storedPath = jdbc.queryForObject("SELECT output_path FROM jobs WHERE id = ?",
                String.class, jobId);
        assertThat(storedPath).isNotBlank();
        Path output = Path.of(storedPath);
        assertThat(output).exists();
        assertThat(Files.size(output)).isPositive();

        assertDecodable(output);
        MediaMetadata metadata = ffprobe.probe(output);
        assertThat(metadata).as("probe metadata for %s %s", type, layout).isNotNull();
        assertThat(metadata.hasAudio()).as("output has audio for %s %s", type, layout).isTrue();
        assertThat(metadata.duration()).as("output duration for %s %s", type, layout).isPositive();

        // Replace path must carry the added/replacement audio stream; mixed path must retain audio.
        // Channel characteristics can be normalized by encoding/amix, but output must be a valid,
        // decodable media file with an audio stream for every supported input layout.
        if ("replace_audio".equals(type) && !baseHasAudio) {
            // Added onto silent base: duration should cover the delayed source window.
            assertThat(metadata.duration()).isGreaterThan(0.5D);
        }
        assertNoScratch();
    }

    private void assertDecodable(Path output) throws Exception {
        ExecResult result = exec("ffmpeg", "-v", "error", "-i", output.toString(), "-f", "null", "-");
        assertThat(result.exit()).as("ffmpeg decode %s stderr=%s", output, result.output()).isZero();
        assertThat(result.output()).as("ffmpeg decode stderr for %s", output).isBlank();
    }

    private void assertNoScratch() throws IOException {
        Path scratchRoot = tempDir.resolve("data/tmp");
        if (!Files.isDirectory(scratchRoot)) {
            return;
        }
        try (var entries = Files.list(scratchRoot)) {
            var lingering = entries.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("processing-"))
                    .toList();
            assertThat(lingering).isEmpty();
        }
    }

    private String uploadId(TestHttpClient client, String filename, byte[] content) throws Exception {
        ResponseEntity<String> response = client.postMultipart(
                "/api/upload", "video", filename, content, true, null);
        assertThat(response.getStatusCode()).as("upload %s: %s", filename, response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        String id = objectMapper.readTree(response.getBody()).path("id").asText();
        assertThat(id).isNotBlank();
        return id;
    }

    private JsonNode awaitTerminal(TestHttpClient client, String jobId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            ResponseEntity<String> response = client.get("/api/jobs/" + jobId);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            last = objectMapper.readTree(response.getBody());
            String status = last.path("status").asText();
            if (JobState.isTerminal(status)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("job " + jobId + " did not reach a terminal state: " + last);
    }

    private static Path generateSilentVideo(Path directory, String name) throws Exception {
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        exec("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10",
                "-t", "2", "-c:v", "mpeg4", "-q:v", "5", target.toString()).assertSuccess();
        assertThat(Files.size(target)).isPositive();
        return target;
    }

    private static Path generateAudioVideo(Path directory, String name, String layout) throws Exception {
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        // Deterministic 440 Hz tone with selected channel layout.
        String ac = switch (layout) {
            case "mono" -> "1";
            case "stereo" -> "2";
            case "5.1" -> "6";
            default -> throw new IllegalArgumentException("unsupported layout: " + layout);
        };
        exec("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "2", "-c:v", "mpeg4", "-q:v", "5",
                "-c:a", "aac", "-b:a", "128k", "-ac", ac, "-shortest", target.toString()).assertSuccess();
        assertThat(Files.size(target)).isPositive();
        return target;
    }

    private record ExecResult(int exit, String output) {
        void assertSuccess() {
            assertThat(exit).as("command failed: %s", output).isZero();
        }
    }

    private static ExecResult exec(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecResult(-1, output + "\n<timed out>");
            }
            return new ExecResult(process.exitValue(), output);
        } catch (IOException exception) {
            return new ExecResult(-1, exception.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ExecResult(-1, exception.toString());
        }
    }
}
