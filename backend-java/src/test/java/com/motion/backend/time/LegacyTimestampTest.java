package com.motion.backend.time;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyTimestampTest {

    @Test
    void parsesSqliteCurrentTimestampAsUtc() {
        assertThat(LegacyTimestamp.parse("2026-06-02 14:16:59"))
                .isEqualTo(Instant.parse("2026-06-02T14:16:59Z"));
    }


    @Test
    void parsesJavaWrittenTimestamp() {
        assertThat(LegacyTimestamp.parse("2026-11-17 17:42:10.123"))
                .isEqualTo(Instant.parse("2026-11-17T17:42:10.123Z"));
    }

    @Test
    void writesFixedUtcFormat() {
        assertThat(LegacyTimestamp.format(Instant.parse("2026-11-17T17:42:10.123Z")))
                .isEqualTo("2026-11-17 17:42:10.123");
    }

    @Test
    void rejectsBlankValues() {
        assertThatThrownBy(() -> LegacyTimestamp.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsSqliteCurrentTimestampWithSecondPrecision() {
        assertThat(LegacyTimestamp.formatSqlCurrentTimestamp(
                Instant.parse("2026-11-17T17:42:10.987Z")))
                .isEqualTo("2026-11-17 17:42:10");
    }

    @Test
    void expiryUsesStrictAfter() {
        Instant now = Instant.parse("2026-11-17T17:42:10Z");
        assertThat(LegacyTimestamp.isExpired("2026-11-17 17:42:10", now)).isFalse();
        assertThat(LegacyTimestamp.isExpired("2026-11-17 17:42:09", now)).isTrue();
    }

    @Test
    void idleCheckUsesLastActivityThenCreatedAt() {
        Instant now = Instant.parse("2026-11-17T17:42:10Z");
        Duration ttl = Duration.ofHours(2);
        assertThat(LegacyTimestamp.isIdleExpired("2026-11-17 15:42:10", null, now, ttl)).isFalse();
        assertThat(LegacyTimestamp.isIdleExpired("2026-11-17 15:42:09", null, now, ttl)).isTrue();
        assertThat(LegacyTimestamp.isIdleExpired(null, "2026-11-17 15:42:10", now, ttl)).isFalse();
    }
}
