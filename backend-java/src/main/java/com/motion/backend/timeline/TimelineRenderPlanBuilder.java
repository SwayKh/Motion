package com.motion.backend.timeline;

import com.motion.backend.processing.ComposeAudio;
import com.motion.backend.processing.ComposeLayer;
import com.motion.backend.processing.ComposePlan;
import com.motion.backend.processing.Clip;
import com.motion.backend.processing.OperationTimestamp;
import com.motion.backend.processing.Operation;
import com.motion.backend.processing.ProcessingException;
import com.motion.backend.processing.ProcessingRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Java translation of the {@code internal/timeline.BuildRenderPlan}.
 *
 * <p>The returned {@link ProcessingRequest} is a pure value tree. It is serialized into the queued
 * job at submission time, so later edits to the saved project cannot change what the worker renders.
 * Every non-text asset is checked through the ownership-aware resolver before it enters the plan.</p>
 */
public final class TimelineRenderPlanBuilder {

    private static final double EPSILON = 1e-6D;

    private TimelineRenderPlanBuilder() {
    }

    public static TimelineRenderPlan build(TimelineProject project, TimelineAssetResolver resolve) {
        if (project == null) {
            throw new ProcessingException("the project is empty");
        }

        List<VisualTrack> visual = new ArrayList<>();
        List<TimelineClip> audioClips = new ArrayList<>();
        for (TimelineTrack track : project.tracks()) {
            switch (track.type()) {
                case "video", "image", "text" -> {
                    if (Boolean.TRUE.equals(track.visible())) {
                        List<TimelineClip> clips = TimelineTime.sortedClips(track);
                        if (!clips.isEmpty()) {
                            visual.add(new VisualTrack(track.order(), track.type(), clips));
                        }
                    }
                }
                case "audio" -> {
                    if (!Boolean.TRUE.equals(track.muted())) {
                        audioClips.addAll(TimelineTime.sortedClips(track));
                    }
                }
                default -> {
                    // Other track types cannot contribute to a render.
                }
            }
        }
        visual.sort(Comparator.comparingInt(VisualTrack::order));

        int baseIndex = -1;
        for (int i = 0; i < visual.size(); i++) {
            if ("video".equals(visual.get(i).type())) {
                baseIndex = i;
                break;
            }
        }
        if (baseIndex < 0) {
            throw new ProcessingException("the project has no visible video clip to render");
        }

        List<TimelineClip> baseClips = visual.get(baseIndex).clips();
        if (baseClips.getFirst().timelineStart() > EPSILON) {
            throw new ProcessingException(
                    "the renderer needs a video clip that starts at the timeline origin");
        }
        if (isBlank(baseClips.getFirst().assetId())) {
            throw new ProcessingException("the first video clip does not reference an asset");
        }
        String primary = baseClips.getFirst().assetId();
        if (resolve != null && resolve.resolve(primary) == null) {
            throw new ProcessingException(
                    "the first video clip references an asset that is missing or not accessible");
        }

        audioClips.sort(Comparator.comparingDouble(TimelineClip::timelineStart));

        boolean simple = visual.size() == 1
                && baseContiguous(baseClips)
                && (baseClips.size() == 1 || audioClips.isEmpty());
        if (simple) {
            return buildSimplePlan(project, baseClips, audioClips, primary, resolve);
        }

        ComposePlan compose = buildComposePlan(project, visual, baseIndex, audioClips, resolve);
        List<Operation> operations = List.of(new Operation(
                "compose", null, null, null, null, null, null, null, null, null, null, compose));
        return new TimelineRenderPlan(primary, new ProcessingRequest(operations),
                TimelineTime.projectDuration(project));
    }

