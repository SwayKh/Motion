package com.motion.backend.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.auth.ApiException;
import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaService;
import com.motion.backend.media.StorageService;
import com.motion.backend.processing.CommandSpec;
import com.motion.backend.processing.FFmpegExecutor;
import com.motion.backend.processing.PrivateWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Generates and caches application filmstrips and waveform peaks for owned videos. */
@Component
public class PreviewService {

    public static final int DEFAULT_THUMBNAIL_HEIGHT = 72;
    public static final int MIN_THUMBNAIL_COUNT = 12;
    public static final int MAX_THUMBNAIL_COUNT = 48;
    public static final int MIN_WAVEFORM_BUCKETS = 200;
    public static final int MAX_WAVEFORM_BUCKETS = 4000;

    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);

    private final MediaService mediaService;
    private final StorageService storage;
    private final FFmpegExecutor ffmpeg;
    private final WaveformGenerator waveformGenerator;
    private final PreviewCache cache;
    private final ObjectMapper mapper;

    public PreviewService(
            MediaService mediaService,
            StorageService storage,
            FFmpegExecutor ffmpeg,
            WaveformGenerator waveformGenerator,
            PreviewCache cache,
            ObjectMapper mapper) {
        this.mediaService = mediaService;
        this.storage = storage;
        this.ffmpeg = ffmpeg;
        this.waveformGenerator = waveformGenerator;
        this.cache = cache;
        this.mapper = mapper;
    }

    /** Filmstrip dimensions plus the source duration needed by the metadata response. */
    public record ThumbnailPreview(ThumbnailStrip strip, double duration) {
    }

    public ThumbnailPreview ensureThumbnailStrip(String userId, String videoId, Integer requestedCount) {
        MediaRecord video = requirePreviewable(userId, videoId);
        int count = requestedCount == null
                ? defaultThumbnailCount(video.duration()) : clampThumbnailCount(requestedCount);
        Path cachePath = cache.stripPath(video.id(), count, DEFAULT_THUMBNAIL_HEIGHT);
        ThumbnailStrip cached = probeStrip(cachePath, count);
        if (cached != null) {
            return new ThumbnailPreview(cached, video.duration());
        }

        return cache.withLock("strip:" + video.id() + ":" + count, () -> {
            ThumbnailStrip raced = probeStrip(cachePath, count);
            if (raced != null) {
                return new ThumbnailPreview(raced, video.duration());
            }
            Path temporary = null;
            try {
                temporary = cache.createTempFile("strip-", ".jpg");
                try (PrivateWorkspace workspace = PrivateWorkspace.create(storage)) {
                    Path source = materialize(workspace, video);
                    String filter = "fps=" + String.format(Locale.ROOT, "%.6f", thumbnailRate(video.duration(), count))
                            + ",scale=-2:" + DEFAULT_THUMBNAIL_HEIGHT
                            + ",tile=" + count + "x1";
                    List<String> arguments = List.of(
                            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                            "-i", source.toString(),
                            "-an", "-vf", filter,
                            "-frames:v", "1", "-q:v", "5",
                            temporary.toString());
                    ffmpeg.execute(new CommandSpec(arguments, false, 0));

                    JpegDimensions.Size size = JpegDimensions.read(temporary);
                    int tileWidth = size.width() / count;
                    if (tileWidth <= 0) {
                        tileWidth = size.width();
                    }
                    ThumbnailStrip strip = new ThumbnailStrip(
                            count, tileWidth, size.height(), size.width(), size.height());
                    cache.publish(temporary, cachePath);
                    temporary = null;
                    return new ThumbnailPreview(strip, video.duration());
                }
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                log.error("generating thumbnails video={}", video.id(), exception);
                throw new ApiException(422, "could not generate thumbnails for this video");
            } finally {
                deleteQuietly(temporary);
            }
        });
    }

    public WaveformResponse ensureWaveform(String userId, String videoId, Integer requestedBuckets) {
        MediaRecord video = requirePreviewable(userId, videoId);
        int buckets = requestedBuckets == null
                ? defaultWaveformBuckets(video.duration()) : clampWaveformBuckets(requestedBuckets);
        Path cachePath = cache.waveformPath(video.id(), buckets);
        WaveformResponse cached = readWaveformCache(cachePath);
        if (cached != null) {
            return cached;
        }

        return cache.withLock("wave:" + video.id() + ":" + buckets, () -> {
            WaveformResponse raced = readWaveformCache(cachePath);
            if (raced != null) {
                return raced;
            }
            Path temporary = null;
            try {
                temporary = cache.createTempFile("wave-", ".json");
                try (PrivateWorkspace workspace = PrivateWorkspace.create(storage)) {
                    Path source = materialize(workspace, video);
                    WaveformGenerator.WaveformData data = waveformGenerator.generate(source, buckets);
                    WaveformResponse result = new WaveformResponse(
                            buckets, video.duration(), data.audio(), data.peaks());
                    Files.writeString(temporary, mapper.writeValueAsString(result));
                    cache.publish(temporary, cachePath);
                    temporary = null;
                    return result;
                }
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                log.error("generating waveform video={}", video.id(), exception);
                throw new ApiException(422, "could not analyse the audio for this video");
            } finally {
                deleteQuietly(temporary);
            }
        });
    }

    public Path spritePath(String videoId, ThumbnailStrip strip) {
        return cache.stripPath(videoId, strip.count(), DEFAULT_THUMBNAIL_HEIGHT);
    }

    public static int defaultThumbnailCount(double duration) {
        if (!Double.isFinite(duration) || duration <= 0) {
            return MIN_THUMBNAIL_COUNT;
        }
        long rounded = Math.round(duration);
        return clampThumbnailCount((int) Math.max(MIN_THUMBNAIL_COUNT,
                Math.min(MAX_THUMBNAIL_COUNT, rounded)));
    }

    public static int clampThumbnailCount(int count) {
        return Math.max(MIN_THUMBNAIL_COUNT, Math.min(MAX_THUMBNAIL_COUNT, count));
    }

    public static int defaultWaveformBuckets(double duration) {
        if (!Double.isFinite(duration) || duration <= 0) {
            return MIN_WAVEFORM_BUCKETS;
        }
        long rounded = Math.round(duration * 8D);
        return clampWaveformBuckets((int) Math.max(MIN_WAVEFORM_BUCKETS,
                Math.min(MAX_WAVEFORM_BUCKETS, rounded)));
    }

    public static int clampWaveformBuckets(int buckets) {
        return Math.max(MIN_WAVEFORM_BUCKETS, Math.min(MAX_WAVEFORM_BUCKETS, buckets));
    }

    private MediaRecord requirePreviewable(String userId, String videoId) {
        MediaRecord video = mediaService.requireOwned(userId, videoId);
        if (video.originalPath() == null || video.originalPath().isBlank()) {
            throw new ApiException(404, "video is not available");
        }
        return video;
    }

    private static Path materialize(PrivateWorkspace workspace, MediaRecord video) {
        Path source = workspace.materialize(video, "source");
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("source file is no longer available");
        }
        return source;
    }

    private static double thumbnailRate(double duration, int count) {
        if (!Double.isFinite(duration) || duration <= 0) {
            return 1D;
        }
        double rate = (double) count / duration;
        return rate > 0 ? rate : 1D;
    }

    private ThumbnailStrip probeStrip(Path path, int count) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            if (Files.size(path) == 0) {
                return null;
            }
            JpegDimensions.Size size = JpegDimensions.read(path);
            int tileWidth = size.width() / count;
            if (tileWidth <= 0) {
                return null;
            }
            return new ThumbnailStrip(
                    count, tileWidth, size.height(), size.width(), size.height());
        } catch (RuntimeException | IOException exception) {
            return null;
        }
    }

    private WaveformResponse readWaveformCache(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readAllBytes(path), WaveformResponse.class);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; generated previews contain no plaintext media.
        }
    }
}
