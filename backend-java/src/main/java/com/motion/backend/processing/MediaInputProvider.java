package com.motion.backend.processing;

import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaStore;
import com.motion.backend.media.StorageService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Resolves source videos into private, FFmpeg-readable inputs.
 *
 * <p>This is the only processing component that turns media records into local files. Encrypted
 * records are decrypted by {@link PrivateWorkspace}; all plaintext is confined to that workspace and
 * removed when the run ends. Metadata is probed here so callers cannot accidentally probe or reuse a
 * stored ciphertext path.</p>
 */
@Component
public class MediaInputProvider {

    private static final long STALE_SCRATCH_AGE_MILLIS = 24L * 60L * 60L * 1000L;

    private final MediaStore mediaStore;
    private final StorageService storage;
    private final FFprobeService ffprobe;

    public MediaInputProvider(MediaStore mediaStore, StorageService storage, FFprobeService ffprobe) {
        this.mediaStore = mediaStore;
        this.storage = storage;
        this.ffprobe = ffprobe;
    }

    /** The configured storage manager, used to create a private workspace for each run. */
    public StorageService storage() {
        return storage;
    }

    /** Removes long-abandoned pre-encryption workspaces from earlier JVM runs, if any. */
    @PostConstruct
    public void sweepStaleScratch() {
        sweepScratch(STALE_SCRATCH_AGE_MILLIS);
    }

    /**
     * Removes every private workspace left by an earlier JVM attempt. Called before workers start,
     * when no workspace in this JVM can be active, so abrupt restarts cannot leak plaintext.
     */
    public void sweepAbandonedWorkspaces() {
        sweepScratch(0L);
    }

    private void sweepScratch(long minimumAgeMillis) {
        Path tempRoot;
        try {
            tempRoot = storage.tempDir();
        } catch (RuntimeException ignored) {
            return;
        }
        if (tempRoot == null || !Files.isDirectory(tempRoot)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - minimumAgeMillis;
        try (Stream<Path> children = Files.list(tempRoot)) {
            children.filter(Files::isDirectory)
                    .filter(path -> {
                        String name = path.getFileName() == null ? "" : path.getFileName().toString();
                        return name.startsWith("processing-") || name.startsWith("concat-");
                    })
                    .filter(path -> lastModified(path) <= cutoff)
                    .forEach(MediaInputProvider::deleteRecursively);
        } catch (IOException ignored) {
            // Best-effort sweep; a live workspace is removed by its own processing run.
        }
    }

    /** Resolves and probes the primary video of a job. */
    public ResolvedInput primary(PrivateWorkspace workspace, MediaRecord video) {
        Path path = materialize(workspace, video, "source", "source file is no longer available");
        MediaMetadata metadata = probe(path, "unable to inspect the source video");
        return new ResolvedInput(video, path, metadata);
    }

    /** Resolves a referenced video, requiring an audio stream when requested. */
    public ResolvedInput referenced(PrivateWorkspace workspace, String videoId, String role,
                                    String missingMessage, String probeMessage, boolean requireAudio) {
        MediaRecord record = mediaStore.findById(videoId)
                .orElseThrow(() -> new ProcessingException(missingMessage));
        Path path = materialize(workspace, record, role, missingMessage);
        MediaMetadata metadata = probe(path, probeMessage);
        if (requireAudio && !metadata.hasAudio()) {
            throw new ProcessingException("the selected audio source has no audio track");
        }
        return new ResolvedInput(record, path, metadata);
    }

    /**
     * Resolves a concat clip whose Go counterpart treats an unreadable source as "no audio" rather
     * than failing the whole job.
     */
    public ResolvedInput concatClip(PrivateWorkspace workspace, String videoId, int index) {
        String message = "clip " + index + " is not available";
        MediaRecord record = mediaStore.findById(videoId)
                .orElseThrow(() -> new ProcessingException(message));
        Path path = materialize(workspace, record, "clip-" + index, message);
        MediaMetadata metadata;
        try {
            metadata = ffprobe.probe(path);
        } catch (ProcessingException exception) {
            metadata = null;
        }
        return new ResolvedInput(record, path, metadata);
    }

    private Path materialize(PrivateWorkspace workspace, MediaRecord source,
                             String role, String failureMessage) {
        if (source.originalPath() == null || source.originalPath().isBlank()) {
            throw new ProcessingException(failureMessage);
        }
        try {
            Path path = workspace.materialize(source, role);
            if (!Files.isRegularFile(path)) {
                throw new ProcessingException(failureMessage);
            }
            return path;
        } catch (ProcessingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProcessingException(failureMessage, exception);
        }
    }

    private MediaMetadata probe(Path path, String failureMessage) {
        try {
            return ffprobe.probe(path);
        } catch (ProcessingException exception) {
            throw new ProcessingException(failureMessage, exception);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort stale cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort stale cleanup.
        }
    }

    public record ResolvedInput(MediaRecord record, Path path, MediaMetadata metadata) {
    }
}
