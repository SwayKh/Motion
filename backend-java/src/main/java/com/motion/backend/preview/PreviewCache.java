package com.motion.backend.preview;

import com.motion.backend.config.MotionProperties;
import com.motion.backend.processing.ProcessingException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * On-disk preview cache mirroring the {@code internal/preview.Manager}.
 *
 * <p>Only derived JPEG and JSON artifacts live here. Source media is never stored in this
 * directory, and generation for one cache key is serialized by an in-process lock.</p>
 */
@Component
public class PreviewCache {

    private final MotionProperties properties;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public PreviewCache(MotionProperties properties) {
        this.properties = properties;
    }

    public Path dir() {
        return properties.previewDir();
    }

    public Path stripPath(String videoId, int count, int height) {
        return resolve("strip_" + requireSafeId(videoId) + "_" + count + "_" + height + ".jpg");
    }

    public Path waveformPath(String videoId, int buckets) {
        return resolve("wave_" + requireSafeId(videoId) + "_" + buckets + ".json");
    }

    public void ensureDir() {
        try {
            Files.createDirectories(dir());
        } catch (IOException exception) {
            throw new ProcessingException("could not create preview directory", exception);
        }
    }

    /** Serialises generation for one cache key; callers must double-check the cache inside. */
    public <T> T withLock(String key, Supplier<T> work) {
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            return work.get();
        }
    }

    public Path createTempFile(String prefix, String suffix) {
        ensureDir();
        try {
            return Files.createTempFile(dir(), prefix, suffix);
        } catch (IOException exception) {
            throw new ProcessingException("could not create preview scratch file", exception);
        }
    }

    /** Atomically publishes a generated artifact, with a copy fallback for cross-device moves. */
    public void publish(Path temporary, Path destination) {
        ensureDir();
        try {
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ProcessingException("could not cache preview artifact", exception);
        }
    }

    /** Deletes every cached preview that belongs to a video, matching the {@code *_id_*}. */
    public void invalidate(String videoId) {
        String safeId = safeId(videoId);
        if (safeId == null || !Files.isDirectory(dir())) {
            return;
        }
        String marker = "_" + safeId + "_";
        try (Stream<Path> paths = Files.list(dir())) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return (name.startsWith("strip_") || name.startsWith("wave_"))
                                && name.contains(marker);
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Cache cleanup is best effort, like the RemoveForVideo.
                        }
                    });
        } catch (IOException ignored) {
            // Cache cleanup is best effort.
        }
    }

    private Path resolve(String filename) {
        Path root = dir().toAbsolutePath().normalize();
        Path path = root.resolve(filename).normalize();
        if (!path.startsWith(root)) {
            throw new ProcessingException("invalid preview cache path");
        }
        return path;
    }

    private static String requireSafeId(String videoId) {
        String safe = safeId(videoId);
        if (safe == null) {
            throw new ProcessingException("invalid video id");
        }
        return safe;
    }

    private static String safeId(String videoId) {
        if (videoId == null || videoId.isBlank() || videoId.contains("/")
                || videoId.contains("\\") || videoId.contains("..")) {
            return null;
        }
        return videoId;
    }
}
