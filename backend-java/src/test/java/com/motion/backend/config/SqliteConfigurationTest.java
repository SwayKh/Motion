package com.motion.backend.config;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the runtime database settings that the Go backend depends on. The URL deliberately
 * includes the same pragmas as {@code application.yml}; Hikari's connection-init SQL supplies
 * {@code foreign_keys=ON}.
 */
@SpringBootTest(properties = "spring.profiles.active=test")
class SqliteConfigurationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () ->
                "jdbc:sqlite:" + tempDir.resolve("pragmas.db") + "?busy_timeout=5000&journal_mode=WAL");
        registry.add("motion.storage.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void sqlitePragmasMatchGoBackend() throws SQLException {
        assertThat(pragma("journal_mode")).isEqualToIgnoringCase("wal");
        assertThat(pragma("busy_timeout")).isEqualTo("5000");
        assertThat(pragma("foreign_keys")).isEqualTo("1");
    }

    @Test
    void hibernateUsesCommunitySqliteDialect() {
        SessionFactoryImplementor sessionFactory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        assertThat(sessionFactory.getJdbcServices().getDialect()).isInstanceOf(SQLiteDialect.class);
    }

    private String pragma(String name) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA " + name)) {
            assertThat(resultSet.next()).as("PRAGMA %s returned a row", name).isTrue();
            return resultSet.getString(1);
        }
    }
}
