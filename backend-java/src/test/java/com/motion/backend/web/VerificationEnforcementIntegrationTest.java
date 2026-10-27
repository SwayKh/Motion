package com.motion.backend.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Go {@code RequireVerified} contract through a test-only route under a protected
 * path family. The real video/job controllers arrive in later migration phases.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.verification.required=true"
        }
)
class VerificationEnforcementIntegrationTest {

    private static final String PROBE = "/api/videos/verification-probe";

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("verification.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anonymousRequestIsUnauthorized() {
        TestHttpClient client = new TestHttpClient(restTemplate);
        client.clearCookies();

        ResponseEntity<String> response = client.get(PROBE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"unauthorized\"}\n");
    }

    @Test
    void signedInButUnverifiedRequestIsForbidden() {
        Session session = register();

        ResponseEntity<String> response = session.client().get(PROBE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .isEqualTo("{\"error\":\"verify your email address to continue\"}\n");
    }

    @Test
    void verifiedRequestReachesTheProtectedHandler() {
        Session session = register();
        jdbc.update("UPDATE users SET email_verified = 1, email_verified_at = CURRENT_TIMESTAMP "
                + "WHERE normalized_email = ?", session.email());

        ResponseEntity<String> response = session.client().get(PROBE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
    }

    private Session register() {
        TestHttpClient client = new TestHttpClient(restTemplate);
        client.useForwardedFor(UUID.randomUUID().toString());
        String email = UUID.randomUUID() + "@example.com";
        ResponseEntity<String> response = client.post("/api/auth/register", """
                {"email":"%s","password":"password123","passwordConfirm":"password123"}
                """.formatted(email));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(client.cookie("motion_session")).isNotBlank();
        return new Session(email, client);
    }

    private record Session(String email, TestHttpClient client) {
    }

    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        VerificationProbeController verificationProbeController() {
            return new VerificationProbeController();
        }
    }

    @RestController
    static class VerificationProbeController {

        @GetMapping(PROBE)
        Map<String, String> probe() {
            return Map.of("status", "ok");
        }
    }
}
