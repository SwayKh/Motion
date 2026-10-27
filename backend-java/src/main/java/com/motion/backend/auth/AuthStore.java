package com.motion.backend.auth;

import com.motion.backend.auth.AuthRecords.ResetToken;
import com.motion.backend.auth.AuthRecords.Session;
import com.motion.backend.auth.AuthRecords.User;
import com.motion.backend.auth.AuthRecords.VerificationToken;
import com.motion.backend.time.LegacyTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * JDBC implementation of the Go stores in {@code internal/db}.
 *
 * <p>Direct SQL is intentional: the Go application uses exact statements with best-effort
 * updates and two atomic transactions. JPA relationships and generated SQL would obscure those
 * semantics.</p>
 */
@Component
public class AuthStore {

    private static final Logger log = LoggerFactory.getLogger(AuthStore.class);

    private static final String USER_COLUMNS =
            "id, email, normalized_email, password_hash, role, status, email_verified, "
                    + "email_verified_at, created_at, updated_at";
    private static final String SESSION_COLUMNS =
            "id, token_hash, user_id, expires_at, last_activity_at, revoked_at, created_at";
    private static final String RESET_COLUMNS =
            "id, token_hash, user_id, expires_at, used_at, created_at";

    private final JdbcTemplate jdbc;

    public AuthStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findUserByNormalizedEmail(String normalizedEmail) {
        return jdbc.query("SELECT " + USER_COLUMNS + " FROM users WHERE normalized_email = ?",
                one(USER_MAPPER), normalizedEmail);
    }

    public Optional<User> findUserById(String id) {
        return jdbc.query("SELECT " + USER_COLUMNS + " FROM users WHERE id = ?", one(USER_MAPPER), id);
    }

