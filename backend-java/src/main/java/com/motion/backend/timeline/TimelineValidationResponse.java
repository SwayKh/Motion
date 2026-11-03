package com.motion.backend.timeline;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** the {@code timelineValidationResponse} wire shape. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"error", "validationErrors"})
public record TimelineValidationResponse(String error, List<TimelineValidationError> validationErrors) {
}
