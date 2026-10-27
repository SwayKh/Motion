package com.motion.backend.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=test"
)
class HealthControllerTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("health.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthReturnsGoCompatibleResponse() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"ok\"");
    }

    @Test
    void unknownApplicationRouteIsNotFoundAfterPhase4RouterEnablement() {
        ResponseEntity<String> response = restTemplate.getForEntity("/not-a-real-route", String.class);

        // Go's ServeMux emits plain text for unmatched routes; only application handlers use the
        // {"error":...} envelope.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(response.getBody()).isEqualTo("404 page not found\n");
    }

    @Test
    void unsupportedMethodMatchesGoResponse() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/config", org.springframework.http.HttpMethod.PUT, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).isEqualTo("GET, HEAD");
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(response.getBody()).isEqualTo("Method Not Allowed\n");
    }
}
