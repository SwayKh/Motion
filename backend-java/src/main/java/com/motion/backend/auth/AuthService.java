package com.motion.backend.auth;

import com.motion.backend.auth.AuthRecords.AuthResult;
import com.motion.backend.auth.AuthRecords.Authenticated;
import com.motion.backend.auth.AuthRecords.ResetToken;
import com.motion.backend.auth.AuthRecords.Session;
import com.motion.backend.auth.AuthRecords.User;
import com.motion.backend.auth.AuthRecords.VerificationToken;
import com.motion.backend.config.MotionProperties;
import com.motion.backend.time.LegacyTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Application logic for the application auth endpoints. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthStore store;
    private final EmailSender emailSender;
    private final VerificationEmailService verificationEmailService;
    private final MotionProperties properties;

    public AuthService(AuthStore store, EmailSender emailSender,
                       VerificationEmailService verificationEmailService, MotionProperties properties) {
        this.store = store;
        this.emailSender = emailSender;
        this.verificationEmailService = verificationEmailService;
        this.properties = properties;
    }

    public AuthResult register(String email, String password, String passwordConfirm) {
        if (!Objects.equals(password, passwordConfirm)) {
            throw new ApiException(400, "passwords do not match");
        }
        String trimmedEmail = email == null ? "" : email.trim();
        if (!AuthCrypto.validEmail(trimmedEmail)) {
            throw new ApiException(400, "invalid email address");
        }
        String normalized = AuthCrypto.normalizeEmail(trimmedEmail);
        if (store.findUserByNormalizedEmail(normalized).isPresent()) {
            throw new ApiException(409, "an account with this email already exists");
        }

        String passwordHash = AuthCrypto.hashPassword(password);
        Instant now = Instant.now();
        User user = new User(
                UUID.randomUUID().toString(),
                trimmedEmail,
                normalized,
                passwordHash,
                "user",
                "active",
                false,
                null,
                LegacyTimestamp.format(now),
                LegacyTimestamp.format(now));
        store.createUser(user);

        String token = createSessionFor(user);
        if (properties.requireEmailVerification()) {
            try {
                verificationEmailService.issue(user);
            } catch (Exception exception) {
                log.warn("sending verification email for user={}: {}", user.id(), exception.getMessage());
            }
        }
        return new AuthResult(user, token);
    }

    public AuthResult login(String email, String password) {
        String normalized = AuthCrypto.normalizeEmail(email);
        User user = store.findUserByNormalizedEmail(normalized).orElse(null);
        if (user == null || !"active".equals(user.status()) || !AuthCrypto.checkPassword(password, user.passwordHash())) {
            throw new ApiException(401, "invalid email or password");
        }

        String token = AuthCrypto.generateToken();
        Instant now = Instant.now();
        Session session = new Session(
                UUID.randomUUID().toString(),
                AuthCrypto.hashToken(token),
                user.id(),
                LegacyTimestamp.format(now.plus(properties.sessionTtl())),
                null,
                null,
                LegacyTimestamp.format(now));
        store.rotateSessionForLogin(user.id(), session);
        return new AuthResult(user, token);
    }

    public void logout(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            return;
        }
        Session session = findValidSession(AuthCrypto.hashToken(rawSessionToken)).orElse(null);
        if (session != null) {
            // Revoke failures on logout are ignored; keep the response identical but make the
            // failure visible in logs instead of silently swallowing it.
            try {
                store.revokeSession(session.id());
            } catch (ApiException exception) {
                log.warn("session revoke failed during logout: {}", exception.getMessage());
            }
        }
    }

    /**
     * Validates a session and its user exactly like OptionalAuthMiddleware/AuthMiddleware.
     * The session touch remains best effort and never extends {@code expires_at}.
     */
    public Optional<Authenticated> authenticate(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            return Optional.empty();
        }
        Session session = findValidSession(AuthCrypto.hashToken(rawSessionToken)).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        User user = store.findUserById(session.userId()).orElse(null);
        if (user == null || !"active".equals(user.status())) {
            return Optional.empty();
        }
        store.touchSession(session.id(), Instant.now());
        return Optional.of(new Authenticated(user, session));
    }

    public void changePassword(Authenticated authenticated, String currentPassword,
                               String newPassword, String newPasswordConfirm) {
        if (!Objects.equals(newPassword, newPasswordConfirm)) {
            throw new ApiException(400, "passwords do not match");
        }
        User user = authenticated.user();
        if (!AuthCrypto.checkPassword(currentPassword, user.passwordHash())) {
            throw new ApiException(401, "invalid current password");
        }
        String hash = AuthCrypto.hashPassword(newPassword);
        String currentSessionId = authenticated.session() == null ? null : authenticated.session().id();
        store.updatePasswordAndRevoke(user.id(), hash, Instant.now(), currentSessionId);
    }

    public void requestPasswordReset(String email) {
        String normalized = AuthCrypto.normalizeEmail(email);
        Optional<User> maybeUser = store.findUserByNormalizedEmail(normalized);
        if (maybeUser.isEmpty()) {
            return;
        }
        User user = maybeUser.get();
        String token = AuthCrypto.generateToken();
        Instant now = Instant.now();
        // The reset-token TTL is fixed at 15 minutes; this is intentionally not configurable.
        ResetToken reset = new ResetToken(
                UUID.randomUUID().toString(),
                AuthCrypto.hashToken(token),
                user.id(),
                LegacyTimestamp.format(now.plusSeconds(15 * 60)),
                null,
                LegacyTimestamp.format(now));
        try {
            store.createResetToken(reset);
        } catch (Exception exception) {
            // Never email a token that was not persisted; the response stays generic like Go's.
            log.warn("failed to persist reset token for user={}: {}", user.id(), exception.getMessage());
            return;
        }
        try {
            emailSender.send(user.email(), "Password reset",
                    "Use this token to reset your password: " + token
                            + "\nOr visit: " + properties.baseUrl() + "/reset?token=" + token);
        } catch (Exception exception) {
            log.warn("password reset email delivery failed for user={}: {}", user.id(), exception.getMessage());
        }
    }

    public void resetPassword(String token, String newPassword, String newPasswordConfirm) {
        if (!Objects.equals(newPassword, newPasswordConfirm)) {
            throw new ApiException(400, "passwords do not match");
        }
        if (token == null || token.isBlank()) {
            throw new ApiException(400, "invalid token");
        }
        String tokenHash = AuthCrypto.hashToken(token);
        ResetToken reset = store.findResetTokenByHash(tokenHash).orElse(null);
        if (!validToken(reset)) {
            throw new ApiException(400, "invalid or expired token");
        }
        String passwordHash = AuthCrypto.hashPassword(newPassword);
        store.consumeResetAndChangePassword(reset.userId(), tokenHash, passwordHash, Instant.now());
    }

    public void verifyEmail(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(400, "a verification token is required");
        }
        String tokenHash = AuthCrypto.hashToken(token);
        VerificationToken verification = store.findVerificationTokenByHash(tokenHash).orElse(null);
        if (!validToken(verification)) {
            throw new ApiException(400, "invalid or expired verification link");
        }
        store.consumeVerificationAndMarkVerified(verification.userId(), tokenHash, Instant.now());
    }

    public void resendVerification(User user) {
        verificationEmailService.issue(user);
    }

    /** Used by later video/job/timeline controllers; deliberately not applied to /api/auth/me. */
    public void requireVerified(Authenticated authenticated) {
        if (authenticated == null) {
            throw new ApiException(401, "unauthorized");
        }
        if (properties.requireEmailVerification() && !authenticated.user().emailVerified()) {
            throw new ApiException(403, "verify your email address to continue");
        }
    }

    private String createSessionFor(User user) {
        String token = AuthCrypto.generateToken();
        Instant now = Instant.now();
        store.createSession(new Session(
                UUID.randomUUID().toString(),
                AuthCrypto.hashToken(token),
                user.id(),
                LegacyTimestamp.format(now.plus(properties.sessionTtl())),
                null,
                null,
                LegacyTimestamp.format(now)));
        return token;
    }

    private Optional<Session> findValidSession(String tokenHash) {
        Session session = store.findSessionByTokenHash(tokenHash).orElse(null);
        if (session == null || session.revokedAt() != null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (LegacyTimestamp.isExpired(session.expiresAt(), now)) {
            return Optional.empty();
        }
        if (LegacyTimestamp.isIdleExpired(session.lastActivityAt(), session.createdAt(),
                now, properties.sessionIdleTtl())) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private static boolean validToken(ResetToken token) {
        return token != null && token.usedAt() == null
                && !LegacyTimestamp.isExpired(token.expiresAt(), Instant.now());
    }

    private static boolean validToken(VerificationToken token) {
        return token != null && token.usedAt() == null
                && !LegacyTimestamp.isExpired(token.expiresAt(), Instant.now());
    }
}
