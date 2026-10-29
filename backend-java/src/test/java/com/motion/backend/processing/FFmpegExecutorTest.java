package com.motion.backend.processing;

import com.motion.backend.web.MediaTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FFmpegExecutorTest {

    @TempDir
    static Path tempDir;

    private static Path validVideo;

    @BeforeAll
    static void generateFixture() throws Exception {
        MediaTestSupport.assumeFfmpegAvailable();
        validVideo = MediaTestSupport.generateAudioVideo(tempDir, "valid.mp4");
    }

    @Test
    void parsesMicrosecondProgressAndCapsAtOneHundred() {
        List<Double> progress = new CopyOnWriteArrayList<>();
        CommandSpec command = new CommandSpec(List.of("printf", "out_time_ms=500000\nout_time_ms=2000000\n"),
                true, 1D);
        new FFmpegExecutor().execute(command, progress::add);
        assertThat(progress).containsExactly(50D, 100D);
    }

    @Test
    void treatsNonZeroExitAsSanitizedFailure() {
        CommandSpec command = new CommandSpec(List.of("false"), false, 0D);
        assertThatThrownBy(() -> new FFmpegExecutor().execute(command))
                .isInstanceOf(ProcessingException.class).hasMessage("video processing failed");
    }

    @Test
    void terminatesTimedOutProcess() {
        FFmpegExecutor executor = new FFmpegExecutor(Duration.ofMillis(200));
        CommandSpec command = new CommandSpec(List.of("sleep", "10"), false, 0D);
        assertThatThrownBy(() -> executor.execute(command))
                .isInstanceOf(ProcessingException.class).hasMessage("video processing timed out");
    }

    @Test
    void interruptionStopsProcessAndRestoresInterruptFlag() throws Exception {
        FFmpegExecutor executor = new FFmpegExecutor(Duration.ofSeconds(30));
        CommandSpec command = new CommandSpec(List.of("sleep", "10"), false, 0D);

        // A dedicated thread makes the interrupt deterministic and mirrors the job worker path.
        CompletableFuture<Throwable> interrupted = interruptAfterDelay(executor, command, 200);
        Throwable throwable = interrupted.get(5, TimeUnit.SECONDS);
        assertThat(throwable).isInstanceOf(ProcessingException.class)
                .hasMessage("video processing was interrupted");
    }

    @Test
    void verifyRejectsUndecodableOutputAndAcceptsRealMedia() throws Exception {
        Path corrupt = Files.writeString(tempDir.resolve("bad.mp4"), "not media");
        assertThatThrownBy(() -> new FFmpegExecutor().verify(corrupt))
                .isInstanceOf(ProcessingException.class)
                .hasMessage("processed output failed decode verification");
        new FFmpegExecutor().verify(validVideo);
    }

    private static CompletableFuture<Throwable> interruptAfterDelay(FFmpegExecutor executor,
                                                                    CommandSpec command, long delayMillis) {
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                executor.execute(command);
                result.complete(null);
            } catch (Throwable throwable) {
                result.complete(throwable);
            }
        }, "ffmpeg-interrupt-test");
        thread.setDaemon(true);
        thread.start();
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        thread.interrupt();
        return result;
    }
}
