package com.motion.backend.preview;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Wire shape of the thumbnail metadata response. */
@JsonPropertyOrder({
        "count", "tileWidth", "tileHeight", "width", "height", "duration", "spriteUrl"
})
public record ThumbnailResponse(
        int count,
        int tileWidth,
        int tileHeight,
        int width,
        int height,
        double duration,
        String spriteUrl) {
}
