package com.motion.backend.timeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

/** application persistent timeline project document. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "id", "name", "schemaVersion", "tracks", "render", "revision", "createdAt", "updatedAt"
})
public record TimelineProject(
        String id,
        String name,
        Integer schemaVersion,
        List<TimelineTrack> tracks,
        RenderSettings render,
        Integer revision,
        String createdAt,
        String updatedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public TimelineProject {
        id = TimelineJson.blankToNull(id);
        name = name == null ? "" : name;
        schemaVersion = schemaVersion == null ? 0 : schemaVersion;
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        render = render == null ? RenderSettings.empty() : render;
        revision = revision == null ? 0 : revision;
        createdAt = TimelineJson.blankToNull(createdAt);
        updatedAt = TimelineJson.blankToNull(updatedAt);
    }

    public TimelineProject withMetadata(String id, Integer revision, String createdAt, String updatedAt) {
        return new TimelineProject(id, name, schemaVersion, tracks, render,
                revision, createdAt, updatedAt);
    }

    /** Re-applies server-owned columns, matching the {@code scanTimelineProject}. */
    public TimelineProject withStoredMetadata(
            String id, String name, Integer revision, String createdAt, String updatedAt) {
        return new TimelineProject(id, name, schemaVersion, tracks, render,
                revision, createdAt, updatedAt);
    }
}
