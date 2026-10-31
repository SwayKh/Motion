package com.motion.backend.preview;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** Wire shape of the waveform metadata and cached-waveform responses. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"buckets", "duration", "audio", "peaks"})
public record WaveformResponse(
        int buckets,
        double duration,
        boolean audio,
        double[] peaks) {

    public WaveformResponse {
        peaks = peaks == null ? new double[0] : peaks;
    }
}
