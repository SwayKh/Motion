package com.motion.backend.timeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** application timeline track. Omitted {@code visible} becomes {@code true}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "type", "name", "order", "visible", "muted", "clips"})
public record TimelineTrack(
        String id,
        String type,
        String name,
        Integer order,
        Boolean visible,
        Boolean muted,
        List<TimelineClip> clips) {

    public TimelineTrack {
        id = id == null ? "" : id;
        type = type == null ? "" : type;
        name = TimelineJson.blankToNull(name);
        order = order == null ? 0 : order;
        visible = visible == null ? Boolean.TRUE : visible;
        muted = muted != null && muted;
        clips = clips == null ? List.of() : List.copyOf(clips);
    }

    public String label() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        if (!id.isBlank()) {
            return id;
        }
        return type;
    }
}
