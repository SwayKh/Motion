package com.motion.backend.processing;

/** Verified output placement returned by the synchronous processing engine. */
public record ProcessingResult(
        String outputPath,
        String outputType,
        boolean encrypted,
        String encKey,
        long size) {
}
