package com.motion.backend.web;

import com.motion.backend.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** Failure-path contracts that cannot be produced with the real, healthy AuthService. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=test"
)
class ExceptionHandlingIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("exceptions.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.toString());
    }

    @MockitoBean
    private AuthService authService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void optionalAuthDatabaseFailureFailsOpenLikeGo() {
        when(authService.authenticate(anyString()))
                .thenThrow(new DataRetrievalFailureException("database unavailable"));

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.COOKIE, "motion_session=not-valid");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/config", HttpMethod.GET, new HttpEntity<>(null, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
                "{\"maxDurationSeconds\":300,\"maxUploadBytes\":157286400,\"requireEmailVerification\":false}\n");
    }

    @Test
    void unexpectedControllerFailureUsesGoJsonEnvelope() {
        doThrow(new IllegalStateException("simulated failure"))
                .when(authService).requestPasswordReset(anyString());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/auth/password/reset/request",
                HttpMethod.POST,
                new HttpEntity<>("{\"email\":\"user@example.com\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getBody()).isEqualTo("{\"error\":\"internal server error\"}\n");
    }
}
