package com.motion.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the fresh-checkout startup failure: a brand-new clone has no
 * {@code data/} directory, and SQLite cannot open {@code data/app.db} unless its parent
 * directory exists. The DataSource configuration must create it before the first
 * connection (StorageService only runs later, during bean initialization).
 */
@SpringBootTest(properties = "spring.profiles.active=test")
class FreshCheckoutStartupTest {

    @TempDir
    static Path tempDir;

    /** Deliberately NOT created - simulates a fresh clone before the first run. */
    static Path freshDataDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        freshDataDir = tempDir.resolve("fresh-data");
        registry.add("MOTION_JDBC_URL",
                () -> "jdbc:sqlite:" + freshDataDir + "/app.db?busy_timeout=5000&journal_mode=WAL");
        registry.add("motion.storage.data-dir", freshDataDir::toString);
        registry.add("motion.storage.upload-dir", () -> tempDir.resolve("fresh-uploads").toString());
        registry.add("motion.storage.output-dir", () -> tempDir.resolve("fresh-outputs").toString());
        registry.add("motion.storage.temp-dir", () -> freshDataDir.resolve("tmp").toString());
        registry.add("motion.storage.preview-dir", () -> freshDataDir.resolve("previews").toString());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void applicationStartsWhenNoDataDirectoryExistsYet() {
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(Files.isRegularFile(freshDataDir.resolve("app.db"))).isTrue();
    }
}
