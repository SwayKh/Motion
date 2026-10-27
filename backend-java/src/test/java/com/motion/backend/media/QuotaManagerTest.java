package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Deterministic coverage for the in-memory active-upload reservation and quota ordering. */
class QuotaManagerTest {

    @Test
    void activeUploadReservationIsExclusiveAndReleasedOnFailure() {
        MotionProperties properties = activeOnlyProperties();
        MediaStore store = mock(MediaStore.class);
        QuotaManager quotas = new QuotaManager(store, properties);

        Runnable release = quotas.acquire("user-1", 10);
        assertThatThrownBy(() -> quotas.acquire("user-1", 10))
                .isInstanceOf(UploadClientException.class)
                .hasMessage("quota exceeded: too many simultaneous uploads");

        release.run();
        assertThatCode(() -> quotas.acquire("user-1", 10)).doesNotThrowAnyException();
    }

    @Test
    void quotaFailuresUseGoMessagesAndDoNotLeakReservations() {
        MotionProperties properties = activeOnlyProperties();
        when(properties.maxSourceFilesPerUser()).thenReturn(1);
        MediaStore store = mock(MediaStore.class);
        when(store.countSourceFilesForUser("user-2")).thenReturn(1);
        QuotaManager quotas = new QuotaManager(store, properties);

        assertThatThrownBy(() -> quotas.acquire("user-2", 10))
                .isInstanceOf(UploadClientException.class)
                .hasMessage("quota exceeded: source file limit reached");

        when(store.countSourceFilesForUser("user-2")).thenReturn(0);
        assertThatCode(() -> quotas.acquire("user-2", 10)).doesNotThrowAnyException();
    }

    @Test
    void anonymousUploadsAreRejectedBeforeAnyCheck() {
        QuotaManager quotas = new QuotaManager(mock(MediaStore.class), activeOnlyProperties());

        assertThatThrownBy(() -> quotas.acquire("", 10))
                .isInstanceOf(UploadClientException.class)
                .hasMessage("quota exceeded: authentication required");
    }

    private static MotionProperties activeOnlyProperties() {
        MotionProperties properties = mock(MotionProperties.class);
        when(properties.maxActiveUploadsPerUser()).thenReturn(1);
        when(properties.maxSourceFilesPerUser()).thenReturn(0);
        when(properties.maxOutputsPerUser()).thenReturn(0);
        when(properties.maxStoredBytesPerUser()).thenReturn(0L);
        when(properties.maxTotalStorageBytes()).thenReturn(0L);
        when(properties.minFreeDiskBytes()).thenReturn(0L);
        when(properties.outputDir()).thenReturn(Path.of("."));
        when(properties.retention()).thenReturn(Duration.ofHours(1));
        return properties;
    }
}
