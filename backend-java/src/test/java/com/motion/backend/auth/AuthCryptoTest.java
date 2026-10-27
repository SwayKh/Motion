package com.motion.backend.auth;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCryptoTest {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    @Test
    void generatesGoShapedTokensAndHashes() {
        String token = AuthCrypto.generateToken();

        assertThat(TOKEN.matcher(token).matches()).isTrue();
        assertThat(AuthCrypto.hashToken(token)).matches("[0-9a-f]{64}");
        assertThat(AuthCrypto.generateToken()).isNotEqualTo(token);
    }

    @Test
    void verifiesRealGoBcryptHash() {
        String goHash = "$2a$10$bJ/SU/btIY7SG5iuviAyIOdQV.CrpKOyxhTgkOhzMLENr0HdHytgO";

        assertThat(AuthCrypto.checkPassword("password123", goHash)).isTrue();
        assertThat(AuthCrypto.checkPassword("wrong-password", goHash)).isFalse();
        assertThat(AuthCrypto.checkPassword("password123", "not-a-hash")).isFalse();
    }

    @Test
    void enforcesGoPasswordPolicy() {
        assertThat(AuthCrypto.validPassword("password123")).isTrue();
        assertThat(AuthCrypto.validPassword("short1")).isFalse();
        assertThat(AuthCrypto.validPassword("lettersonly")).isFalse();
        assertThat(AuthCrypto.validPassword("12345678")).isFalse();
    }

    @Test
    void enforcesGoEmailValidation() {
        assertThat(AuthCrypto.validEmail("User@Example.com")).isTrue();
        assertThat(AuthCrypto.validEmail("nope")).isFalse();
        assertThat(AuthCrypto.validEmail("a@@b.com")).isFalse();
        assertThat(AuthCrypto.validEmail("a@b")).isFalse();
        assertThat(AuthCrypto.normalizeEmail("  User@Example.COM ")).isEqualTo("user@example.com");
    }
}
