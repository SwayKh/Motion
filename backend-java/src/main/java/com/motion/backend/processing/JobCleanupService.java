package com.motion.backend.processing;

import com.motion.backend.config.MotionProperties;
import com.motion.backend.media.MediaRecord;
import com.motion.backend.media.MediaStore;
import com.motion.backend.media.StorageService;
import com.motion.backend.preview.PreviewCache;
import com.motion.backend.time.LegacyTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Retention sweep for terminal job history and orphaned job artifacts.
 *
 * <p>This cleanup never removes videos, originals, active jobs, or the output currently referenced
 * by a video. Eligibility is rechecked transactionally immediately before each row is deleted, and
 * files are removed only after the database deletion succeeds.</p>
 */
@Component
public class JobCleanupService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(JobCleanupService.class);
    private static final Duration MIN_INTERVAL = Duration.ofSeconds(10);

    private final MotionProperties properties;
    private final JobStore jobs;
    private final MediaStore mediaStore;
    private final JobArtifactCleaner cleaner;
    private final StorageService storage;
    private final PreviewCache previewCache;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService scheduler;

    public JobCleanupService(MotionProperties properties, JobStore jobs, MediaStore mediaStore,
                             JobArtifactCleaner cleaner, StorageService storage, PreviewCache previewCache) {
        this.properties = properties;
        this.jobs = jobs;
        this.mediaStore = mediaStore;
        this.cleaner = cleaner;
        this.storage = storage;
        this.previewCache = previewCache;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        sweepStaleScratch();
        sweepStaleOutputTemps();
        Duration interval = properties.cleanupInterval();
        if (properties.retentionDisabled() || interval.isZero() || interval.isNegative()) {
            log.info("job history cleanup disabled");
            return;
        }
        long seconds = Math.max(MIN_INTERVAL.toSeconds(), interval.toSeconds());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "motion-job-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                cleanupOnce();
            } catch (Throwable exception) {
                log.error("job cleanup sweep failed", exception);
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Removes only old private processing directories; active/new workspaces are never touched. */
    public void sweepStaleScratch() {
        if (properties.retentionDisabled()) {
            return;
        }
        Path root = storage.tempDir();
        if (!Files.isDirectory(root)) {
            return;
        }
        long cutoff = staleArtifactCutoff();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith("processing-") || !Files.isDirectory(path)
                        || !storage.containsTemp(path)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        deleteRecursively(path);
                        log.info("removed stale processing workspace {}", name);
                    }
                } catch (IOException exception) {
                    log.warn("stale scratch sweep skipped {}: {}", name, exception.getMessage());
                }
            }
        } catch (IOException exception) {
            log.warn("stale scratch sweep failed", exception);
        }
    }

    /**
     * Removes orphaned staged output temps left in the output directory by interrupted writes.
     *
     * <p>Active writers create {@code .output-*.bin} files only for the duration of a store; files
     * older than the retention window are treated as abandoned crash residue (F-16).</p>
     */
    public void sweepStaleOutputTemps() {
        if (properties.retentionDisabled()) {
            return;
        }
        Path root = storage.outputDir();
        if (!Files.isDirectory(root)) {
            return;
        }
        long cutoff = staleArtifactCutoff();
        try (Stream<Path> entries = Files.list(root)) {
            for (Path path : entries.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith(".output-") || !name.endsWith(".bin")
                        || !Files.isRegularFile(path) || !storage.containsOutput(path)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                        log.info("removed stale output temp {}", name);
                    }
                } catch (IOException exception) {
                    log.warn("stale output temp sweep skipped {}: {}", name, exception.getMessage());
                }
            }
        } catch (IOException exception) {
            log.warn("stale output temp sweep failed", exception);
        }
    }

    private long staleArtifactCutoff() {
        return Instant.now().minus(properties.retention()).toEpochMilli();
    }

    private static void deleteRecursively(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Performs one retention pass; public for deterministic tests. */
    public int cleanupOnce() {
        if (properties.retentionDisabled()) {
            return 0;
        }
        Duration retention = properties.retention();
        if (retention.isZero() || retention.isNegative()) {
            return 0;
        }
        sweepStaleOutputTemps();
        Instant cutoff = Instant.now().minus(retention);

        // application expired-video cleanup (24h retention of terminal videos with no blocking jobs).
        cleanupExpiredVideos(cutoff);

        // Job history retention for terminal rows whose outputs are no longer the current video output.
        Set<String> currentOutputs = new HashSet<>();
        for (MediaRecord video : mediaStore.listAllVideos()) {
            if (video.outputPath() != null && !video.outputPath().isBlank()) {
                currentOutputs.add(normalize(video.outputPath()));
            }
        }

        int removed = 0;
        List<JobRecord> candidates = jobs.listAll();
        for (JobRecord job : candidates) {
            if (!JobState.isTerminal(job.status()) || mediaStore.hasActiveJobs(job.videoId())) {
                continue;
            }
            if (job.outputPath() != null && !job.outputPath().isBlank()
                    && currentOutputs.contains(normalize(job.outputPath()))) {
                continue;
            }
            if (!olderThan(job, cutoff)) {
                continue;
            }
            if (jobs.deleteExpiredHistory(job.id())) {
                cleaner.remove(job.id(), job.outputPath());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("retention cleanup removed {} expired job rows", removed);
        }
        return removed;
    }

    /**
     * Deletes expired videos with application eligibility and removes their files only after the
     * database deletion commits. Errors are isolated per video so one failure does not block others.
     */
    public int cleanupExpiredVideos(Instant cutoff) {
        List<MediaStore.ExpiredVideoCandidate> candidates;
        try {
            candidates = mediaStore.listExpiredVideos(cutoff);
        } catch (RuntimeException exception) {
            log.error("expired video listing failed", exception);
            return 0;
        }

        int removed = 0;
        List<String> errors = new ArrayList<>();
        for (MediaStore.ExpiredVideoCandidate candidate : candidates) {
            try {
                Optional<MediaStore.ExpiredVideoTarget> deleted = mediaStore.deleteExpiredVideo(candidate.id(), cutoff);
                if (deleted.isEmpty()) {
                    log.info("cleanup skipped video={} because it is no longer eligible", candidate.id());
                    continue;
                }
                MediaStore.ExpiredVideoTarget target = deleted.get();
                log.info("cleanup deleting video={}", target.videoId());
                for (MediaStore.ExpiredVideoJob job : target.jobs()) {
                    try {
                        cleaner.remove(job.id(), job.outputPath());
                    } catch (RuntimeException exception) {
                        errors.add("job artifacts " + job.id() + ": " + exception.getMessage());
                    }
                }
                removeFileIfPresent(target.originalPath(), target.videoId(), "upload");
                removeFileIfPresent(target.outputPath(), target.videoId(), "output");
                try {
                    previewCache.invalidate(target.videoId());
                } catch (RuntimeException exception) {
                    log.warn("cleanup preview removal failed video={}: {}", target.videoId(), exception.getMessage());
                }
                removed++;
            } catch (RuntimeException exception) {
                log.error("cleanup failed for video={}", candidate.id(), exception);
                errors.add(candidate.id() + ": " + exception.getMessage());
            }
        }
        if (removed > 0) {
            log.info("expired video cleanup removed {} videos", removed);
        }
        if (!errors.isEmpty()) {
            log.warn("expired video cleanup completed with {} file/cleanup errors", errors.size());
        }
        return removed;
    }

    private static void removeFileIfPresent(String path, String videoId, String kind) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Path file = Path.of(path);
            Files.deleteIfExists(file);
        } catch (Exception exception) {
            log.warn("cleanup delete {} failed video={} path={}: {}", kind, videoId, path, exception.getMessage());
        }
    }

    private static boolean olderThan(JobRecord job, Instant cutoff) {
        String timestamp = job.finishedAt() == null || job.finishedAt().isBlank()
                ? job.createdAt() : job.finishedAt();
        try {
            return LegacyTimestamp.parse(timestamp).isBefore(cutoff);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String normalize(String path) {
        try {
            return java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
        } catch (RuntimeException exception) {
            return path;
        }
    }
}
