package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Reproduces the upload quota checks, including in-flight per-user reservations. */
@Component
public class QuotaManager {

    private static final Logger log = LoggerFactory.getLogger(QuotaManager.class);

    private final MediaStore store;
    private final MotionProperties properties;
    private final Map<String, Integer> activeUploads = new HashMap<>();

    public QuotaManager(MediaStore store, MotionProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public Runnable acquire(String userId, long incomingBytes) {
        if (userId == null || userId.isBlank()) {
            throw exceeded("authentication required");
        }
        synchronized (activeUploads) {
            int active = activeUploads.getOrDefault(userId, 0);
            if (properties.maxActiveUploadsPerUser() > 0
                    && active >= properties.maxActiveUploadsPerUser()) {
                throw exceeded("too many simultaneous uploads");
            }
            activeUploads.put(userId, active + 1);
        }

        Runnable release = () -> {
            synchronized (activeUploads) {
                int remaining = activeUploads.getOrDefault(userId, 0) - 1;
                if (remaining <= 0) {
                    activeUploads.remove(userId);
                } else {
                    activeUploads.put(userId, remaining);
                }
            }
        };

        try {
            checkStorage(userId, incomingBytes);
            return release;
        } catch (UploadClientException exception) {
            release.run();
            throw exception;
        } catch (RuntimeException exception) {
            release.run();
            throw new UploadInternalException("failed to check upload quota", exception);
        }
    }

    private void checkStorage(String userId, long incomingBytes) {
        if (properties.maxSourceFilesPerUser() > 0
                && store.countSourceFilesForUser(userId) >= properties.maxSourceFilesPerUser()) {
            throw exceeded("source file limit reached");
        }
        if (properties.maxOutputsPerUser() > 0
                && store.countCompletedOutputsForUser(userId) >= properties.maxOutputsPerUser()) {
            throw exceeded("output limit reached");
        }
        if (properties.maxStoredBytesPerUser() > 0
                && store.sumStoredBytesForUser(userId) + incomingBytes > properties.maxStoredBytesPerUser()) {
            throw exceeded("per-user storage limit reached");
        }
        if (properties.maxTotalStorageBytes() > 0
                && store.sumStoredBytesAll() + incomingBytes > properties.maxTotalStorageBytes()) {
            throw exceeded("server storage limit reached");
        }
        if (properties.minFreeDiskBytes() > 0) {
            try {
                FileStore fileStore = Files.getFileStore(properties.outputDir().toAbsolutePath());
                if (fileStore.getUsableSpace() < properties.minFreeDiskBytes() + incomingBytes) {
                    throw exceeded("server is low on disk space");
                }
            } catch (IOException exception) {
                // Go treats an unavailable disk guard as non-fatal availability knowledge.
                log.warn("checking free disk space: {}", exception.getMessage());
            }
        }
    }

    private static UploadClientException exceeded(String reason) {
        return new UploadClientException("quota exceeded: " + reason);
    }
}
