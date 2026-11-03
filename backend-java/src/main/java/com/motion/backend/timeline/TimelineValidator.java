package com.motion.backend.timeline;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Exact Go validation codes, messages, limits, and ordering for timeline projects. */
public final class TimelineValidator {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_TRACKS = 32;
    public static final int MAX_CLIPS = 500;
    private static final double EPSILON = 1e-6;
    private static final Set<String> FATAL_SAVE_CODES = Set.of(
            "missing_name", "name_too_long", "unsupported_schema", "too_many_tracks", "too_many_clips");

    private TimelineValidator() {
    }

    public static boolean isFatalSaveCode(String code) {
        return FATAL_SAVE_CODES.contains(code);
    }

    /** Structure checks that do not require resolved assets; drafts may contain softer problems. */
    public static List<TimelineValidationError> validateStructure(TimelineProject project) {
        List<TimelineValidationError> errors = new ArrayList<>();
        if (project == null) {
            errors.add(TimelineValidationError.of("empty_project", "the project is empty"));
            return errors;
        }

        if (project.schemaVersion() != 0 && project.schemaVersion() != TimelineProject.CURRENT_SCHEMA_VERSION) {
            errors.add(TimelineValidationError.of("unsupported_schema",
                    String.format(Locale.ROOT, "unsupported project schema version %d", project.schemaVersion())));
        }

        String name = project.name() == null ? "" : project.name().trim();
        if (name.isEmpty()) {
            errors.add(TimelineValidationError.of("missing_name", "a project name is required"));
        } else if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_LENGTH) {
            errors.add(TimelineValidationError.of("name_too_long", "the project name is too long"));
        }

        List<TimelineTrack> tracks = project.tracks();
        if (tracks.isEmpty()) {
            errors.add(TimelineValidationError.of("no_tracks", "the project has no tracks"));
        }
        if (tracks.size() > MAX_TRACKS) {
            errors.add(TimelineValidationError.of("too_many_tracks",
                    String.format(Locale.ROOT, "the project has more than %d tracks", MAX_TRACKS)));
        }

        errors.addAll(validateRenderSettings(project.render()));

        Map<Integer, String> seenOrder = new HashMap<>();
        int clipCount = 0;
        for (TimelineTrack track : tracks) {
            String label = track.label();
            if (!validTrackType(track.type())) {
                errors.add(trackError(track, null, "invalid_track_type",
                        String.format(Locale.ROOT, "track %s has an unsupported type %s",
                                quote(label), quote(track.type()))));
                continue;
            }
            if (track.order() < 0) {
                errors.add(trackError(track, null, "invalid_track_order",
                        String.format(Locale.ROOT, "track %s has a negative order", quote(label))));
            }
            if (seenOrder.containsKey(track.order())) {
                errors.add(trackError(track, null, "duplicate_track_order",
                        String.format(Locale.ROOT, "tracks %s and %s share order %d",
                                quote(seenOrder.get(track.order())), quote(label), track.order())));
            }
            seenOrder.put(track.order(), label);

            errors.addAll(validateTrackClips(track));
            clipCount += track.clips().size();
        }

