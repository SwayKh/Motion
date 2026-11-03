package com.motion.backend.timeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * application timeline clip. Omitted volume/opacity/scale become {@code 1}; an explicit
 * {@code 0} is preserved.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "id", "kind", "assetId", "sourceIn", "sourceOut", "timelineStart", "volume", "opacity",
        "x", "y", "scale", "text"
})
public record TimelineClip(
        String id,
        String kind,
        String assetId,
        Double sourceIn,
        Double sourceOut,
        Double timelineStart,
        Double volume,
        Double opacity,
        Double x,
        Double y,
        Double scale,
        String text) {

    public TimelineClip {
        id = id == null ? "" : id;
        kind = kind == null ? "" : kind;
        assetId = TimelineJson.blankToNull(assetId);
        sourceIn = orZero(sourceIn);
        sourceOut = orZero(sourceOut);
        timelineStart = orZero(timelineStart);
        volume = volume == null ? 1D : volume;
        opacity = opacity == null ? 1D : opacity;
        x = orZero(x);
        y = orZero(y);
        scale = scale == null ? 1D : scale;
        text = TimelineJson.blankToNull(text);
    }

    private static Double orZero(Double value) {
        return value == null ? 0D : value;
    }

    /** the {@code clip.Duration()}: a negative range is clamped to zero. */
    public double duration() {
        return Math.max(0D, sourceOut - sourceIn);
    }

    public double timelineEnd() {
        return timelineStart + duration();
    }
}
