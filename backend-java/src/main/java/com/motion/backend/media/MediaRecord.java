package com.motion.backend.media;

/** Owned projection of a Go {@code videos} row. Paths stay internal to the backend. */
public record MediaRecord(
        String id,
        String originalPath,
        String originalName,
        long fileSize,
        String outputPath,
        String status,
        double progress,
        String operations,
        double duration,
        int width,
        int height,
        String error,
        String createdAt,
        String userId,
        boolean encrypted,
        String encKey,
        boolean outputEncrypted,
        String outputEncKey) {
}
