package com.motion.backend.timeline;

/** Minimum asset metadata required by timeline validation and rendering. */
public record TimelineAsset(String id, double duration, boolean hasVideo, boolean hasAudio) {
}
