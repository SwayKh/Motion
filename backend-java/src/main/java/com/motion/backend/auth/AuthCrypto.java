package com.motion.backend.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Password, token, and email helpers. */
public final class AuthCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    public static final String WEAK_PASSWORD_MESSAGE =
            "password does not meet policy requirements (minimum 8 characters, at least one letter and one digit)";

    private AuthCrypto() {
    }

    /** 32 random bytes encoded exactly like the base64.RawURLEncoding: 43 URL-safe chars. */
    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64_URL.encodeToString(bytes);
    }

    /** Lowercase hexadecimal SHA-256, matching the auth.HashToken. */
    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Validates the password policy, then bcrypt-hashes at the default cost (10). */
    public static String hashPassword(String password) {
        if (!validPassword(password)) {
            throw new ApiException(400, WEAK_PASSWORD_MESSAGE);
        }
        return BCRYPT.encode(password);
    }

    /** Returns false for malformed or non-matching bcrypt hashes. */
    public static boolean checkPassword(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) {
            return false;
        }
        try {
            return BCRYPT.matches(password, hash);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** Mirrors the deliberately simple validation: exactly one @, non-empty sides, dot in domain. */
    public static boolean validEmail(String email) {
        String normalized = normalizeEmail(email);
        if (normalized.isEmpty() || normalized.getBytes(StandardCharsets.UTF_8).length > 254) {
            return false;
        }
        int at = normalized.indexOf('@');
        if (at <= 0 || at != normalized.lastIndexOf('@') || at == normalized.length() - 1) {
            return false;
        }
        return normalized.substring(at + 1).contains(".");
    }

    /** Counts bytes for length and accepts Unicode letters/digits. */
    public static boolean validPassword(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length < 8) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int codePoint : password.codePoints().toArray()) {
            hasLetter |= Character.isLetter(codePoint);
            hasDigit |= Character.isDigit(codePoint);
        }
        return hasLetter && hasDigit;
    }
}
