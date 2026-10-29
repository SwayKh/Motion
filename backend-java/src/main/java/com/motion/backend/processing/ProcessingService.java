package com.motion.backend.processing;

import com.motion.backend.media.MediaRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.stream.Collectors;

/**
 * Synchronous, reusable FFmpeg engine.
 *
 * <p>The pipeline is: validate/call ownership at the facade, resolve sources into a private
 * workspace, build an argv-only command, execute it, probe and fully decode the result, atomically
 * place the encrypted or plain output, then delete every private plaintext artifact. All failures
 * leave no plaintext behind and are surfaced as safe messages.</p>
 */
@Component
public class ProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ProcessingService.class);
    private static final int CONCAT_FRAME_RATE = 30;

    private final MediaInputProvider inputs;
    private final FFmpegCommandBuilder commandBuilder;
    private final FFmpegExecutor executor;
    private final FFprobeService ffprobe;
    private final MediaOutputWriter outputWriter;

    public ProcessingService(MediaInputProvider inputs, FFmpegCommandBuilder commandBuilder,
                             FFmpegExecutor executor, FFprobeService ffprobe,
                             MediaOutputWriter outputWriter) {
        this.inputs = inputs;
        this.commandBuilder = commandBuilder;
        this.executor = executor;
        this.ffprobe = ffprobe;
        this.outputWriter = outputWriter;
    }

    /** Runs one already-validated request. The caller owns job transitions and error shaping. */
    public ProcessingResult process(JobRecord job, MediaRecord video, ProcessingRequest request,
                                    DoubleConsumer onProgress) {
        return process(job, video, request, onProgress, ProcessListener.NOOP);
    }

    /** Runs a request while exposing the FFmpeg process to an async cancellation registry. */
    public ProcessingResult process(JobRecord job, MediaRecord video, ProcessingRequest request,
                                    DoubleConsumer onProgress, ProcessListener listener) {
        ProcessListener processListener = listener == null ? ProcessListener.NOOP : listener;
        OutputShape shape = outputShape(request);
        try (PrivateWorkspace workspace = PrivateWorkspace.create(inputs.storage())) {
            MediaInputProvider.ResolvedInput primary = inputs.primary(workspace, video);
            MediaMetadata metadata = primary.metadata();
            if (shape.toAudio && !metadata.hasAudio()) {
                throw new ProcessingException("this video has no audio track to extract");
            }

            Path part = workspace.newFile("processed-", shape.extension);
            switch (shape.kind) {
                case CONCAT -> runConcat(workspace, job, video, primary, metadata, request, part,
                        processListener);
                case COMPOSE -> runCompose(workspace, job, video, primary, metadata, request, part,
                        processListener);
                case PIPELINE -> runPipeline(workspace, primary, metadata, request, part, onProgress,
                        processListener);
            }

            verifyOutput(part, shape.toAudio, processListener);
            MediaOutputWriter.StoredOutput stored = outputWriter.store(part, job.id(), shape.extension);
            return new ProcessingResult(stored.path(), shape.outputType, stored.encrypted(),
                    stored.wrappedKey(), stored.size());
        } catch (ProcessingException exception) {
            if (Thread.currentThread().isInterrupted()) {
                throw new ProcessingException("video processing was interrupted", exception);
            }
            throw exception;
        }
    }

    private OutputShape outputShape(ProcessingRequest request) {
        boolean toAudio = false;
        boolean concat = false;
        boolean compose = false;
        for (Operation operation : request.operations()) {
            String type = operation.type() == null ? "" : operation.type();
            toAudio |= "to_audio".equals(type);
            concat |= "concat".equals(type);
            compose |= "compose".equals(type);
        }
        if (concat && compose) {
            throw new ProcessingException("video processing failed");
        }
        if (concat) {
            return new OutputShape(Kind.CONCAT, ".mp4", "video", false);
        }
        if (compose) {
            return new OutputShape(Kind.COMPOSE, ".mp4", "video", false);
        }
        return new OutputShape(Kind.PIPELINE, toAudio ? ".mp3" : ".mp4",
                toAudio ? "audio" : "video", toAudio);
    }

    private void runPipeline(PrivateWorkspace workspace, MediaInputProvider.ResolvedInput primary,
                             MediaMetadata metadata, ProcessingRequest request, Path part,
                             DoubleConsumer onProgress, ProcessListener listener) {
        Map<String, MediaInputProvider.ResolvedInput> audioSources = new HashMap<>();
        int applied = 0;
        for (Operation operation : request.operations()) {
            String type = operation.type() == null ? "" : operation.type();
            switch (type) {
                case "trim", "slice", "remove_section", "speed", "flip", "to_audio" -> applied++;
                case "volume" -> {
                    if (!metadata.hasAudio()) {
                        throw new ProcessingException("this video has no audio track to adjust");
                    }
                    applied++;
                }
                case "add_audio", "replace_audio" -> {
                    String sourceId = operation.audioSourceId();
                    MediaInputProvider.ResolvedInput source = audioSources.get(sourceId);
                    if (source == null) {
                        source = resolveAudioSource(workspace, sourceId);
                        audioSources.put(sourceId, source);
                    }
                    applied++;
                }
                default -> log.debug("ignoring unsupported runtime operation {}", type);
            }
        }
        if (applied == 0) {
            throw new ProcessingException("no valid operations provided");
        }

        Map<String, Path> audioPaths = new HashMap<>();
        audioSources.forEach((id, source) -> audioPaths.put(id, source.path()));
        CommandSpec command = commandBuilder.buildPipeline(primary.path(), part, metadata,
                request.operations(), audioPaths);
        executor.execute(command, onProgress == null ? value -> { } : onProgress, listener);
    }

    private MediaInputProvider.ResolvedInput resolveAudioSource(PrivateWorkspace workspace,
                                                               String sourceId) {
        return inputs.referenced(workspace, sourceId, "audio",
                "audio source video is not available", "unable to inspect the audio source", true);
    }

    private void runConcat(PrivateWorkspace workspace, JobRecord job, MediaRecord video,
                           MediaInputProvider.ResolvedInput primary, MediaMetadata metadata,
                           ProcessingRequest request, Path part, ProcessListener listener) {
        try {
            Operation concat = request.operations().stream()
                    .filter(operation -> "concat".equals(operation.type()))
                    .findFirst()
                    .orElseThrow(() -> new ProcessingException("no concat operation provided"));
            List<Clip> clips = concat.clips() == null ? List.of() : concat.clips();

            List<FFmpegCommandBuilder.ClipInput> clipInputs = new ArrayList<>();
            List<Path> segments = new ArrayList<>();
            for (int i = 0; i < clips.size(); i++) {
                Clip clip = clips.get(i);
                String videoId = clip.videoId();
                MediaInputProvider.ResolvedInput source = primary;
                MediaMetadata clipMetadata = metadata;
                if (videoId != null && !videoId.isBlank() && !videoId.equals(video.id())) {
                    source = inputs.concatClip(workspace, videoId, i);
                    clipMetadata = source.metadata();
                }
                boolean hasAudio = clipMetadata != null && clipMetadata.hasAudio();
                clipInputs.add(new FFmpegCommandBuilder.ClipInput(source.path(), clip.start(),
                        clip.end(), hasAudio));
                segments.add(workspace.root().resolve("segment-" + i + ".ts"));
            }

            Path listFile = workspace.write("list.txt", concatList(segments));
            int[] dimensions = concatDimensions(video, metadata);
            List<CommandSpec> commands = commandBuilder.buildConcat(clipInputs, listFile, part,
                    dimensions[0], dimensions[1], CONCAT_FRAME_RATE);
            for (CommandSpec command : commands) {
                executor.execute(command, value -> { }, listener);
            }
        } catch (ProcessingException exception) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new ProcessingException("video processing was interrupted", exception);
            }
            throw new ProcessingException(
                    "video processing failed; make sure every clip is a readable video", exception);
        }
    }

    private static byte[] concatList(List<Path> segments) {
        String content = segments.stream()
                .map(path -> "file '" + path.toString().replace("'", "'\\''") + "'\n")
                .collect(Collectors.joining());
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private void runCompose(PrivateWorkspace workspace, JobRecord job, MediaRecord video,
                            MediaInputProvider.ResolvedInput primary, MediaMetadata metadata,
                            ProcessingRequest request, Path part, ProcessListener listener) {
        Operation operation = request.operations().isEmpty() ? null : request.operations().getFirst();
        ComposePlan plan = operation == null ? null : operation.compose();
        if (plan == null) {
            throw new ProcessingException("the compose plan is missing");
        }

        try {
            ComposeInputs registry = new ComposeInputs(workspace, video, primary);
            // Input registration order must match the builder's filter traversal so input indices
            // are identical to the lazily registered graph.
            for (ComposeLayer layer : plan.layers()) {
                double start = composeTime(layer.timelineStart(), "invalid layer start");
                double end = composeTime(layer.timelineEnd(), "invalid layer end");
                if (end <= start || "text".equals(layer.kind())) {
                    continue;
                }
                registry.register(layer.videoId(), "a referenced clip is not available");
            }
            for (ComposeAudio segment : plan.audio()) {
                if (segment.volumeValue() > 0) {
                    registry.register(segment.videoId(), "a referenced clip is not available");
                    composeTime(segment.timelineStart(), "invalid audio start");
                }
            }

            int[] dimensions = concatDimensions(video, metadata);
            CommandSpec command = commandBuilder.buildCompose(plan, metadata, registry.inputs(),
                    registry.byVideoId(), dimensions[0], dimensions[1], part);
            executor.execute(command, value -> { }, listener);
        } catch (ProcessingException exception) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new ProcessingException("video processing was interrupted", exception);
            }
            throw new ProcessingException("timeline rendering failed; " + exception.getMessage(),
                    exception);
        }
    }

    private void verifyOutput(Path part, boolean wantsAudio, ProcessListener listener) {
        MediaMetadata output = ffprobe.probe(part);
        if (output.duration() <= 0 || output.size() <= 0) {
            throw new ProcessingException("video processing produced no output");
        }
        if (wantsAudio && !output.hasAudio()) {
            throw new ProcessingException("processed output has no audio stream");
        }
        if (!wantsAudio && !output.hasVideo()) {
            throw new ProcessingException("processed output has no video stream");
        }
        executor.verify(part, listener);
    }

    private static double composeTime(String value, String failureMessage) {
        try {
            return OperationTimestamp.parseOptional(value);
        } catch (ProcessingException exception) {
            throw new ProcessingException(failureMessage, exception);
        }
    }

    private static int[] concatDimensions(MediaRecord video, MediaMetadata metadata) {
        int width = video.width();
        int height = video.height();
        if (width <= 0 || height <= 0) {
            width = metadata.width();
            height = metadata.height();
        }
        if (width <= 0 || height <= 0) {
            width = 640;
            height = 360;
        }
        width &= ~1;
        height &= ~1;
        return new int[] { Math.max(2, width), Math.max(2, height) };
    }

    private enum Kind { PIPELINE, CONCAT, COMPOSE }

    private record OutputShape(Kind kind, String extension, String outputType, boolean toAudio) {
    }

    /** Mirrors the lazy unique-input registry for compose plans. */
    private final class ComposeInputs {
        private final PrivateWorkspace workspace;
        private final MediaRecord primaryVideo;
        private final MediaInputProvider.ResolvedInput primarySource;
        private final List<FFmpegCommandBuilder.InputRef> inputRefs = new ArrayList<>();
        private final Map<String, FFmpegCommandBuilder.InputRef> byVideoId = new LinkedHashMap<>();
        private FFmpegCommandBuilder.InputRef primaryRef;

        private ComposeInputs(PrivateWorkspace workspace, MediaRecord primaryVideo,
                              MediaInputProvider.ResolvedInput primarySource) {
            this.workspace = workspace;
            this.primaryVideo = primaryVideo;
            this.primarySource = primarySource;
        }

        private FFmpegCommandBuilder.InputRef register(String videoId, String failureMessage) {
            String key = videoId == null || videoId.isBlank() ? "__primary__" : videoId;
            FFmpegCommandBuilder.InputRef existing = byVideoId.get(key);
            if (existing != null) {
                return existing;
            }
            if ("__primary__".equals(key) || key.equals(primaryVideo.id())) {
                if (primaryRef == null) {
                    primaryRef = new FFmpegCommandBuilder.InputRef(inputRefs.size(), primarySource.path(),
                            primarySource.metadata().hasAudio());
                    inputRefs.add(primaryRef);
                }
                byVideoId.put("__primary__", primaryRef);
                if (primaryVideo.id() != null) {
                    byVideoId.put(primaryVideo.id(), primaryRef);
                }
                return primaryRef;
            }
            MediaInputProvider.ResolvedInput source = inputs.referenced(workspace, key,
                    "compose-" + key, failureMessage, "unable to inspect a referenced clip", false);
            FFmpegCommandBuilder.InputRef ref = new FFmpegCommandBuilder.InputRef(inputRefs.size(),
                    source.path(), source.metadata().hasAudio());
            inputRefs.add(ref);
            byVideoId.put(key, ref);
            return ref;
        }

        private List<FFmpegCommandBuilder.InputRef> inputs() {
            return List.copyOf(inputRefs);
        }

        private Map<String, FFmpegCommandBuilder.InputRef> byVideoId() {
            return Map.copyOf(byVideoId);
        }
    }
}
