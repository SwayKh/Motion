package com.motion.backend.timeline;

import java.util.List;

/** Validation failure whose message and structured errors match Go exactly. */
public class TimelineValidationException extends RuntimeException {

    private final int status;
    private final List<TimelineValidationError> errors;

    public TimelineValidationException(int status, List<TimelineValidationError> errors) {
        super(join(errors));
        this.status = status;
        this.errors = List.copyOf(errors);
    }

    public int status() {
        return status;
    }

    public List<TimelineValidationError> errors() {
        return errors;
    }

    private static String join(List<TimelineValidationError> errors) {
        return errors.stream().map(TimelineValidationError::message).reduce((a, b) -> a + "; " + b).orElse("");
    }
}
