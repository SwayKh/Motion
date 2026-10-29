package com.motion.backend.processing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One visual layer in an internal composition plan. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonPropertyOrder({
        "kind", "videoId", "text", "sourceIn", "sourceOut", "timelineStart", "timelineEnd",
        "x", "y", "scale", "opacity", "base"
})
public record ComposeLayer(
        @JsonInclude(JsonInclude.Include.ALWAYS) String kind,
        String videoId,
        String text,
        String sourceIn,
        String sourceOut,
        String timelineStart,
        String timelineEnd,
        Double x,
        Double y,
        Double scale,
        Double opacity,
        Boolean base) {

    public double xValue() {
        return x == null ? 0D : x;
    }

    public double yValue() {
        return y == null ? 0D : y;
    }

    public double scaleValue() {
        return scale == null ? 0D : scale;
    }

    public double opacityValue() {
        return opacity == null ? 0D : opacity;
    }

    public boolean isBase() {
        return Boolean.TRUE.equals(base);
    }
}
