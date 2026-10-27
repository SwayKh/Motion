package com.motion.backend.auth;

/** Immutable projections of the Go auth model rows used by Phase 4. */
public final class AuthRecords {

    private AuthRecords() {
    }

    public record User(
            String id,
            String email,
            String normalizedEmail,
            String passwordHash,
            String role,
            String status,
            boolean emailVerified,
            String emailVerifiedAt,
            String createdAt,
            String updatedAt) {
    }

    public record Session(
            String id,
            String tokenHash,
            String userId,
            String expiresAt,
            String lastActivityAt,
            String revokedAt,
            String createdAt) {
    }

    public record ResetToken(
            String id,
            String tokenHash,
            String userId,
            String expiresAt,
            String usedAt,
            String createdAt) {
    }

    public record VerificationToken(
            String id,
            String tokenHash,
            String userId,
            String expiresAt,
            String usedAt,
            String createdAt) {
    }

    /** User and session attached to a request by OptionalAuth/Auth middleware. */
    public record Authenticated(User user, Session session) {
    }

    /** Result returned after register or login; the raw token is only ever sent as a cookie. */
    public record AuthResult(User user, String sessionToken) {
    }
}
