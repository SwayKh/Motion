package com.motion.backend.timeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** application {@code RenderSettings}; zero values mean "derive from the primary clip". */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonPropertyOrder({"width", "height", "fps", "sampleRate", "channels"})
public record RenderSettings(
        Integer width,
        Integer height,
        Integer fps,
        Integer sampleRate,
        Integer channels) {

    public RenderSettings {
        width = width == null ? 0 : width;
        height = height == null ? 0 : height;
        fps = fps == null ? 0 : fps;
        sampleRate = sampleRate == null ? 0 : sampleRate;
        channels = channels == null ? 0 : channels;
    }

    public static RenderSettings empty() {
        return new RenderSettings(0, 0, 0, 0, 0);
    }
}
