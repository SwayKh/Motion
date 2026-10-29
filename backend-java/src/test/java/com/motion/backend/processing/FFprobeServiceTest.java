package com.motion.backend.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.web.MediaTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FFprobeServiceTest {

    private static final FFprobeService PROBE = new FFprobeService(new ObjectMapper());

    @TempDir
    static Path tempDir;

    private static Path audioVideo;
    private static Path silentVideo;

    @BeforeAll
    static void generateFixtures() throws Exception {
        MediaTestSupport.assumeFfmpegAvailable();
        audioVideo = MediaTestSupport.generateAudioVideo(tempDir, "audio.mp4");
        silentVideo = MediaTestSupport.generateVideo(tempDir, "silent.mp4");
    }

    @Test
    void parsesProbeMetadataSafely() {
        MediaMetadata metadata = PROBE.probe(audioVideo);
        assertThat(metadata.videoCodec()).isNotBlank();
        assertThat(metadata.audioCodec()).isEqualTo("aac");
        assertThat(metadata.width()).isEqualTo(160);
        assertThat(metadata.height()).isEqualTo(120);
        assertThat(metadata.duration()).isGreaterThan(1.5D).isLessThan(3D);
        assertThat(metadata.frameRate()).isPositive();
        assertThat(metadata.sampleRate()).isEqualTo(48000);
        assertThat(metadata.channelLayout()).isNotBlank();
        assertThat(metadata.size()).isPositive();
        assertThat(metadata.rotation()).isZero();
        assertThat(metadata.hasVideo()).isTrue();
        assertThat(metadata.hasAudio()).isTrue();

        MediaMetadata silent = PROBE.probe(silentVideo);
        assertThat(silent.hasVideo()).isTrue();
        assertThat(silent.hasAudio()).isFalse();
        assertThat(silent.sampleRate()).isZero();
    }

    @Test
    void rejectsCorruptInputWithSanitizedError() throws Exception {
        Path corrupt = Files.writeString(tempDir.resolve("corrupt.mp4"), "not media");
        assertThatThrownBy(() -> PROBE.probe(corrupt))
                .isInstanceOf(ProcessingException.class)
                .hasMessageMatching("ffprobe exited with status \\d+");
    }
}
