package com.motion.backend.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure model/normalization/validation/time tests mirroring Go's timeline package. */
class TimelineModelTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void omittedBooleansAndMixingValuesUseGoDefaults() throws Exception {
        String json = """
                {"id":"p","name":"Draft","schemaVersion":0,"tracks":[
                  {"id":"v","type":"video","order":0,"clips":[
                    {"id":"c","kind":"video","assetId":"a","sourceIn":0,"sourceOut":2,"timelineStart":0}
                  ]}
                ],"render":{}}
                """;
        TimelineProject project = mapper.readValue(json, TimelineProject.class);
        TimelineTrack track = project.tracks().getFirst();
        TimelineClip clip = track.clips().getFirst();

        assertThat(track.visible()).isTrue();
        assertThat(track.muted()).isFalse();
        assertThat(clip.volume()).isEqualTo(1D);
        assertThat(clip.opacity()).isEqualTo(1D);
        assertThat(clip.scale()).isEqualTo(1D);

        TimelineProject normalized = TimelineNormalizer.normalize(project);
        assertThat(normalized.schemaVersion()).isEqualTo(1);
    }

    @Test
    void explicitFalseAndZeroArePreserved() throws Exception {
        String json = """
                {"id":"p","name":"Draft","schemaVersion":1,"tracks":[
                  {"id":"v","type":"video","order":0,"visible":false,"clips":[
                    {"id":"c","kind":"video","assetId":"a","sourceIn":0,"sourceOut":2,
                     "timelineStart":0,"volume":0,"opacity":0,"scale":1}
                  ]}
                ],"render":{}}
                """;
        TimelineProject project = TimelineNormalizer.normalize(mapper.readValue(json, TimelineProject.class));
        TimelineTrack track = project.tracks().getFirst();
        TimelineClip clip = track.clips().getFirst();

        assertThat(track.visible()).isFalse();
        assertThat(clip.volume()).isZero();
        assertThat(clip.opacity()).isZero();
    }

    @Test
    void normalizationClampsScaleVolumeAndOpacity() {
        TimelineClip clip = new TimelineClip("c", "video", "a", 0D, 2D, 0D, -1D, 2D, 0D, 0D, -1D, null);
        TimelineProject project = new TimelineProject(
                "p", "Draft", 0,
                List.of(new TimelineTrack("v", "video", null, 0, true, false, List.of(clip))),
                RenderSettings.empty(), 0, null, null);

        TimelineProject normalized = TimelineNormalizer.normalize(project);
        TimelineClip fixed = normalized.tracks().getFirst().clips().getFirst();

        assertThat(normalized.schemaVersion()).isEqualTo(1);
        assertThat(fixed.volume()).isZero();
        assertThat(fixed.opacity()).isEqualTo(1D);
        assertThat(fixed.scale()).isEqualTo(1D);
    }

    @Test
    void timeMathSeparatesSourceAndTimelinePositions() {
        TimelineClip clip = new TimelineClip("c", "video", "a", 2D, 5D, 10D, 1D, 1D, 0D, 0D, 1D, null);

        assertThat(clip.duration()).isEqualTo(3D);
        assertThat(clip.timelineEnd()).isEqualTo(13D);
        assertThat(TimelineTime.timelineToSource(clip, 11D)).isEqualTo(3D);
        assertThat(TimelineTime.sourceToTimeline(clip, 4D)).isEqualTo(12D);
        assertThat(TimelineTime.contains(clip, 10D)).isTrue();
        assertThat(TimelineTime.contains(clip, 13D)).isFalse();
    }

    @Test
    void validationUsesGoCodesAndMessages() {
        TimelineClip clip = new TimelineClip("c", "audio", "a", 2D, 1D, -0.5D, 1D, 1D, 0D, 0D, 1D, null);
        TimelineTrack track = new TimelineTrack("v", "video", "Video 1", 0, true, false, List.of(clip));
        TimelineProject project = new TimelineProject(
                null, "", 0, List.of(track),
                new RenderSettings(17, 8, 0, 0, 0), 0, null, null);

        List<TimelineValidationError> errors = TimelineValidator.validateStructure(project);
        assertThat(errors).extracting(TimelineValidationError::code).contains(
                "missing_name", "clip_track_mismatch", "negative_start", "invalid_source_range",
                "invalid_render_width", "invalid_render_height");
        assertThat(errors).anySatisfy(error -> {
            assertThat(error.code()).isEqualTo("invalid_render_width");
            assertThat(error.message()).isEqualTo("render width must be even");
        });
    }

    @Test
    void renderValidationChecksOwnershipAndSourceRange() {
        TimelineClip clip = new TimelineClip("c", "video", "missing", 0D, 9D, 0D, 1D, 1D, 0D, 0D, 1D, null);
        TimelineTrack track = new TimelineTrack("v", "video", "Video 1", 0, true, false, List.of(clip));
        TimelineProject project = new TimelineProject(
                "p", "Draft", 1, List.of(track), RenderSettings.empty(), 1, null, null);

        List<TimelineValidationError> errors = TimelineValidator.validateForRender(project, id -> null);
        assertThat(errors).extracting(TimelineValidationError::code).contains("asset_unavailable");

        errors = TimelineValidator.validateForRender(project,
                id -> new TimelineAsset(id, 2D, true, true));
        assertThat(errors).extracting(TimelineValidationError::code).contains("source_out_of_range");
    }

    @Test
    void serializedProjectUsesGoFieldOrderAndOmitempty() throws Exception {
        TimelineClip clip = new TimelineClip("c", "text", null, 0D, 1D, 0D, 1D, 1D, 0D, 0D, 1D, "Hello");
        TimelineTrack track = new TimelineTrack("t", "text", null, 0, true, false, List.of(clip));
        TimelineProject project = new TimelineProject(
                "p", "Draft", 1, List.of(track), RenderSettings.empty(), 1, null, null);

        String json = mapper.writeValueAsString(project);
        assertThat(json).startsWith(
                "{\"id\":\"p\",\"name\":\"Draft\",\"schemaVersion\":1,\"tracks\":[");
        assertThat(json).contains("\"id\":\"t\",\"type\":\"text\",\"order\":0,\"visible\":true");
        assertThat(json).doesNotContain("\"assetId\"").doesNotContain("\"createdAt\"");
    }
}
