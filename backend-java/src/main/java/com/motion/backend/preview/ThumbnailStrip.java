package com.motion.backend.preview;

/** Dimensions of a single-row thumbnail sprite. */
public record ThumbnailStrip(
        int count,
        int tileWidth,
        int tileHeight,
        int width,
        int height) {
}
