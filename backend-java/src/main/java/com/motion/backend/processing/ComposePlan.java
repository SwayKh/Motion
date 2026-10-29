package com.motion.backend.processing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** Internal multitrack composition plan produced by the future timeline renderer. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
@JsonPropertyOrder({"width", "height", "fps", "duration", "layers", "audio", "hasAudio"})
public record ComposePlan(
        Integer width,
        Integer height,
        Integer fps,
        String duration,
        List<ComposeLayer> layers,
        List<ComposeAudio> audio,
        Boolean hasAudio) {

    public ComposePlan {
        layers = layers == null ? List.of() : List.copyOf(layers);
        audio = audio == null ? List.of() : List.copyOf(audio);
    }
}
