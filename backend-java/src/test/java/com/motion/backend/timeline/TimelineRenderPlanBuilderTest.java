package com.motion.backend.timeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.processing.ProcessingException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure translation tests for Go's two render-plan shapes and build error strings. */
class TimelineRenderPlanBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oneClipAndAudioClipsUseTheProvenSliceAndAddAudioPipeline() throws Exception {
        TimelineRenderPlan plan = TimelineRenderPlanBuilder.build(
                project(
                        track("v", "video", 0, true, false, List.of(
                                clip("c1", "video", "primary", 0.5D, 2.25D, 0D, 1D))),
                        track("a", "audio", 1, true, false, List.of(
                                clip("c2", "audio", "music", 1D, 3D, 0.5D, 0.8D)))),
                id -> new TimelineAsset(id, 10D, true, true));

        assertThat(plan.primaryVideoId()).isEqualTo("primary");
        assertThat(plan.duration()).isEqualTo(2.5D);
        String json = mapper.writeValueAsString(plan.request());
        assertThat(json).isEqualTo("{\"operations\":["
                + "{\"type\":\"slice\",\"start\":\"0.5\",\"end\":\"2.25\"},"
                + "{\"type\":\"add_audio\",\"audioSourceId\":\"music\",\"audioVolume\":0.8,"
                + "\"offset\":\"0.5\",\"audioStart\":\"1\",\"audioEnd\":\"3\"}]}");
    }

    @Test
    void contiguousClipsWithoutAudioUseConcatAndGapsForceCompose() {
        TimelineProject contiguous = project(
                track("v", "video", 0, true, false, List.of(
                        clip("c1", "video", "primary", 0D, 1D, 0D, 1D),
                        clip("c2", "video", "other", 2D, 3D, 1D, 1D))));
        assertThat(mapper.valueToTree(TimelineRenderPlanBuilder.build(
                contiguous, id -> new TimelineAsset(id, 10D, true, true)).request())
                .path("operations").get(0).path("type").asText()).isEqualTo("concat");

        TimelineProject gap = project(
                track("v", "video", 0, true, false, List.of(
                        clip("c1", "video", "primary", 0D, 1D, 0D, 1D),
                        clip("c2", "video", "other", 2D, 3D, 1.5D, 1D))));
        assertThat(mapper.valueToTree(TimelineRenderPlanBuilder.build(
                gap, id -> new TimelineAsset(id, 10D, true, true)).request())
                .path("operations").get(0).path("type").asText()).isEqualTo("compose");
    }

    @Test
    void composePlanOrdersLayersDropsOverlayAudioAndKeepsBaseAudio() throws Exception {
        TimelineProject project = new TimelineProject(
                null, "Compose", 1,
                List.of(
                        track("overlay", "image", 0, true, false, List.of(
                                clip("o", "image", "overlay", 0D, 1D, 0.2D, 1D))),
                        track("base", "video", 1, true, false, List.of(
                                clip("b", "video", "primary", 0D, 2D, 0D, 1D))),
                        track("text", "text", 2, true, false, List.of(
                                new TimelineClip("t", "text", null, 0D, 1D, 0.5D, 1D, 1D,
                                        12D, 20D, 1D, "Hello"))),
                        track("audio", "audio", 3, true, false, List.of(
                                clip("a", "audio", "music", 0D, 2D, 0D, 1D)))),
                new RenderSettings(320, 240, 15, 0, 0), 0, null, null);

        TimelineRenderPlan plan = TimelineRenderPlanBuilder.build(
                project, id -> new TimelineAsset(id, 10D, true, true));
        var compose = mapper.valueToTree(plan.request()).path("operations").get(0).path("compose");

        assertThat(plan.primaryVideoId()).isEqualTo("primary");
        assertThat(compose.path("layers").size()).isEqualTo(3);
        assertThat(compose.path("layers").get(0).path("kind").asText()).isEqualTo("image");
        assertThat(compose.path("layers").get(0).path("base").asBoolean()).isFalse();
        assertThat(compose.path("layers").get(1).path("base").asBoolean()).isTrue();
        assertThat(compose.path("layers").get(2).path("text").asText()).isEqualTo("Hello");
        assertThat(compose.path("layers").get(2).path("videoId").isMissingNode()).isTrue();
        assertThat(compose.path("audio").size()).isEqualTo(2);
        assertThat(compose.path("audio").get(0).path("videoId").asText()).isEqualTo("primary");
        assertThat(compose.path("audio").get(1).path("videoId").asText()).isEqualTo("music");
        assertThat(compose.path("hasAudio").asBoolean()).isTrue();
        assertThat(mapper.writeValueAsString(plan.request())).doesNotContain("\"volume\":0");
    }

    @Test
    void buildErrorsMatchGoExactly() {
        TimelineAssetResolver resolver = id -> new TimelineAsset(id, 10D, true, true);

        assertThatThrownBy(() -> TimelineRenderPlanBuilder.build(project(
                track("a", "audio", 0, true, false, List.of(
                        clip("c", "audio", "primary", 0D, 1D, 0D, 1D)))), resolver))
                .isInstanceOf(ProcessingException.class)
                .hasMessage("the project has no visible video clip to render");

        assertThatThrownBy(() -> TimelineRenderPlanBuilder.build(project(
                track("v", "video", 0, true, false, List.of(
                        clip("c", "video", "primary", 0D, 1D, 0.5D, 1D)))), resolver))
                .isInstanceOf(ProcessingException.class)
                .hasMessage("the renderer needs a video clip that starts at the timeline origin");

        assertThatThrownBy(() -> TimelineRenderPlanBuilder.build(project(
                track("v", "video", 0, true, false, List.of(
                        clip("c", "video", "", 0D, 1D, 0D, 1D)))), resolver))
                .isInstanceOf(ProcessingException.class)
                .hasMessage("the first video clip does not reference an asset");

        assertThatThrownBy(() -> TimelineRenderPlanBuilder.build(project(
                track("v", "video", 0, true, false, List.of(
                        clip("c", "video", "primary", 0D, 1D, 0D, 1D)))), id -> null))
                .isInstanceOf(ProcessingException.class)
                .hasMessage("the first video clip references an asset that is missing or not accessible");
    }

    private static TimelineProject project(TimelineTrack... tracks) {
        return new TimelineProject(null, "Plan", 1, List.of(tracks), RenderSettings.empty(),
                0, null, null);
    }

    private static TimelineTrack track(String id, String type, int order, boolean visible,
                                       boolean muted, List<TimelineClip> clips) {
        return new TimelineTrack(id, type, null, order, visible, muted, clips);
    }

    private static TimelineClip clip(String id, String kind, String assetId, double sourceIn,
                                     double sourceOut, double timelineStart, double volume) {
        return new TimelineClip(id, kind, assetId, sourceIn, sourceOut, timelineStart,
                volume, 1D, 0D, 0D, 1D, null);
    }
}
