package com.motion.backend.web;

import com.motion.backend.auth.AuthCrypto;
import com.motion.backend.time.LegacyTimestamp;
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

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end assertions against the exact HTTP contract of the Go auth surface. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test",
                "motion.rate-limit.login=10000",
                "motion.rate-limit.register=10000",
                "motion.rate-limit.reset=10000",
                "motion.rate-limit.verify=10000",
                "motion.cors.trusted-origins=http://localhost:3000,https://app.example.com",
                "motion.proxy.trusted-proxies=127.0.0.1,::1,0:0:0:0:0:0:0:1"
        }
)
class AuthFlowIntegrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("auth-flow.db"));
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

    private TestHttpClient client() {
        return new TestHttpClient(restTemplate);
    }

    @Test
    void configAndSecurityHeadersMatchGoWireFormat() {
        ResponseEntity<String> response = client().get("/api/config");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(
                "{\"maxDurationSeconds\":300,\"maxUploadBytes\":157286400,\"requireEmailVerification\":false}\n");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("X-XSS-Protection")).isEqualTo("0");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; media-src 'self' blob:");
    }

    @Test
    void csrfCookieLifecycleMatchesGo() {
        TestHttpClient client = client();

        ResponseEntity<String> first = client.get("/api/config");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstToken = csrfCookie(client);
        assertThat(first.getHeaders().get(HttpHeaders.SET_COOKIE)).anySatisfy(cookie -> {
            assertThat(cookie).startsWith("motion_csrf=" + firstToken + "; Path=/");
            assertThat(cookie).doesNotContain("HttpOnly");
            assertThat(cookie).doesNotContain("Max-Age");
            assertThat(cookie).endsWith("SameSite=Lax");
        });

        ResponseEntity<String> second = client.get("/api/config");
        assertThat(second.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(client.cookie("motion_csrf")).isEqualTo(firstToken);

        String rotated = issueCsrf(client);
        assertThat(rotated).isNotEqualTo(firstToken);

        ResponseEntity<String> afterExplicitIssue = client.get("/api/config");
        assertThat(afterExplicitIssue.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(client.cookie("motion_csrf")).isEqualTo(rotated);
    }

    @Test
    void corsRulesMatchGo() {
        TestHttpClient client = client();

        HttpHeaders trusted = new HttpHeaders();
        trusted.set("Origin", "http://localhost:3000");
        ResponseEntity<String> allowed = client.get("/api/config", trusted);
        assertThat(allowed.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:3000");
        assertThat(allowed.getHeaders().getFirst("Vary")).isEqualTo("Origin");
        assertThat(allowed.getHeaders().getFirst("Access-Control-Allow-Methods"))
                .isEqualTo("GET, POST, OPTIONS");
        assertThat(allowed.getHeaders().getFirst("Access-Control-Allow-Headers"))
                .isEqualTo("Content-Type, X-CSRF-Token");
        assertThat(allowed.getHeaders().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");

        HttpHeaders untrusted = new HttpHeaders();
        untrusted.set("Origin", "https://evil.example");
        ResponseEntity<String> untrustedResponse = client.get("/api/config", untrusted);
        assertThat(untrustedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(untrustedResponse.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();

        ResponseEntity<String> trustedPreflight = client.options("/api/auth/login", trusted);
        assertThat(trustedPreflight.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trustedPreflight.getBody()).isNull();
        assertThat(trustedPreflight.getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://localhost:3000");

        ResponseEntity<String> untrustedPreflight = client.options("/api/auth/login", untrusted);
        assertThat(untrustedPreflight.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(untrustedPreflight.getBody()).isNull();
        assertThat(untrustedPreflight.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();

        ResponseEntity<String> originlessPreflight = client.options("/api/auth/login", new HttpHeaders());
        assertThat(originlessPreflight.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(originlessPreflight.getBody()).isNull();
    }

    @Test
    void registerLoginMeLogoutFlowMatchesGoWireFormat() {
        TestHttpClient client = client();
        String email = uniqueEmail();

        ResponseEntity<String> register = client.post("/api/auth/register", registerBody(email));
        assertThat(register.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = register.getBody();
        assertThat(body).isNotNull();
        assertThat(body).startsWith("{\"user\":{\"email\":\"" + email + "\",\"emailVerified\":false,\"id\":\"");
        assertThat(body).endsWith("\"}}\n");
        String id = body.substring(body.indexOf("\"id\":\"") + 6, body.length() - 4);
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(body).isEqualTo("{\"user\":{\"email\":\"" + email
                + "\",\"emailVerified\":false,\"id\":\"" + id + "\"}}\n");
        assertThat(register.getHeaders().get(HttpHeaders.SET_COOKIE)).containsExactly(
                "motion_session=" + client.cookie("motion_session")
                        + "; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax");

        issueCsrf(client);

        ResponseEntity<String> me = client.get("/api/auth/me");
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).isEqualTo("{\"user\":{\"email\":\"" + email
                + "\",\"emailVerified\":false,\"id\":\"" + id + "\",\"role\":\"user\"}}\n");
        assertThat(me.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();

        ResponseEntity<String> logout = client.postWithCsrf("/api/auth/logout", null);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(logout.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(logout.getHeaders().get(HttpHeaders.SET_COOKIE)).containsExactly(
                "motion_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        assertThat(client.cookie("motion_session")).isNull();

        ResponseEntity<String> afterLogout = client.get("/api/auth/me");
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(afterLogout.getBody()).isEqualTo("{\"error\":\"unauthorized\"}\n");

        ResponseEntity<String> login = client.post("/api/auth/login", loginBody(email, "password123"));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isEqualTo("{\"user\":{\"email\":\"" + email
                + "\",\"id\":\"" + id + "\"}}\n");
        assertThat(sessionRevoked(client.cookie("motion_session"))).isFalse();
    }

    @Test
    void authFailuresMatchGoStatusesAndBodies() {
        TestHttpClient registrar = client();
        String email = uniqueEmail();
        registerUser(registrar, email);
        TestHttpClient anonymous = client();

        ResponseEntity<String> duplicate = anonymous.post("/api/auth/register", registerBody(email));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).isEqualTo("{\"error\":\"an account with this email already exists\"}\n");

        ResponseEntity<String> invalidEmail = anonymous.post("/api/auth/register",
                "{\"email\":\"not-an-email\",\"password\":\"password123\",\"passwordConfirm\":\"password123\"}");
        assertThat(invalidEmail.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalidEmail.getBody()).isEqualTo("{\"error\":\"invalid email address\"}\n");

        ResponseEntity<String> mismatch = anonymous.post("/api/auth/register",
                "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"password123\",\"passwordConfirm\":\"different123\"}");
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody()).isEqualTo("{\"error\":\"passwords do not match\"}\n");

        ResponseEntity<String> weak = anonymous.post("/api/auth/register",
                "{\"email\":\"" + uniqueEmail() + "\",\"password\":\"short\",\"passwordConfirm\":\"short\"}");
        assertThat(weak.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(weak.getBody()).isEqualTo("{\"error\":\"" + AuthCrypto.WEAK_PASSWORD_MESSAGE + "\"}\n");

        ResponseEntity<String> wrongPassword = anonymous.post("/api/auth/login", loginBody(email, "wrongpass123"));
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody()).isEqualTo("{\"error\":\"invalid email or password\"}\n");

        ResponseEntity<String> unauthenticated = anonymous.get("/api/auth/me");
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthenticated.getBody()).isEqualTo("{\"error\":\"unauthorized\"}\n");
    }

    @Test
    void csrfEnforcementSessionTouchAndNoSessionSkipMatchGo() {
        TestHttpClient client = client();
        String email = uniqueEmail();
        registerUser(client, email);
        String session = client.cookie("motion_session");

        issueCsrf(client);
        jdbc.update("UPDATE sessions SET last_activity_at = NULL WHERE token_hash = ?",
                AuthCrypto.hashToken(session));
        assertThat(lastActivity(session)).isNull();

        ResponseEntity<String> rejected = client.post("/api/auth/logout", null);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).isEqualTo("{\"error\":\"invalid csrf token\"}\n");
        assertThat(lastActivity(session)).isNotNull();

        TestHttpClient invalidSessionClient = client();
        HttpHeaders rawCookie = new HttpHeaders();
        rawCookie.set(HttpHeaders.COOKIE, "motion_session=not-a-valid-token");
        ResponseEntity<String> invalidSession = invalidSessionClient.post("/api/auth/logout", null, false, rawCookie);
        assertThat(invalidSession.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invalidSession.getBody()).isEqualTo("{\"error\":\"invalid csrf token\"}\n");

        String anonymousEmail = uniqueEmail();
        TestHttpClient secondClient = client();
        registerUser(secondClient, anonymousEmail);
        TestHttpClient anonymous = client();
        anonymous.clearCookies();
        ResponseEntity<String> withoutCsrf = anonymous.post("/api/auth/login",
                loginBody(anonymousEmail, "password123"));
        assertThat(withoutCsrf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withoutCsrf.getHeaders().get(HttpHeaders.SET_COOKIE)).anySatisfy(cookie ->
                assertThat(cookie).startsWith("motion_session="));
    }

    @Test
    void passwordChangeKeepsCurrentSessionAndRevokesOthers() {
        TestHttpClient client = client();
        String email = uniqueEmail();
        registerUser(client, email);
        issueCsrf(client);
        String currentSession = client.cookie("motion_session");
        String otherToken = AuthCrypto.generateToken();
        insertSession(userId(email), otherToken);

        ResponseEntity<String> change = client.postWithCsrf("/api/auth/password/change",
                "{\"currentPassword\":\"password123\",\"newPassword\":\"newpassword123\","
                        + "\"newPasswordConfirm\":\"newpassword123\"}");
        assertThat(change.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(change.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(sessionRevoked(currentSession)).isFalse();
        assertThat(sessionRevoked(otherToken)).isTrue();

        TestHttpClient loginClient = client();
        assertThat(loginClient.post("/api/auth/login", loginBody(email, "password123")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginClient.post("/api/auth/login", loginBody(email, "newpassword123")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void resetFlowMatchesGoSemantics() {
        TestHttpClient client = client();
        String email = uniqueEmail();
        String userId = registerUser(client, email);
        issueCsrf(client);
        String session = client.cookie("motion_session");

        ResponseEntity<String> request = client.postWithCsrf("/api/auth/password/reset/request",
                "{\"email\":\"" + email + "\"}");
        assertThat(request.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(resetTokenCount(userId)).isEqualTo(1);

        ResponseEntity<String> unknown = client.postWithCsrf("/api/auth/password/reset/request",
                "{\"email\":\"" + uniqueEmail() + "\"}");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unknown.getBody()).isEqualTo("{\"status\":\"ok\"}\n");

        ResponseEntity<String> mismatch = client.postWithCsrf("/api/auth/password/reset",
                "{\"token\":\"anything\",\"newPassword\":\"resetpass123\",\"newPasswordConfirm\":\"different123\"}");
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mismatch.getBody()).isEqualTo("{\"error\":\"passwords do not match\"}\n");

        ResponseEntity<String> emptyToken = client.postWithCsrf("/api/auth/password/reset",
                "{\"token\":\"\",\"newPassword\":\"resetpass123\",\"newPasswordConfirm\":\"resetpass123\"}");
        assertThat(emptyToken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(emptyToken.getBody()).isEqualTo("{\"error\":\"invalid token\"}\n");

        ResponseEntity<String> badToken = client.postWithCsrf("/api/auth/password/reset",
                "{\"token\":\"wrong-token\",\"newPassword\":\"resetpass123\",\"newPasswordConfirm\":\"resetpass123\"}");
        assertThat(badToken.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badToken.getBody()).isEqualTo("{\"error\":\"invalid or expired token\"}\n");

        String rawToken = "reset-token-" + UUID.randomUUID();
        insertResetToken(userId, rawToken, Duration.ofMinutes(15));
        ResponseEntity<String> reset = client.postWithCsrf("/api/auth/password/reset",
                "{\"token\":\"" + rawToken + "\",\"newPassword\":\"resetpass123\","
                        + "\"newPasswordConfirm\":\"resetpass123\"}");
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(sessionRevoked(session)).isTrue();

        String usedAt = jdbc.queryForObject("SELECT used_at FROM password_reset_tokens WHERE token_hash = ?",
                String.class, AuthCrypto.hashToken(rawToken));
        assertThat(usedAt).isNotNull();

        TestHttpClient loginClient = client();
        assertThat(loginClient.post("/api/auth/login", loginBody(email, "password123")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginClient.post("/api/auth/login", loginBody(email, "resetpass123")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void verificationFlowMatchesGoSemantics() {
        TestHttpClient client = client();
        String email = uniqueEmail();
        String userId = registerUser(client, email);
        issueCsrf(client);

        String rawToken = "verify-token-" + UUID.randomUUID();
        insertVerificationToken(userId, rawToken, Duration.ofHours(1));
        ResponseEntity<String> verify = client.postWithCsrf("/api/auth/verify",
                "{\"token\":\"" + rawToken + "\"}");
        assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verify.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(client.get("/api/auth/me").getBody()).contains("\"emailVerified\":true");

        ResponseEntity<String> alreadyVerified = client.postWithCsrf("/api/auth/verify/resend", null);
        assertThat(alreadyVerified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(alreadyVerified.getBody()).isEqualTo("{\"status\":\"already_verified\"}\n");

        TestHttpClient other = client();
        String otherEmail = uniqueEmail();
        String otherUserId = registerUser(other, otherEmail);
        issueCsrf(other);
        ResponseEntity<String> resend = other.postWithCsrf("/api/auth/verify/resend", null);
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resend.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        assertThat(verificationTokenCount(otherUserId)).isEqualTo(1);
    }

    @Test
    void oversizedJsonBodyIsRejectedWithGoError() {
        TestHttpClient client = client();
        String oversized = "{\"email\":\"" + "x".repeat(1_048_576)
                + "@example.com\",\"password\":\"password123\"}";

        ResponseEntity<String> response = client.post("/api/auth/login", oversized);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("{\"error\":\"invalid request body\"}\n");
    }

    private String registerUser(TestHttpClient client, String email) {
        ResponseEntity<String> response = client.post("/api/auth/register", registerBody(email));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return userId(email);
    }

    private String issueCsrf(TestHttpClient client) {
        ResponseEntity<String> response = client.get("/api/auth/csrf");
        assertThat(response.getBody()).isEqualTo("{\"status\":\"ok\"}\n");
        String token = csrfCookie(client);
        assertThat(token).isNotNull().hasSize(43);
        return token;
    }

    private String csrfCookie(TestHttpClient client) {
        String token = client.cookie("motion_csrf");
        assertThat(token).isNotNull();
        return token;
    }

    private String userId(String email) {
        String id = jdbc.queryForObject("SELECT id FROM users WHERE normalized_email = ?",
                String.class, AuthCrypto.normalizeEmail(email));
        assertThat(id).isNotNull();
        return id;
    }

    private boolean sessionRevoked(String rawToken) {
        List<String> values = jdbc.query("SELECT revoked_at FROM sessions WHERE token_hash = ?",
                (resultSet, row) -> resultSet.getString(1), AuthCrypto.hashToken(rawToken));
        return !values.isEmpty() && values.get(0) != null;
    }

    private String lastActivity(String rawToken) {
        List<String> values = jdbc.query("SELECT last_activity_at FROM sessions WHERE token_hash = ?",
                (resultSet, row) -> resultSet.getString(1), AuthCrypto.hashToken(rawToken));
        return values.isEmpty() ? null : values.get(0);
    }

    private void insertSession(String userId, String rawToken) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO sessions (id, token_hash, user_id, expires_at, last_activity_at, revoked_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), AuthCrypto.hashToken(rawToken), userId,
                LegacyTimestamp.format(now.plus(Duration.ofHours(1))), null, null, LegacyTimestamp.format(now));
    }

    private void insertResetToken(String userId, String rawToken, Duration ttl) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO password_reset_tokens (id, token_hash, user_id, expires_at, used_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), AuthCrypto.hashToken(rawToken), userId,
                LegacyTimestamp.format(now.plus(ttl)), null, LegacyTimestamp.format(now));
    }

    private void insertVerificationToken(String userId, String rawToken, Duration ttl) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO email_verification_tokens (id, token_hash, user_id, expires_at, used_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), AuthCrypto.hashToken(rawToken), userId,
                LegacyTimestamp.format(now.plus(ttl)), null, LegacyTimestamp.format(now));
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

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
