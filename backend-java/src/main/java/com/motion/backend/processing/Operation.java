package com.motion.backend.processing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/**
 * application processing operation. The {@code orientation} field is numeric: 0 is vertical and
 * 1 is horizontal. Missing optional fields are represented as {@code null} so response mapping can
 * reproduce the {@code omitempty} behavior.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonPropertyOrder({
        "type", "start", "end", "factor", "orientation", "audioSourceId", "audioVolume",
        "offset", "audioStart", "audioEnd", "clips", "compose"
})
public record Operation(
        String type,
        String start,
        String end,
        Double factor,
        Integer orientation,
        String audioSourceId,
        Double audioVolume,
        String offset,
        String audioStart,
        String audioEnd,
        List<Clip> clips,
        ComposePlan compose) {

    public Operation {
        clips = clips == null ? null : List.copyOf(clips);
    }

    public double factorValue() {
        return factor == null ? 0D : factor;
    }

    public int orientationValue() {
        return orientation == null ? 0 : orientation;
    }

    public double audioVolumeValue() {
        return audioVolume == null ? 0D : audioVolume;
    }
}
