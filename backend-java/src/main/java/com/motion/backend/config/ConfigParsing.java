package com.motion.backend.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses environment-style configuration values with application fallbacks.
 *
 * <p>the config loader silently falls back to defaults when a numeric, boolean, or duration
 * value cannot be parsed. The Java port intentionally preserves that behaviour rather than
 * failing startup on a malformed optional environment variable.</p>
 */
final class ConfigParsing {

    private static final Pattern DURATION_PART = Pattern.compile(
            "([+-]?\\d+(?:\\.\\d+)?)(ns|us|µs|μs|ms|s|m|h)");

    private ConfigParsing() {
    }

    static int intValue(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static long longValue(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static boolean boolValue(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes" -> true;
            case "0", "false", "no" -> false;
            default -> fallback;
        };
    }

    static Duration durationValue(String raw, Duration fallback) {
        Duration parsed = parseGoDuration(raw);
        return parsed != null && !parsed.isZero() && !parsed.isNegative() ? parsed : fallback;
    }

    static List<String> listValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static Duration parseGoDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        Matcher matcher = DURATION_PART.matcher(text);
        BigDecimal nanos = BigDecimal.ZERO;
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                return null;
            }
            BigDecimal amount = new BigDecimal(matcher.group(1));
            BigDecimal multiplier = switch (matcher.group(2)) {
                case "h" -> BigDecimal.valueOf(3_600_000_000_000L);
                case "m" -> BigDecimal.valueOf(60_000_000_000L);
                case "s" -> BigDecimal.valueOf(1_000_000_000L);
                case "ms" -> BigDecimal.valueOf(1_000_000L);
                case "us", "µs", "μs" -> BigDecimal.valueOf(1_000L);
                case "ns" -> BigDecimal.ONE;
                default -> throw new IllegalStateException("Unhandled duration unit: " + matcher.group(2));
            };
            nanos = nanos.add(amount.multiply(multiplier));
            consumed = matcher.end();
        }
        if (consumed == 0 || consumed != text.length()) {
            return null;
        }
        try {
            return Duration.ofNanos(nanos.longValueExact());
        } catch (ArithmeticException ignored) {
            return null;
        }
    }
}
