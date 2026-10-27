package com.motion.backend.web;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Confirms configured quotas surface through the real upload endpoint with Go's message. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.register=10000",
                "motion.quota.max-source-files-per-user=1"
        }
)
class UploadQuotaIntegrationTest {

    @TempDir
    static Path tempDir;

    private static Path videoPath;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("quota.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void generateFixture() throws Exception {
        videoPath = MediaTestSupport.generateVideo(tempDir.resolve("fixtures"), "quota.mp4");
    }

    @Test
    void secondOwnedUploadIsRejectedBySourceFileQuota() throws Exception {
        TestHttpClient client = MediaTestSupport.authenticatedClient(restTemplate);
        byte[] video = MediaTestSupport.bytes(videoPath);

        ResponseEntity<String> first = client.postMultipart(
                "/api/upload", "video", "quota.mp4", video, true, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = client.postMultipart(
                "/api/upload", "video", "quota.mp4", video, true, null);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody()).isEqualTo(
                "{\"error\":\"quota exceeded: source file limit reached\"}\n");

        assertThat(client.get("/api/videos").getBody()).contains("\"id\"");
    }
}
