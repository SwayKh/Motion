package com.motion.backend.processing;

import com.motion.backend.media.MediaRecord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Exact Go validation rules and error strings for process requests. */
public final class ProcessingValidator {

    public static final int MAX_OPERATIONS = 20;
    public static final double MIN_SPEED = 0.5D;
    public static final double MAX_SPEED = 2.0D;

    private ProcessingValidator() {
    }

    public static void validate(MediaRecord video, ProcessingRequest request) {
        List<Operation> operations = request.operations();
        if (operations.isEmpty()) {
            throw new ProcessingException("operations cannot be empty");
        }
        if (operations.size() > MAX_OPERATIONS) {
            throw new ProcessingException("too many operations max=" + MAX_OPERATIONS);
        }

        int toAudioIndex = -1;
        boolean concatSeen = false;
        for (int i = 0; i < operations.size(); i++) {
            Operation operation = operations.get(i);
            String type = operation.type() == null ? "" : operation.type();
            try {
                switch (type) {
                    case "flip" -> requireValidFlip(operation);
                    case "trim" -> requireValidTrim(video, operation);
                    case "slice" -> requireValidTrim(video, operation);
                    case "remove_section" -> requireValidTrim(video, operation);
                    case "speed" -> requireValidSpeed(operation);
                    case "volume" -> requireValidVolume(operation);
                    case "add_audio", "replace_audio" -> requireValidAudio(operation);
                    case "concat" -> {
                        if (concatSeen) {
                            throw new ProcessingException(
                                    "operation " + i + " concat invalid: only one concat operation is allowed");
                        }
                        concatSeen = true;
                        requireValidConcat(operation);
                    }
                    case "to_audio" -> {
                        if (toAudioIndex != -1) {
                            throw new ProcessingException("operation " + i
                                    + " to_audio invalid: only one extract audio operation is allowed");
                        }
                        toAudioIndex = i;
                    }
                    default -> throw new ProcessingException("operation " + i + " unknown type=" + type);
                }
            } catch (ProcessingException exception) {
                String message = exception.getMessage();
                if (message.startsWith("operation ")) {
                    throw exception;
                }
                throw new ProcessingException("operation " + i + " " + type + " invalid: " + message);
            }
        }

        if (toAudioIndex != -1 && toAudioIndex != operations.size() - 1) {
            throw new ProcessingException("operation " + toAudioIndex
                    + " to_audio invalid: extract audio must be the last operation");
        }
        if (concatSeen && operations.size() != 1) {
            throw new ProcessingException("concat must be the only operation");
        }
    }

    /** Distinct referenced video IDs other than the primary source, in the collection order. */
    public static List<String> referencedVideoIds(String primaryId, ProcessingRequest request) {
        Set<String> seen = new LinkedHashSet<>();
        for (Operation operation : request.operations()) {
            addReference(seen, primaryId, operation.audioSourceId());
            if (operation.clips() != null) {
                for (Clip clip : operation.clips()) {
                    addReference(seen, primaryId, clip.videoId());
                }
            }
            ComposePlan compose = operation.compose();
            if (compose != null) {
                for (ComposeLayer layer : compose.layers()) {
                    addReference(seen, primaryId, layer.videoId());
                }
                for (ComposeAudio audio : compose.audio()) {
                    addReference(seen, primaryId, audio.videoId());
                }
            }
        }
        return new ArrayList<>(seen);
    }

    private static void addReference(Set<String> seen, String primaryId, String id) {
        if (id != null && !id.isBlank() && !id.equals(primaryId)) {
            seen.add(id);
        }
    }

    private static void requireValidFlip(Operation operation) {
        int orientation = operation.orientationValue();
        if (orientation != 0 && orientation != 1) {
            throw new ProcessingException("direction must be vertical or horizontal");
        }
    }

    private static void requireValidSpeed(Operation operation) {
        double factor = operation.factorValue();
        if (!Double.isFinite(factor) || factor < MIN_SPEED || factor > MAX_SPEED) {
            throw new ProcessingException("speed factor must be between 0.50 and 2.00");
        }
    }

    private static void requireValidVolume(Operation operation) {
        double factor = operation.factorValue();
        if (!Double.isFinite(factor) || factor <= 0) {
            throw new ProcessingException("volume factor must be greater than 0");
        }
    }

    private static void requireValidTrim(MediaRecord video, Operation operation) {
        double start;
        try {
            start = OperationTimestamp.parse(operation.start());
        } catch (ProcessingException exception) {
            throw new ProcessingException("invalid start timestamp");
        }
        double end;
        try {
            end = OperationTimestamp.parse(operation.end());
        } catch (ProcessingException exception) {
            throw new ProcessingException("invalid end timestamp");
        }
        if (start >= end) {
            throw new ProcessingException("start must be before end");
        }
        if (video.duration() > 0 && end > video.duration() + 0.001D) {
            throw new ProcessingException("trim end exceeds video duration");
        }
    }

    private static void requireValidAudio(Operation operation) {
        if (operation.audioSourceId() == null || operation.audioSourceId().isBlank()) {
            throw new ProcessingException("an audio source video is required");
        }
        double volume = operation.audioVolumeValue();
        if (volume < 0) {
            throw new ProcessingException("audio volume must not be negative");
        }
        if (volume > 10) {
            throw new ProcessingException("audio volume is too large");
        }
        double offset = parseAudioTime(operation.offset(), "invalid offset");
        double start = parseAudioTime(operation.audioStart(), "invalid audio start");
        double end = parseAudioTime(operation.audioEnd(), "invalid audio end");
        if (operation.audioStart() != null && !operation.audioStart().isBlank()
                && operation.audioEnd() != null && !operation.audioEnd().isBlank()
                && start >= end) {
            throw new ProcessingException("audio start must be before audio end");
        }
        // Referenced by the compiler-free offset check above; the parser accepts it but the filter
        // builder applies it only when positive.
        if (offset < 0) {
            throw new ProcessingException("invalid offset");
        }
    }

    private static double parseAudioTime(String value, String message) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return OperationTimestamp.parse(value);
        } catch (ProcessingException exception) {
            throw new ProcessingException(message);
        }
    }

    private static void requireValidConcat(Operation operation) {
        List<Clip> clips = operation.clips() == null ? List.of() : operation.clips();
        if (clips.size() < 2) {
            throw new ProcessingException("concat requires at least two clips");
        }
        if (clips.size() > 20) {
            throw new ProcessingException("concat supports at most 20 clips");
        }
        for (int i = 0; i < clips.size(); i++) {
            Clip clip = clips.get(i);
            if ((clip.start() == null || clip.start().isBlank())
                    && (clip.end() == null || clip.end().isBlank())) {
                continue;
            }
            double start = 0;
            if (clip.start() != null && !clip.start().isBlank()) {
                try {
                    start = OperationTimestamp.parse(clip.start());
                } catch (ProcessingException exception) {
                    throw new ProcessingException("clip " + i + " has an invalid start");
                }
            }
            double end = 0;
            if (clip.end() != null && !clip.end().isBlank()) {
                try {
                    end = OperationTimestamp.parse(clip.end());
                } catch (ProcessingException exception) {
                    throw new ProcessingException("clip " + i + " has an invalid end");
                }
            }
            if (clip.start() != null && !clip.start().isBlank()
                    && clip.end() != null && !clip.end().isBlank() && start >= end) {
                throw new ProcessingException("clip " + i + " start must be before end");
            }
        }
    }
}
