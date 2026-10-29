package com.motion.backend.processing;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compile-time argv parity with the authoritative ffmpeg-go output captured from Go. */
class FFmpegCommandBuilderTest {

    private static final Path IN = Path.of("/tmp/in.mp4");
    private static final Path AUDIO = Path.of("/tmp/audio.mp4");
    private static final Path OUT = Path.of("/tmp/out.mp4");
    private static final FFmpegCommandBuilder BUILDER = new FFmpegCommandBuilder();

    @Test
    void trimMatchesGoArgvIncludingDoubleEscapedColons() {
        Operation operation = op("trim", "00:00:01", "00:00:02.5", null, null);

        assertThat(build(operation).arguments()).containsExactly(
                "ffmpeg", "-i", "/tmp/in.mp4", "-filter_complex",
                "[0:v]trim=start=00\\\\:00\\\\:01:end=00\\\\:00\\\\:02.5[s0];"
                        + "[s0]setpts=PTS-STARTPTS[s1];"
                        + "[0:a]atrim=start=00\\\\:00\\\\:01:end=00\\\\:00\\\\:02.5[s2];"
                        + "[s2]asetpts=PTS-STARTPTS[s3]",
                "-map", "[s1]", "-map", "[s3]",
                "-nostats", "-progress", "pipe:1", "/tmp/out.mp4", "-y");
    }

    @Test
    void speedMatchesGoArgvAndPassthroughFrameMode() {
        Operation operation = op("speed", null, null, 1.5D, null);
        CommandSpec command = build(operation);

        assertThat(command.arguments()).containsExactly(
                "ffmpeg", "-i", "/tmp/in.mp4", "-filter_complex",
                "[0:v]setpts=0.67*PTS[s0];[0:a]atempo=1.5[s1]",
                "-map", "[s0]", "-map", "[s1]",
                "-fps_mode", "passthrough", "-nostats", "-progress", "pipe:1",
                "/tmp/out.mp4", "-y");
        assertThat(command.progressEnabled()).isTrue();
        assertThat(command.durationSeconds()).isEqualTo(10D);
    }

    @Test
    void flipsAndVolumeMatchGoArgv() {
        assertThat(build(op("flip", null, null, null, 1)).arguments()).containsExactly(
                "ffmpeg", "-i", "/tmp/in.mp4", "-filter_complex", "[0:v]hflip[s0]",
                "-map", "[s0]", "-map", "0:a", "-c:a", "copy",
                "-nostats", "-progress", "pipe:1", "/tmp/out.mp4", "-y");

        assertThat(build(op("volume", null, null, 2D, null)).arguments()).containsExactly(
                "ffmpeg", "-i", "/tmp/in.mp4", "-filter_complex", "[0:a]volume=2[s0]",
                "-map", "0:v", "-map", "[s0]", "-c:v", "copy",
                "-nostats", "-progress", "pipe:1", "/tmp/out.mp4", "-y");
    }

    @Test
    void removeSectionMatchesGoEscapedExpression() {
        assertThat(build(op("remove_section", "0.5", "1", null, null)).arguments()).containsExactly(
                "ffmpeg", "-i", "/tmp/in.mp4", "-filter_complex",
                "[0:v]select=expr=not(between(t\\,0.5\\,1))[s0];"
                        + "[s0]setpts=N/FRAME_RATE/TB[s1];"
                        + "[0:a]aselect=expr=not(between(t\\,0.5\\,1))[s2];"
                        + "[s2]asetpts=N/SR/TB[s3]",
                "-map", "[s1]", "-map", "[s3]", "-nostats", "-progress", "pipe:1",
                "/tmp/out.mp4", "-y");
    }

    @Test
    void toAudioMatchesGoCodecAndOptionOrder() {
        Operation operation = new Operation("to_audio", null, null, null, null, null, null,
                null, null, null, null, null);
        assertThat(build(operation).arguments()).containsExactly(
                "ffmpeg", "-i", "/tmp/in.mp4", "-map", "0:a", "-c:a", "libmp3lame",
                "-nostats", "-progress", "pipe:1", "-q:a", "2", "-vn", "/tmp/out.mp4", "-y");
    }

    @Test
    void addAudioMatchesGoInputAndAmixGraph() {
        Operation operation = new Operation("add_audio", null, null, null, null, "audio", 0.5D,
                "1.5", "0.25", null, null, null);
        CommandSpec command = BUILDER.buildPipeline(IN, OUT, metadata(true), List.of(operation),
                Map.of("audio", AUDIO));

        assertThat(command.arguments()).containsExactly(
                "ffmpeg", "-i", "/tmp/in.mp4", "-i", "/tmp/audio.mp4", "-filter_complex",
                "[1:a]atrim=start=0.25:end=[s0];[s0]asetpts=PTS-STARTPTS[s1];"
                        + "[s1]volume=0.5[s2];[s2]adelay=1500:all=1[s3];"
                        + "[0:a][s3]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0[s4]",
                "-map", "0:v", "-map", "[s4]", "-c:v", "copy",
                "-nostats", "-progress", "pipe:1", "/tmp/out.mp4", "-y");
    }

