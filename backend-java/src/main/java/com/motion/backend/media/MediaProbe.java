package com.motion.backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Read-only ffprobe wrapper used to reproduce the upload validation. This is metadata probing
 * only: no FFmpeg processing pipeline is created in Phase 2.
 */
@Component
public class MediaProbe {

    public record Metadata(double duration, int width, int height, boolean hasVideo, boolean hasAudio) {
    }

    private final ObjectMapper objectMapper;

    public MediaProbe(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Metadata probe(Path file) {
        ProcessBuilder builder = new ProcessBuilder(
                "ffprobe", "-v", "quiet", "-print_format", "json",
                "-show_format", "-show_streams", file.toString());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new UploadInternalException("media probing is unavailable", exception);
        }

        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new UploadClientException(
                        "unable to read video metadata; the file may be unsupported or corrupt");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UploadInternalException("failed to read video metadata", exception);
        } catch (IOException exception) {
            throw new UploadInternalException("failed to read video metadata", exception);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(output);
        } catch (IOException exception) {
            throw new UploadInternalException("failed to read video metadata", exception);
        }

        JsonNode format = root.path("format");
        double duration = parseDouble(format.path("duration").asText(null));

        int width = 0;
        int height = 0;
        boolean hasAudio = false;
        for (JsonNode stream : root.path("streams")) {
            String type = stream.path("codec_type").asText();
            if ("video".equals(type) && width == 0) {
                width = stream.path("width").asInt(0);
                height = stream.path("height").asInt(0);
            } else if ("audio".equals(type)) {
                hasAudio = true;
            }
        }

        if (width == 0 && !hasAudio) {
            throw new UploadInternalException("failed to read video metadata");
        }
        return new Metadata(duration, width, height, width != 0, hasAudio);
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0D;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0D;
        }
    }
}
