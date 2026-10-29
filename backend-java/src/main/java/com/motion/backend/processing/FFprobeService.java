package com.motion.backend.processing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Safe ffprobe wrapper that exposes the metadata the processing engine needs. */
@Component
public class FFprobeService {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;

    public FFprobeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MediaMetadata probe(Path file) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "ffprobe", "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", file.toString());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            Process started = process;
            // Drain stdout while the probe runs. readAllBytes() before waitFor(timeout) can block
            // forever on a malformed input, defeating the process timeout and leaking a child.
            var stdout = new StringBuilder();
            Thread drain = new Thread(() -> readStdout(started.getInputStream(), stdout),
                    "ffprobe-drain");
            drain.setDaemon(true);
            drain.start();
            boolean finished;
            try {
                finished = started.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                destroy(started);
                Thread.currentThread().interrupt();
                throw new ProcessingException("ffprobe was interrupted", exception);
            }
            if (!finished) {
                destroy(started);
                throw new ProcessingException("ffprobe timed out");
            }
            drain.join(5_000L);
            if (drain.isAlive()) {
                destroy(started);
                throw new ProcessingException("ffprobe output could not be read");
            }
            if (started.exitValue() != 0) {
                throw new ProcessingException("ffprobe exited with status " + started.exitValue());
            }
            return parse(stdout.toString());
        } catch (IOException exception) {
            throw new ProcessingException("ffprobe is unavailable", exception);
        } catch (InterruptedException exception) {
            if (process != null) {
                destroy(process);
            }
            Thread.currentThread().interrupt();
            throw new ProcessingException("ffprobe was interrupted", exception);
        }
    }

    private static void readStdout(InputStream input, StringBuilder destination) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8 * 1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                destination.append(buffer, 0, read);
            }
        } catch (IOException ignored) {
            // The process exit code is authoritative.
        }
    }

    private static void destroy(Process process) {
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    private MediaMetadata parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode format = root.path("format");
            double duration = number(format.path("duration"));
            long size = (long) number(format.path("size"));
            String formatName = text(format.path("format_name"));

            String videoCodec = null;
            String audioCodec = null;
            String pixelFormat = null;
            double frameRate = 0;
            int sampleRate = 0;
            String channelLayout = null;
            double rotation = 0;
            int videoWidth = 0;
            int videoHeight = 0;

            for (JsonNode stream : root.path("streams")) {
                String type = text(stream.path("codec_type"));
                if ("video".equals(type) && videoCodec == null) {
                    videoCodec = text(stream.path("codec_name"));
                    pixelFormat = text(stream.path("pix_fmt"));
                    videoWidth = (int) number(stream.path("width"));
                    videoHeight = (int) number(stream.path("height"));
                    frameRate = rational(stream.path("r_frame_rate"));
                    if (frameRate <= 0) {
                        frameRate = rational(stream.path("avg_frame_rate"));
                    }
                    rotation = rotationOf(stream);
                } else if ("audio".equals(type) && audioCodec == null) {
                    audioCodec = text(stream.path("codec_name"));
                    sampleRate = (int) number(stream.path("sample_rate"));
                    channelLayout = text(stream.path("channel_layout"));
                    if (channelLayout == null || channelLayout.isBlank()) {
                        channelLayout = text(stream.path("channels"));
                    }
                }
            }
            return new MediaMetadata(duration, videoWidth, videoHeight, videoCodec, audioCodec,
                    pixelFormat, frameRate, sampleRate, channelLayout, rotation, formatName, size);
        } catch (IOException exception) {
            throw new ProcessingException("unable to parse ffprobe output", exception);
        }
    }

    private static double rotationOf(JsonNode stream) {
        for (JsonNode side : stream.path("side_data_list")) {
            double value = number(side.path("rotation"));
            if (value != 0) {
                return value;
            }
        }
        JsonNode tags = stream.path("tags");
        double rotate = number(tags.path("rotate"));
        return rotate == 0 ? number(tags.path("rotation")) : rotate;
    }

    private static double rational(JsonNode node) {
        String value = text(node);
        if (value == null || value.isBlank() || "0/0".equals(value)) {
            return 0;
        }
        String[] parts = value.split("/", -1);
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = parts.length == 2 ? Double.parseDouble(parts[1]) : 1;
            return denominator == 0 || !Double.isFinite(numerator / denominator)
                    ? 0 : numerator / denominator;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static double number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0;
        }
        try {
            return node.isNumber() ? node.asDouble() : Double.parseDouble(node.asText().trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.isTextual() ? node.asText() : node.asText();
        return value.isBlank() ? null : value;
    }
}
