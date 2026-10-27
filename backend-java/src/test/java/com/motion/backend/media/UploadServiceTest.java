package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies that failed uploads never leave scratch files or orphaned stored files behind. */
class UploadServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void probeFailureRemovesTheScratchFile() throws Exception {
        MotionProperties properties = properties();
        StorageService storage = new StorageService(properties);
        storage.initialize();
        MediaProbe probe = mock(MediaProbe.class);
        when(probe.probe(any(Path.class))).thenThrow(new UploadInternalException("probe failed"));
        UploadService service = service(properties, storage, probe, mock(MediaStore.class));

        UploadInternalException failure = catchThrowableOfType(
                () -> service.upload(videoFile(), "user-1"), UploadInternalException.class);

        assertThat(failure).isNotNull().hasMessage("probe failed");
        assertThat(countFiles(properties.tempDir())).isZero();
        assertThat(countFiles(properties.uploadDir())).isZero();
    }

    @Test
    void databaseFailureRemovesTheStoredFile() throws Exception {
        MotionProperties properties = properties();
        StorageService storage = new StorageService(properties);
        storage.initialize();
        MediaProbe probe = mock(MediaProbe.class);
        when(probe.probe(any(Path.class)))
                .thenReturn(new MediaProbe.Metadata(1D, 160, 120, true, false));
        MediaStore store = mock(MediaStore.class);
        doThrow(new RuntimeException("db down")).when(store).insert(any(MediaRecord.class));
        UploadService service = service(properties, storage, probe, store);

        UploadInternalException failure = catchThrowableOfType(
                () -> service.upload(videoFile(), "user-1"), UploadInternalException.class);

        assertThat(failure).isNotNull().hasMessage("failed to save uploaded file");
        assertThat(countFiles(properties.tempDir())).isZero();
        assertThat(countFiles(properties.uploadDir())).isZero();
    }

    private UploadService service(MotionProperties properties, StorageService storage,
                                  MediaProbe probe, MediaStore store) {
        QuotaManager quotas = mock(QuotaManager.class);
        when(quotas.acquire(eq("user-1"), anyLong())).thenReturn(() -> { });
        return new UploadService(properties, storage, probe, quotas, store);
    }

    private MotionProperties properties() {
        MotionProperties properties = mock(MotionProperties.class);
        when(properties.encryptionKey()).thenReturn("");
        when(properties.dataDir()).thenReturn(tempDir.resolve("data"));
        when(properties.uploadDir()).thenReturn(tempDir.resolve("uploads"));
        when(properties.outputDir()).thenReturn(tempDir.resolve("outputs"));
        when(properties.tempDir()).thenReturn(tempDir.resolve("data/tmp"));
        when(properties.previewDir()).thenReturn(tempDir.resolve("data/previews"));
        when(properties.retention()).thenReturn(Duration.ofHours(1));
        when(properties.maxUploadBytes()).thenReturn(1024L * 1024);
        when(properties.maxDurationSeconds()).thenReturn(300);
        when(properties.maxWidth()).thenReturn(1920);
        when(properties.maxHeight()).thenReturn(1080);
        return properties;
    }

    private static MultipartFile videoFile() {
        return new MockMultipartFile("video", "sample.mp4", "application/octet-stream",
                "fake-but-sized-video".getBytes());
    }

    private static long countFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.count();
        }
    }
}
