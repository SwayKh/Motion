package com.motion.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards against the packaged-jar startup failure caused by an exported empty JDBC URL. */
@SpringBootTest(properties = "spring.profiles.active=test")
class EmptyJdbcUrlStartupTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("MOTION_JDBC_URL", () -> "");
        registry.add("MOTION_DATA_DIR", () -> tempDir.toString());
        registry.add("motion.storage.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void emptyJdbcUrlFallsBackToTheDefaultSqliteLocation() {
        assertThat(((HikariDataSource) dataSource).getJdbcUrl())
                .isEqualTo("jdbc:sqlite:" + tempDir + "/app.db?busy_timeout=5000&journal_mode=WAL");
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(Files.exists(tempDir.resolve("app.db"))).isTrue();
    }
}
