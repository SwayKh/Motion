package com.motion.backend.media;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Path-free Go {@code videoResponse}. Component order is the JSON field order emitted by Go's
 * struct encoder; empty {@code error}/{@code outputType} values use the omitempty behavior.
 * Decimal values use normalized {@link BigDecimal}s because the encoder emits {@code 0}, not
 * Jackson's default {@code 0.0}, for zero-valued floats.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VideoResponse(
        String id,
        String name,
        String status,
        BigDecimal progress,
        BigDecimal duration,
        int width,
        int height,
        long fileSize,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String error,
        String createdAt,
        boolean hasOriginal,
        boolean hasOutput,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) String outputType) {
}
