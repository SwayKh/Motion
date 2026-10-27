package com.motion.backend.config;

import com.motion.backend.MotionApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Invalid encryption configuration fails startup without echoing the configured value. */
class InvalidEncryptionKeyStartupTest {

    private static final String INVALID_KEY = "definitely-not-a-32-byte-master-key";

    @TempDir
    Path tempDir;

    @Test
    void invalidMasterKeyAbortsStartupWithASafeMessage() {
        String[] args = {
                "--spring.profiles.active=test",
                "--spring.datasource.url=jdbc:sqlite:" + tempDir.resolve("invalid-key.db"),
                "--motion.storage.data-dir=" + tempDir.resolve("data"),
                "--motion.storage.upload-dir=" + tempDir.resolve("uploads"),
                "--motion.storage.output-dir=" + tempDir.resolve("outputs"),
                "--motion.storage.temp-dir=" + tempDir.resolve("data/tmp"),
                "--motion.storage.preview-dir=" + tempDir.resolve("data/previews"),
                "--motion.encryption.key=" + INVALID_KEY
        };
        assertThatThrownBy(() -> new SpringApplicationBuilder(MotionApplication.class)
                .web(WebApplicationType.NONE)
                .run(args))
                .isInstanceOf(Exception.class)
                .satisfies(exception -> {
                    StringBuilder messages = new StringBuilder();
                    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                        messages.append(cause.getMessage()).append('\n');
                    }
                    assertThat(messages.toString())
                            .contains("MOTION_ENCRYPTION_KEY", "32-byte")
                            .doesNotContain(INVALID_KEY);
                });
    }
}
