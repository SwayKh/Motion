package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import com.motion.backend.time.LegacyTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Upload orchestration: validate, stream, probe, store, persist, clean up. */
@Component
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".mpeg", ".mpg", ".3gp");

    private final MotionProperties properties;
    private final StorageService storage;
    private final MediaProbe probe;
    private final QuotaManager quotas;
    private final MediaStore store;

    public UploadService(MotionProperties properties, StorageService storage, MediaProbe probe,
                         QuotaManager quotas, MediaStore store) {
        this.properties = properties;
        this.storage = storage;
        this.probe = probe;
        this.quotas = quotas;
        this.store = store;
    }

    public MediaRecord upload(MultipartFile file, String userId) {
        String originalName = baseName(file.getOriginalFilename());
        String extension = extension(originalName);
        if (extension.isEmpty()) {
            throw new UploadClientException("file must have a file extension");
        }
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new UploadClientException("unsupported file type " + extension
                    + "; use MP4, MOV, MKV, WebM, AVI, M4V, MPEG, or 3GP");
        }

        long maxBytes = properties.maxUploadBytes();
        if (file.getSize() > maxBytes) {
            throw sizeError(maxBytes);
        }

        Runnable release = quotas.acquire(userId, file.getSize());
        Path tempFile = null;
        Path storedPath = null;
        boolean persisted = false;
        try {
            tempFile = storage.newTempFile("upload-", extension);
            long written = copyWithLimit(file, tempFile, maxBytes);
            if (written > maxBytes) {
                throw sizeError(maxBytes);
            }

            MediaProbe.Metadata metadata = probe.probe(tempFile);
            if (metadata.duration() > properties.maxDurationSeconds()) {
                throw new UploadClientException("video exceeds the "
                        + (properties.maxDurationSeconds() / 60) + " minute limit");
            }
            if (metadata.width() > 0
                    && (metadata.width() > properties.maxWidth() || metadata.height() > properties.maxHeight())) {
                throw new UploadClientException("video exceeds the "
                        + properties.maxHeight() + "p limit");
            }
            if (!metadata.hasVideo()) {
                throw new UploadClientException("file does not contain a video stream");
            }

            String id = UUID.randomUUID().toString();
            StoredUpload stored = storage.storeUpload(tempFile, id + extension);
            storedPath = stored.path();
            tempFile = null;

            MediaRecord record = new MediaRecord(
                    id, storedPath.toString(), originalName, written, null,
                    "uploaded", 0D, null, metadata.duration(), metadata.width(), metadata.height(),
                    null, LegacyTimestamp.format(Instant.now()), userId,
                    stored.encrypted(), stored.wrappedKey(), false, null);
            store.insert(record);
            persisted = true;
            return store.findById(id).orElse(record);
        } catch (UploadClientException | UploadInternalException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("upload failed", exception);
            throw new UploadInternalException("failed to save uploaded file", exception);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort scratch cleanup, matching the reference.
                }
            }
            if (storedPath != null && !persisted) {
                storage.remove(storedPath);
            }
            release.run();
        }
    }

    private static long copyWithLimit(MultipartFile file, Path destination, long maxBytes) throws IOException {
        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                written += read;
                if (written > maxBytes) {
                    throw new UploadClientException("file exceeds the " + (maxBytes >> 20) + " MB limit");
                }
                output.write(buffer, 0, read);
            }
        }
        return written;
    }

    private static UploadClientException sizeError(long maxBytes) {
        return new UploadClientException("file exceeds the " + (maxBytes >> 20) + " MB limit");
    }

    private static String baseName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload";
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        return name.isBlank() ? "upload" : name;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
