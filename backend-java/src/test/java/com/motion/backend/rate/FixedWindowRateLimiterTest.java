package com.motion.backend.rate;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    @Test
    void admitsFirstRequestEvenWithZeroLimit() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(0, Duration.ofMinutes(1));

        assertThat(limiter.allow("client")).isTrue();
        assertThat(limiter.allow("client")).isFalse();
    }

    @Test
    void enforcesConfiguredLimitPerKey() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, Duration.ofMinutes(1));

        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
        assertThat(limiter.allow("b")).isTrue();
    }

    @Test
    void evictsExpiredEntriesOnNextUse() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-18T00:00:00Z"));
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofMinutes(1), clock);

        assertThat(limiter.allow("old")).isTrue();
        assertThat(limiter.trackedKeys()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        assertThat(limiter.allow("new")).isTrue();

        assertThat(limiter.trackedKeys()).isEqualTo(1);
        assertThat(limiter.allow("old")).isTrue();
    }

    @Test
    void hardCapsTrackedKeysEvenWithoutExpiredWindows() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(1, Duration.ofHours(1));

        for (int i = 0; i < FixedWindowRateLimiter.MAX_TRACKED_KEYS + 10; i++) {
            assertThat(limiter.allow("client-" + i)).isTrue();
        }

        assertThat(limiter.trackedKeys()).isEqualTo(FixedWindowRateLimiter.MAX_TRACKED_KEYS);
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
