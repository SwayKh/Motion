package com.motion.backend.timeline;

import java.util.ArrayList;
import java.util.List;

/** Applies the project defaults and clamps, once per create/update/render. */
public final class TimelineNormalizer {

    private TimelineNormalizer() {
    }

    public static TimelineProject normalize(TimelineProject project) {
        if (project == null) {
            return null;
        }
        int schemaVersion = project.schemaVersion() == 0
                ? TimelineProject.CURRENT_SCHEMA_VERSION : project.schemaVersion();
        List<TimelineTrack> tracks = new ArrayList<>(project.tracks().size());
        for (TimelineTrack track : project.tracks()) {
            List<TimelineClip> clips = new ArrayList<>(track.clips().size());
            for (TimelineClip clip : track.clips()) {
                double scale = clip.scale() <= 0D ? 1D : clip.scale();
                double volume = Math.max(0D, clip.volume());
                double opacity = Math.min(1D, Math.max(0D, clip.opacity()));
                clips.add(new TimelineClip(
                        clip.id(), clip.kind(), clip.assetId(),
                        clip.sourceIn(), clip.sourceOut(), clip.timelineStart(),
                        volume, opacity, clip.x(), clip.y(), scale, clip.text()));
            }
            tracks.add(new TimelineTrack(
                    track.id(), track.type(), track.name(), track.order(),
                    track.visible(), track.muted(), clips));
        }
        return new TimelineProject(
                project.id(), project.name(), schemaVersion, tracks, project.render(),
                project.revision(), project.createdAt(), project.updatedAt());
    }
}