    private static boolean baseContiguous(List<TimelineClip> clips) {
        for (int i = 1; i < clips.size(); i++) {
            double previousEnd = TimelineTime.timelineEnd(clips.get(i - 1));
            double nextStart = clips.get(i).timelineStart();
            if (Math.abs(previousEnd - nextStart) > EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static TimelineRenderPlan buildSimplePlan(
            TimelineProject project,
            List<TimelineClip> clips,
            List<TimelineClip> audioClips,
            String primary,
            TimelineAssetResolver resolve) {
        List<Operation> operations = new ArrayList<>();
        if (clips.size() == 1) {
            TimelineClip clip = clips.getFirst();
            operations.add(new Operation(
                    "slice", seconds(clip.sourceIn()), seconds(clip.sourceOut()),
                    null, null, null, null, null, null, null, null, null));
        } else {
            List<Clip> joined = new ArrayList<>(clips.size());
            for (TimelineClip clip : clips) {
                joined.add(new Clip(clip.assetId(), seconds(clip.sourceIn()), seconds(clip.sourceOut())));
            }
            operations.add(new Operation(
                    "concat", null, null, null, null, null, null, null, null, null, joined, null));
        }

        for (TimelineClip clip : audioClips) {
            if (clip.volume() <= 0D) {
                continue;
            }
            if (isBlank(clip.assetId())) {
                throw new ProcessingException("an audio clip does not reference an asset");
            }
            if (resolve != null && resolve.resolve(clip.assetId()) == null) {
                throw new ProcessingException(
                        "an audio clip references an asset that is missing or not accessible");
            }
            operations.add(new Operation(
                    "add_audio", null, null, null, null, clip.assetId(), clip.volume(),
                    seconds(clip.timelineStart()), seconds(clip.sourceIn()), seconds(clip.sourceOut()),
                    null, null));
        }

        return new TimelineRenderPlan(primary, new ProcessingRequest(List.copyOf(operations)),
                TimelineTime.projectDuration(project));
    }

    private static ComposePlan buildComposePlan(
            TimelineProject project,
            List<VisualTrack> visual,
            int baseIndex,
            List<TimelineClip> audioClips,
            TimelineAssetResolver resolve) {
        List<ComposeLayer> layers = new ArrayList<>();
        for (int i = 0; i < visual.size(); i++) {
            for (TimelineClip clip : visual.get(i).clips()) {
                String kind;
                String videoId = clip.assetId();
                String text = null;
                boolean base = false;
                switch (visual.get(i).type()) {
                    case "text" -> {
                        kind = "text";
                        videoId = null;
                        text = clip.text();
                    }
                    case "image" -> kind = "image";
                    default -> {
                        kind = "video";
                        base = i == baseIndex;
                    }
                }

                if (!"text".equals(kind)) {
                    if (isBlank(videoId)) {
                        throw new ProcessingException("a " + kind + " clip does not reference an asset");
                    }
                    if (resolve != null && resolve.resolve(videoId) == null) {
                        throw new ProcessingException("a " + kind
                                + " clip references an asset that is missing or not accessible");
                    }
                }

                layers.add(new ComposeLayer(
                        kind,
                        blankToNull(videoId),
                        text,
                        seconds(clip.sourceIn()),
                        seconds(clip.sourceOut()),
                        seconds(clip.timelineStart()),
                        seconds(TimelineTime.timelineEnd(clip)),
                        nonzero(clip.x()),
                        nonzero(clip.y()),
                        nonzero(clip.scale()),
                        nonzero(clip.opacity()),
                        base ? Boolean.TRUE : null));
            }
        }

        List<ComposeAudio> audio = new ArrayList<>();
        for (TimelineClip clip : visual.get(baseIndex).clips()) {
            if (clip.volume() <= 0D || isBlank(clip.assetId())) {
                continue;
            }
            audio.add(composeAudio(clip));
        }
        for (TimelineClip clip : audioClips) {
            if (clip.volume() <= 0D) {
                continue;
            }
            if (isBlank(clip.assetId())) {
                throw new ProcessingException("an audio clip does not reference an asset");
            }
            if (resolve != null && resolve.resolve(clip.assetId()) == null) {
                throw new ProcessingException(
                        "an audio clip references an asset that is missing or not accessible");
            }
            audio.add(composeAudio(clip));
        }

        RenderSettings settings = project.render();
        return new ComposePlan(
                positiveOrNull(settings.width()),
                positiveOrNull(settings.height()),
                positiveOrNull(settings.fps()),
                seconds(TimelineTime.projectDuration(project)),
                List.copyOf(layers),
                List.copyOf(audio),
                audio.isEmpty() ? null : Boolean.TRUE);
    }

    private static ComposeAudio composeAudio(TimelineClip clip) {
        return new ComposeAudio(
                clip.assetId(), seconds(clip.sourceIn()), seconds(clip.sourceOut()),
                seconds(clip.timelineStart()), nonzero(clip.volume()));
    }

    private static String seconds(double value) {
        return OperationTimestamp.formatSeconds(value);
    }

    private static Double nonzero(double value) {
        return value == 0D ? null : value;
    }

    private static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record VisualTrack(int order, String type, List<TimelineClip> clips) {
    }
}
