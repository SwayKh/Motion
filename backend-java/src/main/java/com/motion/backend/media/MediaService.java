package com.motion.backend.media;

import com.motion.backend.auth.ApiException;
import com.motion.backend.preview.PreviewCache;
import com.motion.backend.time.LegacyTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Media business rules shared by all in-scope controllers. */
@Component
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /** Owns an opened plaintext byte source; {@link MediaFileServer} closes it after streaming. */
    public record ServableFile(String name, long size, MediaSource source) {
    }

    private final MediaStore store;
    private final StorageService storage;
    private final PreviewCache previewCache;

    public MediaService(MediaStore store, StorageService storage, PreviewCache previewCache) {
        this.store = store;
        this.storage = storage;
        this.previewCache = previewCache;
    }

    public List<VideoResponse> list(String userId) {
        return store.listByUser(userId).stream().map(MediaService::toResponse).toList();
    }

    public VideoResponse metadata(String userId, String id) {
        return toResponse(requireOwned(userId, id));
    }

    public MediaRecord requireOwned(String userId, String id) {
        MediaRecord video = store.findById(id)
                .orElseThrow(() -> new ApiException(404, "video not found"));
        if (video.userId() == null || !video.userId().equals(userId)) {
            throw new ApiException(404, "video not found");
        }
        return video;
    }

    public ServableFile original(String userId, String id) {
        MediaRecord video = requireOwned(userId, id);
        if (video.originalPath() == null || video.originalPath().isBlank()) {
            throw new ApiException(404, "original file is not available");
        }
        String name = video.originalName() == null || video.originalName().isBlank()
                ? baseName(video.originalPath())
                : video.originalName();
        return servable(video.originalPath(), name, video.encrypted(), video.encKey());
    }

    public ServableFile output(String userId, String id) {
        MediaRecord video = requireOwned(userId, id);
        if (!"completed".equals(video.status())
                || video.outputPath() == null || video.outputPath().isBlank()) {
            throw new ApiException(404, "processed output is not available");
        }
        return servable(video.outputPath(), outputFilename(video), video.outputEncrypted(),
                video.outputEncKey());
    }

    @Transactional
    public void delete(String userId, String id) {
        MediaRecord video = requireOwned(userId, id);
        if (store.hasActiveJobs(id)) {
            throw new ApiException(409, "cancel the active job before deleting this video");
        }

        // Go removes artifacts first and ignores file failures; missing files are not an error.
        for (String path : store.listJobOutputPaths(id)) {
            storage.removeQuietly(path);
        }
        storage.removeQuietly(video.originalPath());
        storage.removeQuietly(video.outputPath());

        try {
            store.deleteJobsForVideo(id);
            store.delete(id);
        } catch (RuntimeException exception) {
            log.error("deleting video rows for {}", id, exception);
            throw new ApiException(500, "failed to delete video");
        }
        previewCache.invalidate(id);
    }

    /** Opens a stored job artifact with the same containment and generic errors as media outputs. */
    public ServableFile storedFile(String path, String name, boolean encrypted, String wrappedKey) {
        return servable(path, name, encrypted, wrappedKey);
    }

    private ServableFile servable(String path, String name, boolean encrypted, String wrappedKey) {
        Path resolved = Path.of(path);
        if (!storage.contains(resolved) || !Files.isRegularFile(resolved)) {
            throw new ApiException(404, "file is no longer available");
        }
        String safeName = baseName(name == null || name.isBlank() ? path : name);
        try {
            MediaSource source = encrypted
                    ? storage.crypto().openEncrypted(resolved, wrappedKey)
                    : FileMediaSource.open(resolved);
            return new ServableFile(safeName, source.size(), source);
        } catch (NoSuchFileException exception) {
            throw new ApiException(404, "file is no longer available");
        } catch (IOException | RuntimeException exception) {
            // Never leak key or plaintext details through the API.
            log.error("opening stored media for {}", path, exception);
            throw new ApiException(500, "failed to read file");
        }
    }

    private static String outputFilename(MediaRecord video) {
        String original = video.originalName() == null ? "" : video.originalName();
        String base = stripExtension(original);
        if (base.isBlank() || ".".equals(base)) {
            base = video.id();
        }
        String outputExtension = extension(video.outputPath() == null ? "" : video.outputPath());
        return base + "-edited" + outputExtension;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot);
    }

    private static String baseName(String path) {
        if (path == null || path.isBlank()) {
            return "media";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isBlank() ? "media" : name;
    }

    private static VideoResponse toResponse(MediaRecord video) {
        double progress = video.progress();
        if (progress < 0) {
            progress = 0;
        } else if (progress > 100) {
            progress = 100;
        }

        String outputType = "";
        if (video.outputPath() != null && !video.outputPath().isBlank()) {
            outputType = extension(video.outputPath()).toLowerCase(Locale.ROOT).equals(".mp3")
                    ? "audio" : "video";
        }

        String name = video.originalName() == null || video.originalName().isBlank()
                ? baseName(video.originalPath())
                : video.originalName();

        return new VideoResponse(
                video.id(),
                name,
                video.status(),
                goDecimal(progress),
                goDecimal(video.duration()),
                video.width(),
                video.height(),
                video.fileSize(),
                video.error(),
                formatCreatedAt(video.createdAt()),
                video.originalPath() != null && !video.originalPath().isBlank(),
                "completed".equals(video.status())
                        && video.outputPath() != null && !video.outputPath().isBlank(),
                outputType);
    }

    /** Matches {@code encoding/json}'s shortest decimal form for float64 values. */
    private static BigDecimal goDecimal(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return BigDecimal.ZERO;
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        return decimal.scale() < 0 ? decimal.setScale(0) : decimal;
    }

    private static String formatCreatedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.EPOCH.toString();
        }
        try {
            return LegacyTimestamp.parse(raw).toString();
        } catch (RuntimeException ignored) {
            return raw;
        }
    }
}
