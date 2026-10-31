package com.motion.backend.preview;

import com.motion.backend.processing.CommandSpec;
import com.motion.backend.processing.FFmpegExecutor;
import com.motion.backend.processing.ProcessingException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Decodes mono PCM and reduces it to normalised peak buckets, matching the waveform extractor. */
@Component
public class WaveformGenerator {

    private static final int SAMPLE_RATE = 8000;

    private final FFmpegExecutor executor;

    public WaveformGenerator(FFmpegExecutor executor) {
        this.executor = executor;
    }

    public WaveformData generate(Path source, int buckets) {
        List<String> arguments = List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", source.toString(),
                "-vn", "-ac", "1", "-ar", String.valueOf(SAMPLE_RATE),
                "-f", "s16le", "-");
        FFmpegExecutor.CapturedOutput output =
                executor.capture(new CommandSpec(arguments, false, 0));
        if (output.exitCode() != 0) {
            if (looksLikeNoAudio(output.stderr())) {
                return WaveformData.noAudio();
            }
            throw new ProcessingException("could not analyse the audio for this video");
        }

        byte[] pcm = output.stdout();
        int samples = pcm.length / 2;
        if (samples == 0) {
            return WaveformData.noAudio();
        }

        double[] peaks = new double[buckets];
        double per = (double) samples / buckets;
        for (int i = 0; i < buckets; i++) {
            int start = (int) (i * per);
            int end = (int) ((i + 1) * per);
            if (end > samples) {
                end = samples;
            }
            if (end <= start) {
                end = Math.min(samples, start + 1);
            }
            int peak = 0;
            for (int j = start; j < end; j++) {
                int unsigned = pcm[j * 2] & 0xFF | ((pcm[j * 2 + 1] & 0xFF) << 8);
                int signed = unsigned >= 0x8000 ? unsigned - 0x10000 : unsigned;
                int magnitude = signed == Short.MIN_VALUE ? 0x8000 : Math.abs(signed);
                peak = Math.max(peak, magnitude);
            }
            peaks[i] = peak / 32768D;
        }
        return new WaveformData(true, peaks);
    }

    private static boolean looksLikeNoAudio(String stderr) {
        String lower = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
        return lower.contains("does not contain any stream")
                || lower.contains("matches no streams")
                || lower.contains("no such stream")
                || lower.contains("stream map")
                || lower.contains("could not find codec parameters for stream");
    }

    public record WaveformData(boolean audio, double[] peaks) {

        public static WaveformData noAudio() {
            return new WaveformData(false, new double[0]);
        }
    }
}
