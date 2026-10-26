package com.motion.backend.time;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * Reads and writes the timestamp representations used by this application.
 *
 * <p>The database contains SQLite {@code CURRENT_TIMESTAMP} strings (UTC, second precision,
 * optional fraction) and Java-written fixed UTC millisecond strings. This class never rewrites
 * stored values; it only converts them when comparisons or rendering require it.</p>
 */
public final class LegacyTimestamp {

    private static final DateTimeFormatter PLAIN = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter DATABASE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter SQL_CURRENT_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private LegacyTimestamp() {
    }

    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("timestamp is blank");
        }
        String trimmed = value.trim();

        try {
            return LocalDateTime.parse(trimmed, PLAIN).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            // Fall through to ISO-8601, which is used by a small number of rows.
        }
        return Instant.parse(trimmed);
    }

    public static String format(Instant instant) {
        return DATABASE_FORMAT.format(instant);
    }

    /**
     * Formats a value exactly like SQLite's {@code CURRENT_TIMESTAMP}: UTC, second precision.
     * The Go stores use this form for {@code revoked_at}, {@code used_at}, and session touches.
     */
    public static String formatSqlCurrentTimestamp(Instant instant) {
        return SQL_CURRENT_TIMESTAMP_FORMAT.format(instant);
    }

    /** Returns true when {@code now} is strictly after the parsed expiry, matching the time.After. */
    public static boolean isExpired(String expiresAt, Instant now) {
        try {
            return now.isAfter(parse(expiresAt));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /** Returns true when the idle period is strictly greater than the configured TTL. */
    public static boolean isIdleExpired(String lastActivityAt, String createdAt, Instant now, Duration idleTtl) {
        try {
            Instant reference = lastActivityAt == null || lastActivityAt.isBlank()
                    ? parse(createdAt)
                    : parse(lastActivityAt);
            return Duration.between(reference, now).compareTo(idleTtl) > 0;
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}
