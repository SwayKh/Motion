package com.motion.backend.web;

import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared disposable fixtures for media integration tests. */
public final class MediaTestSupport {

    private MediaTestSupport() {
    }

    public static TestHttpClient authenticatedClient(TestRestTemplate restTemplate) {
        TestHttpClient client = new TestHttpClient(restTemplate);
        String email = "media-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<String> register = client.post("/api/auth/register",
                "{\"email\":\"" + email
                        + "\",\"password\":\"password123\",\"passwordConfirm\":\"password123\"}");
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(client.cookie("motion_session")).isNotNull();

        ResponseEntity<String> csrf = client.get("/api/auth/csrf");
        assertThat(csrf.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(client.cookie("motion_csrf")).isNotNull();
        return client;
    }

    /** Real generated media is required; skip rather than simulate FFmpeg behavior. */
    public static void assumeFfmpegAvailable() {
        Assumptions.assumeTrue(run("ffmpeg", "-version").exit() == 0,
                "FFmpeg/FFprobe n9.0.2 is required for media integration tests");
    }

    /** Generates a short, real, probeable video without audio; nothing is copied from production data. */
    public static Path generateVideo(Path directory, String name) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        run("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10",
                "-t", "2", "-c:v", "mpeg4", "-q:v", "5", target.toString()).assertSuccess();
        assertThat(Files.size(target)).isPositive();
        return target;
    }

    /** Generates a longer real video so background jobs remain observable while workers run. */
    public static Path generateLongVideo(Path directory, String name, int seconds) throws IOException, InterruptedException {
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        run("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=640x360:rate=15",
                "-t", Integer.toString(seconds), "-c:v", "mpeg4", "-q:v", "5", target.toString())
                .assertSuccess();
        assertThat(Files.size(target)).isPositive();
        return target;
    }

    /** Generates a real video with a 440 Hz audio track for extraction, mixing, and timeline tests. */
    public static Path generateAudioVideo(Path directory, String name) throws Exception {
        Files.createDirectories(directory);
        Path target = directory.resolve(name);
        run("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "2", "-c:v", "mpeg4", "-q:v", "5",
                "-c:a", "aac", "-b:a", "64k", "-shortest", target.toString()).assertSuccess();
        assertThat(Files.size(target)).isPositive();
        return target;
    }

    public static byte[] bytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    private record Result(int exit, String output) {
        void assertSuccess() {
            assertThat(exit).as("fixture generation: %s", output).isZero();
        }
    }

    private static Result run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            return new Result(process.waitFor(), output);
        } catch (IOException exception) {
            return new Result(-1, exception.toString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Result(-1, exception.toString());
        }
    }
}
