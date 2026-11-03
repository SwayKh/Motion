package com.motion.backend.timeline;

/** Small helpers that reproduce the {@code omitempty} string behavior. */
final class TimelineJson {

    private TimelineJson() {
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
