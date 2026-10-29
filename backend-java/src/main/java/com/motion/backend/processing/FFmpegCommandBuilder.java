package com.motion.backend.processing;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Builds Go-equivalent FFmpeg argv lists. No shell strings are ever created. */
@Component
public class FFmpegCommandBuilder {

    public record InputRef(int index, Path path, boolean hasAudio) {
    }

    public record ClipInput(Path path, String start, String end, boolean hasAudio) {
    }

    /** Builds the single-source pipeline used by all non-concat, non-compose operations. */
    public CommandSpec buildPipeline(Path source, Path output, MediaMetadata metadata,
                                     List<Operation> operations, Map<String, Path> audioSources) {
        PipelineBuilder builder = new PipelineBuilder(source, output, metadata, audioSources);
        for (Operation operation : operations) {
            builder.apply(operation);
        }
        return builder.build();
    }

    /** Builds each clip normalization command plus the final concat-demuxer command. */
    public List<CommandSpec> buildConcat(List<ClipInput> clips, Path listFile, Path output,
                                         int width, int height, int fps) {
        Path scratch = listFile.toAbsolutePath().getParent();
        List<CommandSpec> commands = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            Path segment = scratch.resolve("segment-" + i + ".ts");
            commands.add(normalizeClip(clips.get(i), segment, width, height, fps));
        }
        commands.add(new CommandSpec(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "concat", "-safe", "0", "-i", listFile.toString(),
                "-c", "copy", "-bsf:a", "aac_adtstoasc", output.toString()), false, 0));
        return commands;
    }

    private static CommandSpec normalizeClip(ClipInput clip, Path segment,
                                             int width, int height, int fps) {
        List<String> args = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
        double duration = 0;
        boolean hasStart = clip.start() != null && !clip.start().isBlank();
        boolean hasEnd = clip.end() != null && !clip.end().isBlank();
        if (hasStart) {
            double start = OperationTimestamp.parse(clip.start());
            args.add("-ss");
            args.add(OperationTimestamp.formatSeconds(start));
            if (hasEnd) {
                duration = OperationTimestamp.parse(clip.end()) - start;
            }
        } else if (hasEnd) {
            duration = OperationTimestamp.parse(clip.end());
        }
        args.add("-i");
        args.add(clip.path().toString());
        if (duration > 0) {
            args.add("-t");
            args.add(OperationTimestamp.formatSeconds(duration));
        }
        String videoFilter = String.format(Locale.ROOT,
                "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=%d",
                width, height, width, height, fps);
        if (clip.hasAudio()) {
            args.addAll(List.of("-map", "0:v:0", "-map", "0:a:0",
                    "-vf", videoFilter, "-af", "aresample=async=1:first_pts=0",
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-ar", "48000", "-ac", "2", "-f", "mpegts",
                    segment.toString()));
        } else {
            args.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
                    "-map", "0:v:0", "-map", "1:a:0", "-vf", videoFilter,
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-ar", "48000", "-ac", "2", "-shortest",
                    "-f", "mpegts", segment.toString()));
        }
        return new CommandSpec(args, false, 0);
    }

    /** Builds the single internal compose command from an already-resolved input registry. */
    public CommandSpec buildCompose(ComposePlan plan, MediaMetadata metadata,
                                    List<InputRef> inputs, Map<String, InputRef> byVideoId,
                                    int derivedWidth, int derivedHeight, Path output) {
        int width = plan.width() != null && plan.width() > 0 ? plan.width() : derivedWidth;
        int height = plan.height() != null && plan.height() > 0 ? plan.height() : derivedHeight;
        width &= ~1;
        height &= ~1;
        int fps = plan.fps() != null && plan.fps() > 0 ? plan.fps() : 30;
        double duration = metadata.duration();
        if (plan.duration() != null && !plan.duration().isBlank()) {
            try {
                double parsed = OperationTimestamp.parse(plan.duration());
                if (parsed > 0) {
                    duration = parsed;
                }
            } catch (ProcessingException ignored) {
                // Go falls back to the source duration when the plan duration is unreadable.
            }
        }
        if (duration <= 0) {
            throw new ProcessingException("the timeline has no duration");
        }

        List<String> filters = new ArrayList<>();
        filters.add(String.format(Locale.ROOT, "color=c=black:s=%dx%d:r=%d:d=%s[bg]",
                width, height, fps, OperationTimestamp.formatSeconds(duration)));
        String current = "bg";
        int layerCount = 0;
        for (ComposeLayer layer : plan.layers()) {
            double start = OperationTimestamp.parseOptional(layer.timelineStart());
            double end = OperationTimestamp.parseOptional(layer.timelineEnd());
            if (end <= start) {
                continue;
            }
            String next = "v" + layerCount++;
            if ("text".equals(layer.kind())) {
                filters.add("[" + current + "]" + drawText(layer, start, end) + "[" + next + "]");
                current = next;
                continue;
            }
            InputRef ref = byVideoId.get(videoKey(layer.videoId()));
            if (ref == null) {
                throw new ProcessingException("a referenced clip is not available");
            }
            StringBuilder chain = new StringBuilder()
                    .append('[').append(ref.index()).append(":v]trim=start=")
                    .append(OperationTimestamp.formatSeconds(OperationTimestamp.parseOptional(layer.sourceIn())))
                    .append(":end=")
                    .append(OperationTimestamp.formatSeconds(OperationTimestamp.parseOptional(layer.sourceOut())))
                    .append(",setpts=PTS-STARTPTS");
            if (layer.isBase()) {
                chain.append(",scale=").append(width).append(':').append(height)
                        .append(":force_original_aspect_ratio=decrease,pad=")
                        .append(width).append(':').append(height)
                        .append(":(ow-iw)/2:(oh-ih)/2,setsar=1");
            } else {
                double scale = layer.scaleValue() > 0 ? layer.scaleValue() : 1;
                chain.append(",scale=iw*").append(OperationTimestamp.formatSeconds(scale))
                        .append(":ih*").append(OperationTimestamp.formatSeconds(scale));
            }
            chain.append(",format=rgba");
            if (layer.opacityValue() < 1) {
                chain.append(",colorchannelmixer=aa=")
                        .append(OperationTimestamp.formatSeconds(clamp01(layer.opacityValue())));
            }
            chain.append(",setpts=PTS-STARTPTS+")
                    .append(OperationTimestamp.formatSeconds(start)).append("/TB[")
                    .append(next).append("_ov]");
            filters.add(chain.toString());
            double x = layer.isBase() ? 0 : layer.xValue();
            double y = layer.isBase() ? 0 : layer.yValue();
            filters.add(String.format(Locale.ROOT,
                    "[%s][%s_ov]overlay=x=%s:y=%s:eof_action=pass:enable='between(t,%s,%s)'[%s]",
                    current, next, OperationTimestamp.formatSeconds(x), OperationTimestamp.formatSeconds(y),
                    OperationTimestamp.formatSeconds(start), OperationTimestamp.formatSeconds(end), next));
            current = next;
        }
        filters.add("[" + current + "]format=yuv420p[vout]");

        List<String> audioLabels = new ArrayList<>();
        int audioCount = 0;
        for (ComposeAudio segment : plan.audio()) {
            if (segment.volumeValue() <= 0) {
                continue;
            }
            InputRef ref = byVideoId.get(videoKey(segment.videoId()));
            if (ref == null || !ref.hasAudio()) {
                continue;
            }
            double start = OperationTimestamp.parseOptional(segment.timelineStart());
            String label = "a" + audioCount++;
            filters.add(String.format(Locale.ROOT,
                    "[%d:a]atrim=start=%s:end=%s,asetpts=PTS-STARTPTS,volume=%s,adelay=%d:all=1[%s]",
                    ref.index(),
                    OperationTimestamp.formatSeconds(OperationTimestamp.parseOptional(segment.sourceIn())),
                    OperationTimestamp.formatSeconds(OperationTimestamp.parseOptional(segment.sourceOut())),
                    OperationTimestamp.formatSeconds(segment.volumeValue()),
                    (long) (start * 1000 + 0.5), label));
            audioLabels.add("[" + label + "]");
        }
        boolean hasAudioOut = !audioLabels.isEmpty();
        if (hasAudioOut) {
            if (audioLabels.size() == 1) {
                filters.add(audioLabels.getFirst() + "alimiter=limit=0.95[aout]");
            } else {
                filters.add(String.join("", audioLabels) + "amix=inputs=" + audioLabels.size()
                        + ":duration=longest:dropout_transition=0:normalize=0,alimiter=limit=0.95[aout]");
            }
        }

        List<String> args = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
        for (InputRef input : inputs) {
            args.add("-i");
            args.add(input.path().toString());
        }
        args.add("-filter_complex");
        args.add(String.join(";", filters));
        args.add("-map");
        args.add("[vout]");
        if (hasAudioOut) {
            args.add("-map");
            args.add("[aout]");
        }
        args.addAll(List.of("-t", OperationTimestamp.formatSeconds(duration),
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-r", Integer.toString(fps)));
        if (hasAudioOut) {
            args.addAll(List.of("-c:a", "aac", "-ar", "48000", "-ac", "2"));
        }
        args.add(output.toString());
        return new CommandSpec(args, false, 0);
    }

    private static String videoKey(String videoId) {
        return videoId == null || videoId.isBlank() ? "__primary__" : videoId;
    }

    private static String drawText(ComposeLayer layer, double start, double end) {
        int fontSize = 36;
        if (layer.scaleValue() > 0) {
            fontSize = (int) (36 * layer.scaleValue() + 0.5);
        }
        fontSize = Math.max(12, fontSize);
        return String.format(Locale.ROOT,
                "drawtext=text='%s':x=%s:y=%s:fontsize=%d:fontcolor=white@%s:enable='between(t,%s,%s)'",
                escapeDrawText(layer.text() == null ? "" : layer.text()),
                OperationTimestamp.formatSeconds(layer.xValue()),
                OperationTimestamp.formatSeconds(layer.yValue()), fontSize,
                OperationTimestamp.formatSeconds(clamp01(layer.opacityValue())),
                OperationTimestamp.formatSeconds(start), OperationTimestamp.formatSeconds(end));
    }

    private static String escapeDrawText(String text) {
        return text.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
                .replace("%", "\\%")
                .replace(",", "\\,")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace(";", "\\;")
                .replace("\n", "\\n");
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static final class PipelineBuilder {
        private final Path source;
        private final Path output;
        private final MediaMetadata metadata;
        private final Map<String, Path> audioSources;
        private final List<String> inputs = new ArrayList<>();
        private final List<String> filters = new ArrayList<>();
        private final TreeMap<String, String> kwargs = new TreeMap<>();
        private final int primaryAudioInput;
        private StreamRef video;
        private StreamRef audio;
        private int nextLabel;
        private boolean videoModified;
        private boolean audioModified;
        private boolean disableVideo;
        private Integer audioQuality;

        private PipelineBuilder(Path source, Path output, MediaMetadata metadata,
                                Map<String, Path> audioSources) {
            this.source = source;
            this.output = output;
            this.metadata = metadata;
            this.audioSources = audioSources;
            this.inputs.add(source.toString());
            this.video = new StreamRef("0:v", false);
            this.audio = metadata.hasAudio() ? new StreamRef("0:a", false) : null;
            this.primaryAudioInput = 0;
        }

        private void apply(Operation operation) {
            String type = operation.type() == null ? "" : operation.type();
            switch (type) {
                case "trim", "slice" -> trim(operation);
                case "remove_section" -> removeSection(operation);
                case "speed" -> speed(operation);
                case "volume" -> volume(operation);
                case "flip" -> flip(operation);
                case "to_audio" -> toAudio();
                case "add_audio", "replace_audio" -> addAudio(operation, "add_audio".equals(type));
                default -> {
                    // Validation has already rejected unknown operations.
                }
            }
        }

        private void trim(Operation operation) {
            String escapedStart = escapeFilterValue(operation.start());
            String escapedEnd = escapeFilterValue(operation.end());
            String value = "start=" + escapedStart + ":end=" + escapedEnd;
            if (video != null) {
                video = chain(video, "trim", value);
                video = chain(video, "setpts", "PTS-STARTPTS");
                videoModified = true;
            }
            if (audio != null) {
                audio = chain(audio, "atrim", value);
                audio = chain(audio, "asetpts", "PTS-STARTPTS");
                audioModified = true;
            }
        }

        private void removeSection(Operation operation) {
            double start = OperationTimestamp.parse(operation.start());
            double end = OperationTimestamp.parse(operation.end());
            String expression = "not(between(t," + OperationTimestamp.formatSeconds(start) + ","
                    + OperationTimestamp.formatSeconds(end) + "))";
            if (video != null) {
                video = chain(video, "select", "expr=" + expression);
                video = chain(video, "setpts", "N/FRAME_RATE/TB");
                videoModified = true;
            }
            if (audio != null) {
                audio = chain(audio, "aselect", "expr=" + expression);
                audio = chain(audio, "asetpts", "N/SR/TB");
                audioModified = true;
            }
        }

        private void speed(Operation operation) {
            double factor = operation.factorValue();
            if (factor <= 0) {
                return;
            }
            // ffmpeg-go emits the video graph before the audio graph even though Go applies the
            // audio speed first. Preserve the compiled argv order.
            if (video != null) {
                video = chain(video, "setpts", OperationTimestamp.speedPts(factor));
                videoModified = true;
            }
            if (audio != null) {
                audio = chain(audio, "atempo", OperationTimestamp.formatSeconds(factor));
                audioModified = true;
            }
            kwargs.put("fps_mode", "passthrough");
        }

        private void volume(Operation operation) {
            if (audio != null) {
                audio = chain(audio, "volume", OperationTimestamp.formatSeconds(operation.factorValue()));
                audioModified = true;
            }
        }

        private void flip(Operation operation) {
            if (video == null) {
                return;
            }
            video = chain(video, operation.orientationValue() == 1 ? "hflip" : "vflip", null);
            videoModified = true;
        }

        private void toAudio() {
            video = null;
            disableVideo = true;
            audioQuality = 2;
            kwargs.put("c:a", "libmp3lame");
        }

        private void addAudio(Operation operation, boolean mix) {
            String sourceId = operation.audioSourceId();
            Path audioPath = sourceId == null || audioSources == null
                    ? null : audioSources.get(sourceId);
            if (audioPath == null) {
                throw new ProcessingException("audio source video is not available");
            }
            int index = inputs.size();
            inputs.add(audioPath.toString());
            StreamRef added = new StreamRef(index + ":a", false);
            double trimStart = OperationTimestamp.parseOptional(operation.audioStart());
            double trimEnd = OperationTimestamp.parseOptional(operation.audioEnd());
            if (trimStart > 0 || trimEnd > 0) {
                String value = "start=" + OperationTimestamp.formatSeconds(trimStart) + ":end="
                        + (trimEnd > 0 ? OperationTimestamp.formatSeconds(trimEnd) : "");
                added = chain(added, "atrim", value);
                added = chain(added, "asetpts", "PTS-STARTPTS");
            }
            double volume = operation.audioVolumeValue();
            if (volume > 0 && volume != 1) {
                added = chain(added, "volume", OperationTimestamp.formatSeconds(volume));
            }
            if (operation.offset() != null && !operation.offset().isBlank()) {
                double offset = OperationTimestamp.parse(operation.offset());
                if (offset > 0) {
                    String ms = Long.toString((long) (offset * 1000));
                    // the Processor.AddAudio emits `delays=<ms>:<ms>`, but FFmpeg n9 rejects the
                    // colon-separated form. `all=1` preserves the "delay every channel equally"
                    // intent (matching the compose path) with n9-compatible syntax for mono,
                    // stereo, and multichannel inputs.
                    added = chain(added, "adelay", ms + ":all=1");
                }
            }
            if (mix && audio != null) {
                audio = mergeChain(List.of(audio, added), "amix",
                        "inputs=2:duration=longest:dropout_transition=0:normalize=0");
            } else {
                audio = added;
            }
            audioModified = true;
        }

        private StreamRef chain(StreamRef input, String filter, String argument) {
            String label = "s" + nextLabel++;
            String node = filter + (argument == null ? "" : "=" + argument);
            filters.add(input.filterSelector() + escapeFilterNode(node) + "[" + label + "]");
            return new StreamRef("[" + label + "]", true);
        }

        private StreamRef mergeChain(List<StreamRef> inputsForFilter, String filter, String argument) {
            String label = "s" + nextLabel++;
            String selectors = inputsForFilter.stream()
                    .map(StreamRef::filterSelector)
                    .reduce("", String::concat);
            filters.add(selectors + escapeFilterNode(filter + "=" + argument) + "[" + label + "]");
            return new StreamRef("[" + label + "]", true);
        }

        private CommandSpec build() {
            if (video == null && audio == null) {
                throw new ProcessingException(
                        "no output streams available; the source is missing the required audio or video stream");
            }
            if (video != null && !videoModified && !kwargs.containsKey("c:v")) {
                kwargs.put("c:v", "copy");
            }
            if (audio != null && !audioModified && !kwargs.containsKey("c:a")) {
                kwargs.put("c:a", "copy");
            }
            if (disableVideo) {
                kwargs.put("vn", "");
            }
            if (audioQuality != null) {
                kwargs.put("q:a", Integer.toString(audioQuality));
            }
            kwargs.put("progress", "pipe:1");
            kwargs.put("nostats", "");

            List<String> args = new ArrayList<>();
            args.add("ffmpeg");
            for (String input : inputs) {
                args.add("-i");
                args.add(input);
            }
            if (!filters.isEmpty()) {
                args.add("-filter_complex");
                args.add(String.join(";", filters));
            }
            if (video != null) {
                args.add("-map");
                args.add(video.mapSelector());
            }
            if (audio != null) {
                args.add("-map");
                args.add(audio.mapSelector());
            }
            kwargs.forEach((key, value) -> {
                args.add("-" + key);
                if (value != null && !value.isEmpty()) {
                    args.add(value);
                }
            });
            args.add(output.toString());
            args.add("-y");
            return new CommandSpec(args, true, metadata.duration());
        }
    }

    private record StreamRef(String reference, boolean filtered) {
        String filterSelector() {
            return filtered ? reference : "[" + reference + "]";
        }

        String mapSelector() {
            return filtered ? reference : reference;
        }
    }

    /** Mirrors the explicit colon escaping for trim timecodes. */
    private static String escapeFilterValue(String value) {
        return value == null ? "" : value.replace(":", "\\:");
    }

    /** Mirrors ffmpeg-go's final filter-node escaping of backslashes, quotes, brackets, and commas. */
    private static String escapeFilterNode(String node) {
        return node.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace(",", "\\,")
                .replace(";", "\\;");
    }
}