        if (clipCount == 0) {
            errors.add(TimelineValidationError.of("no_clips", "the project has no clips"));
        }
        if (clipCount > MAX_CLIPS) {
            errors.add(TimelineValidationError.of("too_many_clips",
                    String.format(Locale.ROOT, "the project has more than %d clips", MAX_CLIPS)));
        }
        return errors;
    }

    /** Full validation including ownership-aware asset resolution. */
    public static List<TimelineValidationError> validateForRender(
            TimelineProject project, TimelineAssetResolver resolver) {
        List<TimelineValidationError> errors = validateStructure(project);
        if (project == null) {
            return errors;
        }

        Map<String, TimelineAsset> resolved = new HashMap<>();
        for (TimelineTrack track : project.tracks()) {
            for (TimelineClip clip : track.clips()) {
                if ("text".equals(clip.kind()) || isBlank(clip.assetId()) || resolver == null) {
                    continue;
                }
                TimelineAsset asset = resolved.computeIfAbsent(clip.assetId(), resolver::resolve);
                if (asset == null) {
                    errors.add(clipError(track, clip, "asset_unavailable",
                            String.format(Locale.ROOT,
                                    "clip %s references an asset that is missing or not accessible",
                                    quote(clip.id()))));
                    continue;
                }
                if ("video".equals(clip.kind()) && !asset.hasVideo()) {
                    errors.add(clipError(track, clip, "asset_has_no_video",
                            String.format(Locale.ROOT, "clip %s references an asset with no video stream",
                                    quote(clip.id()))));
                }
                if (asset.duration() > 0 && clip.sourceOut() > asset.duration() + 0.001D) {
                    errors.add(clipError(track, clip, "source_out_of_range",
                            String.format(Locale.ROOT, "clip %s ends past the end of its source",
                                    quote(clip.id()))));
                }
            }
        }
        return errors;
    }

    private static List<TimelineValidationError> validateTrackClips(TimelineTrack track) {
        List<TimelineValidationError> errors = new ArrayList<>();
        List<TimelineClip> ordered = TimelineTime.sortedClips(track);
        for (TimelineClip clip : ordered) {
            errors.addAll(validateClip(track, clip));
        }
        for (int i = 1; i < ordered.size(); i++) {
            TimelineClip previous = ordered.get(i - 1);
            TimelineClip next = ordered.get(i);
            if (next.timelineStart() < TimelineTime.timelineEnd(previous) - EPSILON) {
                errors.add(clipError(track, next, "overlapping_clips",
                        String.format(Locale.ROOT, "clips on track %s overlap", quote(track.label()))));
            }
        }
        return errors;
    }

    private static List<TimelineValidationError> validateClip(TimelineTrack track, TimelineClip clip) {
        List<TimelineValidationError> errors = new ArrayList<>();
        String kind = clip.kind();
        if (!validClipKind(kind)) {
            errors.add(clipError(track, clip, "invalid_clip_kind",
                    String.format(Locale.ROOT, "clip %s has an unsupported kind %s",
                            quote(clip.id()), quote(kind))));
            return errors;
        }
        if (!kindAllowedOnTrack(kind, track.type())) {
            errors.add(clipError(track, clip, "clip_track_mismatch",
                    String.format(Locale.ROOT, "a %s clip is not allowed on a %s track", kind, track.type())));
        }
        if (clip.timelineStart() < -EPSILON) {
            errors.add(clipError(track, clip, "negative_start",
                    String.format(Locale.ROOT, "clip %s starts before the timeline origin", quote(clip.id()))));
        }
        if (clip.sourceOut() <= clip.sourceIn() + EPSILON) {
            errors.add(clipError(track, clip, "invalid_source_range",
                    String.format(Locale.ROOT, "clip %s has an empty or inverted source range", quote(clip.id()))));
        }
        if (clip.volume() < -EPSILON) {
            errors.add(clipError(track, clip, "invalid_volume",
                    String.format(Locale.ROOT, "clip %s has a negative volume", quote(clip.id()))));
        }
        if (clip.opacity() < -EPSILON || clip.opacity() > 1D + EPSILON) {
            errors.add(clipError(track, clip, "invalid_opacity",
                    String.format(Locale.ROOT, "clip %s opacity must be between 0 and 1", quote(clip.id()))));
        }
        if (clip.scale() <= 0D) {
            errors.add(clipError(track, clip, "invalid_scale",
                    String.format(Locale.ROOT, "clip %s scale must be greater than 0", quote(clip.id()))));
        }

        switch (kind) {
            case "text" -> {
                if (isBlank(clip.text())) {
                    errors.add(clipError(track, clip, "empty_text",
                            String.format(Locale.ROOT, "text clip %s has no text", quote(clip.id()))));
                }
                if (!isBlank(clip.assetId())) {
                    errors.add(clipError(track, clip, "text_with_asset",
                            String.format(Locale.ROOT, "text clip %s must not reference an asset", quote(clip.id()))));
                }
            }
            case "video", "audio", "image" -> {
                if (isBlank(clip.assetId())) {
                    errors.add(clipError(track, clip, "missing_asset",
                            String.format(Locale.ROOT, "clip %s does not reference an asset", quote(clip.id()))));
                }
            }
            default -> {
                // Reported by invalid_clip_kind above.
            }
        }
        return errors;
    }

    private static List<TimelineValidationError> validateRenderSettings(RenderSettings settings) {
        List<TimelineValidationError> errors = new ArrayList<>();
        int width = settings.width();
        if (width != 0) {
            if (width < 16 || width > 7680) {
                errors.add(TimelineValidationError.of("invalid_render_width",
                        "render width must be between 16 and 7680"));
            } else if (width % 2 != 0) {
                errors.add(TimelineValidationError.of("invalid_render_width", "render width must be even"));
            }
        }
        int height = settings.height();
        if (height != 0) {
            if (height < 16 || height > 4320) {
                errors.add(TimelineValidationError.of("invalid_render_height",
                        "render height must be between 16 and 4320"));
            } else if (height % 2 != 0) {
                errors.add(TimelineValidationError.of("invalid_render_height", "render height must be even"));
            }
        }
        int fps = settings.fps();
        if (fps != 0 && (fps < 1 || fps > 120)) {
            errors.add(TimelineValidationError.of("invalid_render_fps",
                    "render frame rate must be between 1 and 120"));
        }
        int sampleRate = settings.sampleRate();
        if (sampleRate != 0 && (sampleRate < 8000 || sampleRate > 192000)) {
            errors.add(TimelineValidationError.of("invalid_render_sample_rate",
                    "render sample rate must be between 8000 and 192000"));
        }
        int channels = settings.channels();
        if (channels != 0 && (channels < 1 || channels > 8)) {
            errors.add(TimelineValidationError.of("invalid_render_channels",
                    "render channel count must be between 1 and 8"));
        }
        return errors;
    }

    private static TimelineValidationError trackError(
            TimelineTrack track, TimelineClip clip, String code, String message) {
        return new TimelineValidationError(code, message, track.id(), clip == null ? null : clip.id());
    }

    private static TimelineValidationError clipError(
            TimelineTrack track, TimelineClip clip, String code, String message) {
        return new TimelineValidationError(code, message, track.id(), clip.id());
    }

    private static boolean validTrackType(String type) {
        return Set.of("video", "audio", "image", "text").contains(type);
    }

    private static boolean validClipKind(String kind) {
        return Set.of("video", "audio", "image", "text").contains(kind);
    }

    private static boolean kindAllowedOnTrack(String kind, String trackType) {
        return switch (trackType == null ? "" : trackType) {
            case "video" -> "video".equals(kind);
            case "audio" -> "audio".equals(kind);
            case "image" -> "image".equals(kind);
            case "text" -> "text".equals(kind);
            default -> false;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Mirrors the {@code %q} formatting for the IDs and labels used in messages. */
    private static String quote(String value) {
        String text = value == null ? "" : value;
        StringBuilder result = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
