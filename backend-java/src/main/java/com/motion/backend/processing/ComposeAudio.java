package com.motion.backend.processing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One audio segment in an internal composition plan. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonPropertyOrder({"videoId", "sourceIn", "sourceOut", "timelineStart", "volume"})
public record ComposeAudio(
        String videoId,
        String sourceIn,
        String sourceOut,
        String timelineStart,
        Double volume) {

    public double volumeValue() {
        return volume == null ? 0D : volume;
    }
}
