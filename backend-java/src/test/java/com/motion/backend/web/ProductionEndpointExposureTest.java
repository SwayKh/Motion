package com.motion.backend.web;

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

import static org.assertj.core.api.Assertions.assertThat;

/** Production must expose only the Go-compatible /health route, not Actuator or API docs. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=production",
                "motion.env=production"
        }
)
class ProductionEndpointExposureTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("production.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("data/tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("data/previews").toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthRemainsAvailable() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
    }

    @Test
    void actuatorAndDocumentationEndpointsAreAbsent() {
        for (String path : new String[]{"/actuator/health", "/api-docs", "/swagger-ui.html"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).as(path).isEqualTo("404 page not found\n");
        }
    }
}
