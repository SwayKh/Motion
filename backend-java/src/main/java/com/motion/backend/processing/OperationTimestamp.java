package com.motion.backend.processing;

import java.math.BigDecimal;
import java.util.Locale;

/** Timestamp parsing and number formatting for FFmpeg filter arguments. */
public final class OperationTimestamp {

    private OperationTimestamp() {
    }

    /** Accepts plain seconds, {@code MM:SS}, or {@code HH:MM:SS}, with fractional components. */
    public static double parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new ProcessingException("timestamp is empty");
        }
        String text = value.trim();
        if (!text.contains(":")) {
            return parseComponent(text, false);
        }
        String[] parts = text.split(":", -1);
        if (parts.length < 2 || parts.length > 3) {
            throw new ProcessingException("invalid timestamp");
        }
        double total = 0;
        for (int i = 0; i < parts.length; i++) {
            double component = parseComponent(parts[i], i > 0);
            total = total * 60 + component;
        }
        return total;
    }

    public static double parseOptional(String value) {
        return value == null || value.trim().isEmpty() ? 0D : parse(value);
    }

    /** Mirrors {@code strconv.FormatFloat(value, 'f', -1, 64)}. */
    public static String formatSeconds(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Mirrors the {@code fmt.Sprintf("%.2f*PTS", ...)} for speed filters. */
    public static String speedPts(double factor) {
        return String.format(Locale.ROOT, "%.2f", 1.0D / factor) + "*PTS";
    }

    private static double parseComponent(String raw, boolean bounded) {
        String part = raw == null ? "" : raw.trim();
        if (part.isEmpty()) {
            throw new ProcessingException("invalid timestamp");
        }
        double value;
        try {
            value = Double.parseDouble(part);
        } catch (NumberFormatException exception) {
            throw new ProcessingException("invalid timestamp");
        }
        if (!Double.isFinite(value) || value < 0 || (bounded && value >= 60)) {
            throw new ProcessingException("invalid timestamp");
        }
        return value;
    }
}