    @Test
    void videoOnlySourceDoesNotSetAudioCopy() {
        assertThat(BUILDER.buildPipeline(IN, OUT, metadata(false),
                List.of(op("flip", null, null, null, 0)), Map.of()).arguments()).containsExactly(
                "ffmpeg", "-i", "/tmp/in.mp4", "-filter_complex", "[0:v]vflip[s0]",
                "-map", "[s0]", "-nostats", "-progress", "pipe:1", "/tmp/out.mp4", "-y");
    }

    @Test
    void concatUsesNormalizationThenCopyDemuxer() {
        Path scratch = Path.of("/tmp/work");
        Path list = scratch.resolve("list.txt");
        List<FFmpegCommandBuilder.ClipInput> clips = List.of(
                new FFmpegCommandBuilder.ClipInput(IN, "0", "1", true),
                new FFmpegCommandBuilder.ClipInput(AUDIO, null, "1", false));

        List<CommandSpec> commands = BUILDER.buildConcat(clips, list, OUT, 160, 120, 30);
        assertThat(commands).hasSize(3);
        assertThat(commands.getFirst().arguments()).containsExactly(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-ss", "0", "-i", "/tmp/in.mp4", "-t", "1",
                "-map", "0:v:0", "-map", "0:a:0",
                "-vf", "scale=160:120:force_original_aspect_ratio=decrease,"
                        + "pad=160:120:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30",
                "-af", "aresample=async=1:first_pts=0",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ar", "48000", "-ac", "2", "-f", "mpegts",
                "/tmp/work/segment-0.ts");
        assertThat(commands.get(1).arguments()).contains("-f", "lavfi", "-i",
                "anullsrc=r=48000:cl=stereo", "-shortest", "/tmp/work/segment-1.ts");
        assertThat(commands.getLast().arguments()).containsExactly(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "concat", "-safe", "0", "-i", "/tmp/work/list.txt",
                "-c", "copy", "-bsf:a", "aac_adtstoasc", "/tmp/out.mp4");
    }

    @Test
    void composeBuildsOneFilterGraphWithLazyInputs() {
        ComposePlan plan = new ComposePlan(320, 240, 30, "2", List.of(
                        new ComposeLayer("video", "", null, "0", "2", "0", "2",
                                null, null, null, 1D, true),
                        new ComposeLayer("text", null, "Hi:a,b", null, null, "0.5", "1.5",
                                10D, 20D, 1D, 0.8D, false)),
                List.of(new ComposeAudio("", "0", "2", "0", 1D)), true);
        List<FFmpegCommandBuilder.InputRef> refs = List.of(
                new FFmpegCommandBuilder.InputRef(0, IN, true));
        Map<String, FFmpegCommandBuilder.InputRef> byId = Map.of(
                "__primary__", refs.getFirst(), "primary", refs.getFirst());

        List<String> args = BUILDER.buildCompose(plan, metadata(true), refs, byId, 160, 120, OUT).arguments();
        assertThat(args).startsWith("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", "/tmp/in.mp4", "-filter_complex");
        String graph = args.get(args.indexOf("-filter_complex") + 1);
        assertThat(graph).contains("color=c=black:s=320x240:r=30:d=2[bg]")
                .contains("[0:v]trim=start=0:end=2,setpts=PTS-STARTPTS")
                .contains("scale=320:240:force_original_aspect_ratio=decrease")
                .contains("overlay=x=0:y=0:eof_action=pass:enable='between(t,0,2)'")
                .contains("drawtext=text='Hi\\:a\\,b':x=10:y=20:fontsize=36")
                .contains("fontcolor=white@0.8:enable='between(t,0.5,1.5)'")
                .contains("[vout]", "[0:a]atrim=start=0:end=2", "adelay=0:all=1[a0]",
                        "alimiter=limit=0.95[aout]");
        assertThat(args).endsWith("-t", "2", "-c:v", "libx264", "-preset", "ultrafast",
                "-pix_fmt", "yuv420p", "-r", "30", "-c:a", "aac", "-ar", "48000", "-ac", "2",
                "/tmp/out.mp4");
    }

    @Test
    void rejectsMissingAudioSourceAndComposeWithoutDuration() {
        assertThatThrownBy(() -> BUILDER.buildPipeline(IN, OUT, metadata(true),
                List.of(op("add_audio", null, null, null, null)), Map.of()))
                .isInstanceOf(ProcessingException.class)
                .hasMessage("audio source video is not available");
        ComposePlan plan = new ComposePlan(null, null, null, null, List.of(), List.of(), false);
        assertThatThrownBy(() -> BUILDER.buildCompose(plan, new MediaMetadata(0, 0, 0, null, null,
                null, 0, 0, null, 0, null, 0), List.of(), Map.of(), 160, 120, OUT))
                .isInstanceOf(ProcessingException.class).hasMessage("the timeline has no duration");
    }

    private static CommandSpec build(Operation operation) {
        return BUILDER.buildPipeline(IN, OUT, metadata(true), List.of(operation), Map.of());
    }

    private static Operation op(String type, String start, String end, Double factor, Integer orientation) {
        return new Operation(type, start, end, factor, orientation, null, null,
                null, null, null, null, null);
    }

    private static MediaMetadata metadata(boolean audio) {
        return new MediaMetadata(10D, 160, 120, "mpeg4", audio ? "aac" : null, "yuv420p",
                10D, audio ? 48000 : 0, audio ? "stereo" : null, 0D, "mov,mp4", 1000L);
    }
}
