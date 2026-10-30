package com.motion.backend.processing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.time.LegacyTimestamp;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exact Go {@code jobResponse} wire shape, including field order and omitempty behavior. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "id", "videoId", "videoName", "status", "progress", "error", "operations",
        "createdAt", "startedAt", "finishedAt", "cancelRequestedAt", "hasSource",
        "hasOutput", "outputType"
})
public record JobResponse(
        String id,
        String videoId,
        String videoName,
        String status,
        BigDecimal progress,
        String error,
        List<Operation> operations,
        String createdAt,
        String startedAt,
        String finishedAt,
        String cancelRequestedAt,
        boolean hasSource,
        boolean hasOutput,
        String outputType) {

    public static JobResponse from(JobRecord job, ObjectMapper mapper) {
        double progress = Math.max(0, Math.min(100, job.progress()));
        String outputType = "";
        if (job.outputPath() != null && !job.outputPath().isBlank()) {
            String lower = job.outputPath().toLowerCase(Locale.ROOT);
            outputType = lower.endsWith(".mp3") ? "audio" : "video";
        }
        boolean hasSource = job.sourcePath() != null && !job.sourcePath().isBlank()
                && Files.isRegularFile(Path.of(job.sourcePath()));
        return new JobResponse(
                job.id(), job.videoId(), job.videoName(), job.status(), decimal(progress),
                blankToNull(job.error()), parseOperations(job.operations(), mapper),
                formatTime(job.createdAt()), formatTime(job.startedAt()),
                formatTime(job.finishedAt()), formatTime(job.cancelRequestedAt()),
                hasSource,
                "completed".equals(job.status()) && job.outputPath() != null && !job.outputPath().isBlank(),
                blankToNull(outputType));
    }

    private static List<Operation> parseOperations(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            ProcessingRequest request = mapper.readValue(json, ProcessingRequest.class);
            return request.operations() == null ? new ArrayList<>() : request.operations();
        } catch (IOException exception) {
            return new ArrayList<>();
        }
    }

    private static String formatTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LegacyTimestamp.parse(raw).toString();
        } catch (RuntimeException exception) {
            return raw;
        }
    }

    private static BigDecimal decimal(double value) {
        BigDecimal result = BigDecimal.valueOf(value).stripTrailingZeros();
        return result.scale() < 0 ? result.setScale(0) : result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
