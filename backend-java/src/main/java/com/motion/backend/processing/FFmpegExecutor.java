package com.motion.backend.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;

/** Executes argv-only FFmpeg commands with drained pipes, timeouts, and interruption cleanup. */
@Component
public class FFmpegExecutor {

    private static final Logger log = LoggerFactory.getLogger(FFmpegExecutor.class);
    private static final int MAX_STDERR_CHARS = 64 * 1024;
    private static final Duration DESTROY_GRACE = Duration.ofSeconds(2);

    private final Duration timeout;

    public FFmpegExecutor() {
        this(Duration.ofHours(2));
    }

    /** Spring wiring: the wall-clock limit is configurable via {@code motion.jobs.ffmpeg-timeout}. */
    @org.springframework.beans.factory.annotation.Autowired
    public FFmpegExecutor(com.motion.backend.config.MotionProperties properties) {
        this(properties.ffmpegTimeout());
    }

    public FFmpegExecutor(Duration timeout) {
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofHours(2) : timeout;
    }

    public void execute(CommandSpec command) {
        execute(command, value -> { });
    }

    /**
     * Runs a command and returns its raw stdout. Used by preview extraction, where the useful
     * result is bytes on stdout rather than an output file; exit status is returned instead of
     * thrown so callers can classify expected no-audio results from FFmpeg's stderr.
     */
    public CapturedOutput capture(CommandSpec command) {
        ProcessBuilder builder = new ProcessBuilder(command.arguments());
        builder.redirectErrorStream(false);
        ExecutorService drains = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ffmpeg-drain");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        StringBuilder stderr = new StringBuilder();
        try {
            Process started = builder.start();
            process = started;
            Future<?> stderrFuture = drains.submit(() -> drainStderr(started.getErrorStream(), stderr));
            byte[] stdout;
            try (InputStream input = started.getInputStream()) {
                stdout = input.readAllBytes();
            }
            boolean finished;
            try {
                finished = started.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                terminate(started);
                Thread.currentThread().interrupt();
                throw new ProcessingException("video processing was interrupted", exception);
            }
            if (!finished) {
                terminate(started);
                throw new ProcessingException("video processing timed out");
            }
            waitForDrain(stderrFuture);
            return new CapturedOutput(stdout, stderr.toString(), started.exitValue());
        } catch (IOException exception) {
            throw new ProcessingException("ffmpeg could not be started", exception);
        } finally {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            drains.shutdownNow();
        }
    }

    /** Stdout bytes, drained stderr, and the process exit code from {@link #capture(CommandSpec)}. */
    public record CapturedOutput(byte[] stdout, String stderr, int exitCode) {
    }

    /** Decodes the complete output with FFmpeg so corrupt or truncated files never become jobs. */
    public void verify(Path output) {
        verify(output, ProcessListener.NOOP);
    }

    /** Decode verification that remains cancellable while the verifier process is running. */
    public void verify(Path output, ProcessListener listener) {
        CommandSpec verify = new CommandSpec(List.of(
                "ffmpeg", "-v", "error", "-i", output.toString(), "-f", "null", "-"), false, 0);
        try {
            execute(verify, value -> { }, listener);
        } catch (ProcessingException exception) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new ProcessingException("video processing was interrupted", exception);
            }
            throw new ProcessingException("processed output failed decode verification", exception);
        }
    }

    public void execute(CommandSpec command, DoubleConsumer onProgress) {
        execute(command, onProgress, ProcessListener.NOOP);
    }

    public void execute(CommandSpec command, DoubleConsumer onProgress, ProcessListener listener) {
        ProcessBuilder builder = new ProcessBuilder(command.arguments());
        builder.redirectErrorStream(false);
        ExecutorService drains = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ffmpeg-drain");
            thread.setDaemon(true);
            return thread;
        });
        Process process = null;
        StringBuilder stderr = new StringBuilder();
        try {
            Process started = builder.start();
            process = started;
            listener.started(started);
            Future<?> stdoutFuture = drains.submit(() -> drainStdout(
                    started.getInputStream(), command, onProgress));
            Future<?> stderrFuture = drains.submit(() -> drainStderr(started.getErrorStream(), stderr));

            boolean finished;
            try {
                finished = started.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                terminate(started);
                Thread.currentThread().interrupt();
                throw new ProcessingException("video processing was interrupted", exception);
            }
            if (!finished) {
                terminate(started);
                throw new ProcessingException("video processing timed out");
            }

            waitForDrain(stdoutFuture);
            waitForDrain(stderrFuture);
            int exit = started.exitValue();
            if (exit != 0) {
                String detail = sanitizedDetail(stderr);
                log.warn("ffmpeg exited with status {} for command {}: {}", exit,
                        command.arguments().getFirst(), detail);
                throw new ProcessingException("video processing failed");
            }
        } catch (IOException exception) {
            throw new ProcessingException("ffmpeg could not be started", exception);
        } finally {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            listener.finished(process);
            drains.shutdownNow();
        }
    }

    private static void drainStdout(InputStream input, CommandSpec command, DoubleConsumer onProgress) {
        if (!command.progressEnabled() || command.durationSeconds() <= 0 || onProgress == null) {
            drainDiscard(input);
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("out_time_ms=")) {
                    continue;
                }
                try {
                    double micros = Double.parseDouble(line.substring("out_time_ms=".length()).trim());
                    double percent = (micros / 1_000_000D / command.durationSeconds()) * 100D;
                    if (!Double.isFinite(percent)) {
                        continue;
                    }
                    onProgress.accept(Math.max(0D, Math.min(100D, percent)));
                } catch (NumberFormatException ignored) {
                    // Progress lines are advisory; malformed values are skipped.
                }
            }
        } catch (IOException ignored) {
            // The process exit code is authoritative.
        }
    }

    private static void drainStderr(InputStream input, StringBuilder destination) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (destination) {
                    if (destination.length() + line.length() + 1 > MAX_STDERR_CHARS) {
                        int keep = Math.max(0, MAX_STDERR_CHARS - line.length() - 1);
                        if (destination.length() > keep) {
                            destination.delete(0, destination.length() - keep);
                        }
                    }
                    destination.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // The process exit code is authoritative.
        }
    }

    private static void drainDiscard(InputStream input) {
        try (input) {
            byte[] buffer = new byte[16 * 1024];
            while (input.read(buffer) != -1) {
                // Drain only.
            }
        } catch (IOException ignored) {
            // The process exit code is authoritative.
        }
    }

    private static void waitForDrain(Future<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            future.cancel(true);
        }
    }

    public static void terminate(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroy();
        try {
            if (!process.waitFor(DESTROY_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.descendants().forEach(handle -> handle.destroyForcibly());
            }
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String sanitizedDetail(StringBuilder stderr) {
        synchronized (stderr) {
            String text = stderr.toString().replaceAll("\\u001B\\[[;\\d]*m", "");
            if (text.length() > 2000) {
                text = text.substring(text.length() - 2000);
            }
            return text.trim().toLowerCase(Locale.ROOT).replace('\n', ' ');
        }
    }
}
