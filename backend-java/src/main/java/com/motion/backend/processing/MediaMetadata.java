package com.motion.backend.processing;

/** Safe ffprobe projection used by validation, command building, and output verification. */
public record MediaMetadata(
        double duration,
        int width,
        int height,
        String videoCodec,
        String audioCodec,
        String pixelFormat,
        double frameRate,
        int sampleRate,
        String channelLayout,
        double rotation,
        String format,
        long size) {

    public boolean hasVideo() {
        return videoCodec != null && !videoCodec.isBlank();
    }

    public boolean hasAudio() {
        return audioCodec != null && !audioCodec.isBlank();
    }
}
