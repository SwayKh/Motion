package com.motion.backend.web;

import com.motion.backend.auth.AuthCrypto;
import com.motion.backend.time.LegacyTimestamp;
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

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies all rate limiters through the real HTTP filter/controller chain. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=2",
                "motion.rate-limit.register=2",
                "motion.rate-limit.reset=2",
                "motion.rate-limit.verify=2",
                "motion.rate-limit.upload=2",
                "motion.proxy.trusted-proxies=127.0.0.1,::1,0:0:0:0:0:0:0:1"
        }
)
class RateLimitIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("rate-limit.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.toString());
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("outputs").toString());
        registry.add("motion.storage.temp-dir", () -> tempDir.resolve("tmp").toString());
        registry.add("motion.storage.preview-dir", () -> tempDir.resolve("previews").toString());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allFourLimitersUseGoWindowsAndResponses() {
        loginLimiterReturns429AfterTwoAttempts();
        registerLimiterReturns429AfterTwoAccounts();
        resetLimiterSilentlyStopsCreatingTokens();
        verificationResendLimiterReturns429ButAlreadyVerifiedBypassesIt();
        publicVerifyEndpointSharesTheVerifyLimiter();
        uploadLimiterReturns429AfterTwoAttempts();
    }

    private void uploadLimiterReturns429AfterTwoAttempts() {
        String ip = "203.0.113.50";
        TestHttpClient client = clientAt(ip);
        assertThat(client.post("/api/auth/register", registerBody(uniqueEmail())).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        issueCsrf(client);

        // Two rejected uploads consume the budget; the third is throttled before validation.
        for (int attempt = 1; attempt <= 2; attempt++) {
            ResponseEntity<String> response = client.postWithCsrf(
                    "/api/upload", "not-a-multipart-body");
            assertThat(response.getStatusCode()).as("upload attempt " + attempt)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
        ResponseEntity<String> denied = client.postWithCsrf(
                "/api/upload", "not-a-multipart-body");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getBody()).isEqualTo("{\"error\":\"too many uploads; try again later\"}\n");
    }

    private void loginLimiterReturns429AfterTwoAttempts() {
        String ip = "203.0.113.10";
        for (int attempt = 1; attempt <= 2; attempt++) {
            ResponseEntity<String> response = clientAt(ip).post("/api/auth/login",
                    "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"wrongpass123\"}");
            assertThat(response.getStatusCode()).as("login attempt " + attempt).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<String> denied = clientAt(ip).post("/api/auth/login",
                "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"wrongpass123\"}");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getBody()).isEqualTo("{\"error\":\"too many requests\"}\n");
    }

    private void registerLimiterReturns429AfterTwoAccounts() {
        String ip = "203.0.113.11";
        for (int attempt = 1; attempt <= 2; attempt++) {
            ResponseEntity<String> response = clientAt(ip).post("/api/auth/register", registerBody(uniqueEmail()));
            assertThat(response.getStatusCode()).as("register attempt " + attempt).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<String> denied = clientAt(ip).post("/api/auth/register", registerBody(uniqueEmail()));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getBody()).isEqualTo("{\"error\":\"too many requests\"}\n");
    }

    private void resetLimiterSilentlyStopsCreatingTokens() {
        String ip = "203.0.113.20";
        String email = uniqueEmail();
        ResponseEntity<String> register = clientAt(ip).post("/api/auth/register", registerBody(email));
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String userId = userId(email);

        for (int attempt = 1; attempt <= 2; attempt++) {
            ResponseEntity<String> response = clientAt(ip).post("/api/auth/password/reset/request",
                    "{\"email\":\"" + email + "\"}");
            assertThat(response.getStatusCode()).as("reset attempt " + attempt).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        }
        assertThat(resetTokenCount(userId)).isEqualTo(2);

        ResponseEntity<String> denied = clientAt(ip).post("/api/auth/password/reset/request",
                "{\"email\":\"" + email + "\"}");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(denied.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(resetTokenCount(userId)).isEqualTo(2);
    }

    private void verificationResendLimiterReturns429ButAlreadyVerifiedBypassesIt() {
        String ip = "203.0.113.30";
        String email = uniqueEmail();
        TestHttpClient client = clientAt(ip);
        ResponseEntity<String> register = client.post("/api/auth/register", registerBody(email));
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String userId = userId(email);
        issueCsrf(client);

        for (int attempt = 1; attempt <= 2; attempt++) {
            ResponseEntity<String> response = client.postWithCsrf("/api/auth/verify/resend", null);
            assertThat(response.getStatusCode()).as("resend attempt " + attempt).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        }
        assertThat(verificationTokenCount(userId)).isEqualTo(1);

        ResponseEntity<String> denied = client.postWithCsrf("/api/auth/verify/resend", null);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getBody()).isEqualTo("{\"error\":\"too many requests\"}\n");

        jdbc.update("UPDATE users SET email_verified = 1, updated_at = ? WHERE id = ?",
                LegacyTimestamp.format(Instant.now()), userId);
        ResponseEntity<String> alreadyVerified = client.postWithCsrf("/api/auth/verify/resend", null);
        assertThat(alreadyVerified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(alreadyVerified.getBody()).isEqualTo("{\"status\":\"already_verified\"}\n");
    }

    private void publicVerifyEndpointSharesTheVerifyLimiter() {
        String ip = "203.0.113.40";
        String body = "{\"token\":\"not-a-real-token\"}";
        for (int attempt = 1; attempt <= 2; attempt++) {
            ResponseEntity<String> response = clientAt(ip).post("/api/auth/verify", body);
            assertThat(response.getStatusCode()).as("verify attempt " + attempt)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo("{\"error\":\"invalid or expired verification link\"}\n");
        }

        ResponseEntity<String> denied = clientAt(ip).post("/api/auth/verify", body);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getBody()).isEqualTo("{\"error\":\"too many requests\"}\n");
    }

    private TestHttpClient clientAt(String ip) {
        TestHttpClient client = new TestHttpClient(restTemplate);
        client.useForwardedFor(ip);
        return client;
    }

    private void issueCsrf(TestHttpClient client) {
        ResponseEntity<String> response = client.get("/api/auth/csrf");
        assertThat(response.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(client.cookie("motion_csrf")).isNotNull().hasSize(43);
    }

    private String userId(String email) {
        String id = jdbc.queryForObject("SELECT id FROM users WHERE normalized_email = ?",
                String.class, AuthCrypto.normalizeEmail(email));
        assertThat(id).isNotNull();
        return id;
    }

    private int resetTokenCount(String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private int verificationTokenCount(String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = ?",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String registerBody(String email) {
        return "{\"email\":\"" + email
                + "\",\"password\":\"password123\",\"passwordConfirm\":\"password123\"}";
    }
}