    public void createUser(User user) {
        try {
            jdbc.update("INSERT INTO users (" + USER_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    user.id(), user.email(), user.normalizedEmail(), user.passwordHash(),
                    user.role(), user.status(), user.emailVerified() ? 1 : 0,
                    user.emailVerifiedAt(), user.createdAt(), user.updatedAt());
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception)) {
                throw new ApiException(409, "an account with this email already exists");
            }
            throw new ApiException(500, "failed to create account");
        } catch (DataAccessException exception) {
            throw new ApiException(500, "failed to create account");
        }
    }

    public void createSession(Session session) {
        try {
            jdbc.update("INSERT INTO sessions (" + SESSION_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                    session.id(), session.tokenHash(), session.userId(), session.expiresAt(),
                    session.lastActivityAt(), session.revokedAt(), session.createdAt());
        } catch (DataAccessException exception) {
            throw new ApiException(500, "failed to create session");
        }
    }

    public Optional<Session> findSessionByTokenHash(String tokenHash) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM sessions WHERE token_hash = ?",
                one(SESSION_MAPPER), tokenHash);
    }

    public void touchSession(String id, Instant now) {
        try {
            jdbc.update("UPDATE sessions SET last_activity_at = ? WHERE id = ? AND revoked_at IS NULL",
                    LegacyTimestamp.formatSqlCurrentTimestamp(now), id);
        } catch (DataAccessException exception) {
            log.warn("best-effort session touch failed: {}", exception.getMessage());
        }
    }

    public void revokeSession(String id) {
        revoke("UPDATE sessions SET revoked_at = ? WHERE id = ?",
                LegacyTimestamp.formatSqlCurrentTimestamp(Instant.now()), id);
    }

    public void revokeAllForUser(String userId) {
        revoke("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                LegacyTimestamp.formatSqlCurrentTimestamp(Instant.now()), userId);
    }

    public void revokeAllExcept(String userId, String exceptSessionId) {
        revoke("UPDATE sessions SET revoked_at = ? "
                        + "WHERE user_id = ? AND revoked_at IS NULL AND id != ?",
                LegacyTimestamp.formatSqlCurrentTimestamp(Instant.now()), userId, exceptSessionId);
    }

    /** Login rotates sessions in one transaction; a failed revoke must not yield a usable session. */
    @Transactional
    public void rotateSessionForLogin(String userId, Session session) {
        revokeAllForUser(userId);
        createSession(session);
    }

    /** Password updates and session revocation commit together; a failed revoke rolls back. */
    @Transactional
    public void updatePasswordAndRevoke(String userId, String passwordHash, Instant now,
                                        String currentSessionId) {
        updatePassword(userId, passwordHash, now);
        if (currentSessionId == null || currentSessionId.isBlank()) {
            revokeAllForUser(userId);
        } else {
            revokeAllExcept(userId, currentSessionId);
        }
    }

    public void updatePassword(String userId, String passwordHash, Instant now) {
        int updated = jdbc.update("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                passwordHash, LegacyTimestamp.format(now), userId);
        if (updated == 0) {
            throw new ApiException(500, "failed to update password");
        }
    }

    public void markEmailVerified(String userId, Instant now) {
        jdbc.update("UPDATE users SET email_verified = 1, email_verified_at = COALESCE(email_verified_at, ?), "
                        + "updated_at = ? WHERE id = ?",
                LegacyTimestamp.formatSqlCurrentTimestamp(now),
                LegacyTimestamp.formatSqlCurrentTimestamp(now), userId);
    }

    public void createResetToken(ResetToken token) {
        jdbc.update("INSERT INTO password_reset_tokens (id, token_hash, user_id, expires_at, used_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                token.id(), token.tokenHash(), token.userId(), token.expiresAt(),
                token.usedAt(), token.createdAt());
    }

    public Optional<ResetToken> findResetTokenByHash(String tokenHash) {
        return jdbc.query("SELECT " + RESET_COLUMNS + " FROM password_reset_tokens WHERE token_hash = ?",
                one(RESET_MAPPER), tokenHash);
    }

    /** the ConsumeTokenAndResetPassword: consume token, update password, revoke sessions atomically. */
    @Transactional
    public void consumeResetAndChangePassword(String userId, String tokenHash, String newPasswordHash, Instant now) {
        String current = LegacyTimestamp.formatSqlCurrentTimestamp(now);
        int consumed = jdbc.update("UPDATE password_reset_tokens SET used_at = ? "
                        + "WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
                current, tokenHash, current);
        if (consumed != 1) {
            throw new ApiException(400, "invalid or expired token");
        }
        int updated = jdbc.update("UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?",
                newPasswordHash, LegacyTimestamp.format(now), userId);
        if (updated == 0) {
            throw new ApiException(400, "invalid or expired token");
        }
        jdbc.update("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                current, userId);
    }

    public void deleteVerificationTokensForUser(String userId) {
        jdbc.update("DELETE FROM email_verification_tokens WHERE user_id = ?", userId);
    }

    /** Mirrors the delete-then-create sequence atomically. */
    @Transactional
    public void replaceVerificationToken(String userId, VerificationToken token) {
        deleteVerificationTokensForUser(userId);
        createVerificationToken(token);
    }

    public void createVerificationToken(VerificationToken token) {
        jdbc.update("INSERT INTO email_verification_tokens (id, token_hash, user_id, expires_at, used_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                token.id(), token.tokenHash(), token.userId(), token.expiresAt(),
                token.usedAt(), token.createdAt());
    }

    public Optional<VerificationToken> findVerificationTokenByHash(String tokenHash) {
        return jdbc.query("SELECT id, token_hash, user_id, expires_at, used_at, created_at "
                + "FROM email_verification_tokens WHERE token_hash = ?", one(VERIFICATION_MAPPER), tokenHash);
    }

    /** the ConsumeAndVerify: no expiry predicate in SQL; expiry is checked by the caller. */
    @Transactional
    public void consumeVerificationAndMarkVerified(String userId, String tokenHash, Instant now) {
        String current = LegacyTimestamp.formatSqlCurrentTimestamp(now);
        int consumed = jdbc.update("UPDATE email_verification_tokens SET used_at = ? "
                        + "WHERE token_hash = ? AND user_id = ? AND used_at IS NULL",
                current, tokenHash, userId);
        if (consumed == 0) {
            throw new ApiException(400, "invalid or expired verification link");
        }
        jdbc.update("UPDATE users SET email_verified = 1, email_verified_at = COALESCE(email_verified_at, ?), "
                        + "updated_at = ? WHERE id = ?",
                current, current, userId);
    }

    private void revoke(String sql, Object... arguments) {
        try {
            jdbc.update(sql, arguments);
        } catch (DataAccessException exception) {
            throw new ApiException(500, "failed to revoke sessions");
        }
    }

    private static <T> org.springframework.jdbc.core.ResultSetExtractor<Optional<T>> one(RowMapper<T> mapper) {
        return resultSet -> resultSet.next() ? Optional.of(mapper.mapRow(resultSet, 1)) : Optional.empty();
    }

    private static boolean isUniqueViolation(DataAccessException exception) {
        String message = exception.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("unique");
    }

    private static final RowMapper<User> USER_MAPPER = (rs, row) -> new User(
            rs.getString("id"),
            rs.getString("email"),
            rs.getString("normalized_email"),
            rs.getString("password_hash"),
            rs.getString("role"),
            rs.getString("status"),
            rs.getInt("email_verified") == 1,
            rs.getString("email_verified_at"),
            rs.getString("created_at"),
            rs.getString("updated_at"));

    private static final RowMapper<Session> SESSION_MAPPER = (rs, row) -> new Session(
            rs.getString("id"),
            rs.getString("token_hash"),
            rs.getString("user_id"),
            rs.getString("expires_at"),
            rs.getString("last_activity_at"),
            rs.getString("revoked_at"),
            rs.getString("created_at"));

    private static final RowMapper<ResetToken> RESET_MAPPER = (rs, row) -> new ResetToken(
            rs.getString("id"),
            rs.getString("token_hash"),
            rs.getString("user_id"),
            rs.getString("expires_at"),
            rs.getString("used_at"),
            rs.getString("created_at"));

    private static final RowMapper<VerificationToken> VERIFICATION_MAPPER = (rs, row) -> new VerificationToken(
            rs.getString("id"),
            rs.getString("token_hash"),
            rs.getString("user_id"),
            rs.getString("expires_at"),
            rs.getString("used_at"),
            rs.getString("created_at"));
}
