package com.motion.backend.timeline;

import java.util.Comparator;
import java.util.List;

/** Source/timeline timestamp math mirroring the {@code internal/timeline/time.go}. */
public final class TimelineTime {

    private TimelineTime() {
    }

    public static double duration(TimelineClip clip) {
        return Math.max(0D, clip.sourceOut() - clip.sourceIn());
    }

    public static double timelineEnd(TimelineClip clip) {
        return clip.timelineStart() + duration(clip);
    }

    public static boolean contains(TimelineClip clip, double timelineTime) {
        return timelineTime >= clip.timelineStart() && timelineTime < timelineEnd(clip);
    }

    public static double sourceToTimeline(TimelineClip clip, double sourceTime) {
        return clip.timelineStart() + (sourceTime - clip.sourceIn());
    }

    public static double timelineToSource(TimelineClip clip, double timelineTime) {
        return clip.sourceIn() + (timelineTime - clip.timelineStart());
    }

    public static List<TimelineClip> sortedClips(TimelineTrack track) {
        return track.clips().stream()
                .sorted(Comparator.comparingDouble(TimelineClip::timelineStart))
                .toList();
    }

    public static double projectDuration(TimelineProject project) {
        double duration = 0D;
        for (TimelineTrack track : project.tracks()) {
            for (TimelineClip clip : track.clips()) {
                duration = Math.max(duration, timelineEnd(clip));
            }
        }
        return duration;
    }
}
