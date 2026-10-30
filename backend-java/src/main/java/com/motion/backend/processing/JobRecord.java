package com.motion.backend.processing;

/** Internal projection of a application {@code jobs} row. Paths and keys stay backend-only. */
public record JobRecord(
        String id,
        String videoId,
        String videoName,
        String sourcePath,
        String operations,
        String status,
        double progress,
        String outputPath,
        String error,
        String createdAt,
        String startedAt,
        String finishedAt,
        String cancelRequestedAt,
        String userId,
        boolean encrypted,
        String encKey) {
}
