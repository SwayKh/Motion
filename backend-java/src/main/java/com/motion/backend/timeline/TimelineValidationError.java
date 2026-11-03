package com.motion.backend.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One timeline validation problem, including optional UI highlighting IDs. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"code", "message", "trackId", "clipId"})
public record TimelineValidationError(
        String code,
        String message,
        String trackId,
        String clipId) {

    public static TimelineValidationError of(String code, String message) {
        return new TimelineValidationError(code, message, null, null);
    }
}
