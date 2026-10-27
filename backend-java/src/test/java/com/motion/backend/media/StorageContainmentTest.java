package com.motion.backend.media;

import com.motion.backend.config.MotionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Filesystem containment tests: lexical AND real-path (symlink-resolved) checks must both
 * hold before a stored path may be read, served, or deleted.
 */
class StorageContainmentTest {

    @TempDir
    Path tempDir;

    private StorageService service() throws IOException {
        MotionProperties properties = new MotionProperties();
        properties.getStorage().setUploadDir(tempDir.resolve("uploads").toString());
        properties.getStorage().setOutputDir(tempDir.resolve("outputs").toString());
        properties.getStorage().setDataDir(tempDir.resolve("data").toString());
        StorageService storage = new StorageService(properties);
        storage.initialize();
        return storage;
    }

    @Test
    void regularFilesInsideRootsAreContained() throws IOException {
        StorageService storage = service();
        Path upload = Files.write(tempDir.resolve("uploads/a.mp4"), new byte[] {1});
        Path output = Files.write(tempDir.resolve("outputs/a-out.mp4"), new byte[] {2});
        assertThat(storage.contains(upload)).isTrue();
        assertThat(storage.contains(output)).isTrue();
    }

    @Test
    void pathsOutsideRootsAreRejected() throws IOException {
        StorageService storage = service();
        Path outside = Files.write(tempDir.resolve("elsewhere.mp4"), new byte[] {1});
        assertThat(storage.contains(outside)).isFalse();
        assertThat(storage.contains(tempDir.resolve("uploads/../secret.mp4").normalize()))
                .isFalse();
    }

    @Test
    void symlinkedFileInsideRootIsRejected() throws IOException {
        StorageService storage = service();
        Path secret = Files.write(tempDir.resolve("secret.mp4"), new byte[] {1});
        Path link = tempDir.resolve("uploads/link.mp4");
        Files.createSymbolicLink(link, secret);
        assertThat(storage.contains(link)).isFalse();
    }

    @Test
    void symlinkedDirectoryInsideRootDoesNotLeakContents() throws IOException {
        StorageService storage = service();
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Path secret = Files.write(outsideDir.resolve("secret.mp4"), new byte[] {1});
        Files.createSymbolicLink(tempDir.resolve("uploads/stolen"), outsideDir);
        // The lexical path lives under uploads/, but the real location is outside the root.
        assertThat(storage.contains(tempDir.resolve("uploads/stolen/secret.mp4"))).isFalse();
        // Direct traversal through the symlinked directory is also rejected.
        assertThat(storage.contains(secret)).isFalse();
    }

    @Test
    void missingFilesInsideRootsAreWriteEligibleButNotReadable() throws IOException {
        StorageService storage = service();
        // A not-yet-created destination inside the root is contained (write path); the
        // read path separately requires Files.isRegularFile, so missing files 404.
        assertThat(storage.contains(tempDir.resolve("uploads/not-yet-created.mp4"))).isTrue();
    }

    @Test
    void tempAndOutputScopesAreIndependent() throws IOException {
        StorageService storage = service();
        Path upload = Files.write(tempDir.resolve("uploads/a.mp4"), new byte[] {1});
        Path output = Files.write(tempDir.resolve("outputs/a-out.mp4"), new byte[] {2});
        assertThat(storage.containsOutput(upload)).isFalse();
        assertThat(storage.containsOutput(output)).isTrue();
        assertThat(storage.containsTemp(output)).isFalse();
    }
}
