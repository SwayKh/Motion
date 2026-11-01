package com.motion.backend.processing;

import com.motion.backend.media.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Removes only job-owned output/scratch artifacts; uploads and user media are never touched. */
@Component
public class JobArtifactCleaner {

    private static final Logger log = LoggerFactory.getLogger(JobArtifactCleaner.class);

    private final StorageService storage;

    public JobArtifactCleaner(StorageService storage) {
        this.storage = storage;
    }

    public void remove(JobRecord job) {
        if (job != null) {
            remove(job.id(), job.outputPath());
        }
    }

    public void remove(String jobId, String outputPath) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        Set<Path> paths = new LinkedHashSet<>();
        addOutput(paths, outputPath);
        for (String extension : new String[] {".mp4", ".mp3"}) {
            addOutput(paths, storage.outputDir().resolve(jobId + extension).toString());
            addOutput(paths, storage.outputDir().resolve("." + jobId + ".part" + extension).toString());
            addOutput(paths, storage.outputDir().resolve(jobId + ".part" + extension).toString());
        }
        for (Path path : paths) {
            try {
                storage.remove(path);
            } catch (RuntimeException exception) {
                log.warn("best-effort job artifact cleanup failed for {}: {}", path,
                        exception.getMessage());
            }
        }
    }

    private void addOutput(Set<Path> paths, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(raw).toAbsolutePath().normalize();
            if (storage.containsOutput(path)) {
                paths.add(path);
            } else {
                log.warn("skipping job artifact outside output directory: {}", path);
            }
        } catch (RuntimeException exception) {
            log.warn("skipping invalid job artifact path: {}", exception.getMessage());
        }
    }
}
