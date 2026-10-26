package com.motion.backend.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fresh standalone databases must receive the complete V1 baseline schema. */
@SpringBootTest(properties = "spring.profiles.active=test")
class FlywaySchemaTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + tempDir.resolve("fresh.db"));
        registry.add("motion.storage.data-dir", () -> tempDir.toString());
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void freshDatabaseReceivesCompleteStandaloneSchema() {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "users", "sessions", "password_reset_tokens", "email_verification_tokens",
                "videos", "jobs", "timeline_projects", "flyway_schema_history");

        assertThat(columns("videos")).contains(
                "id", "user_id", "original_path", "original_name", "file_size", "output_path",
                "status", "progress", "operations", "duration", "width", "height", "error",
                "encrypted", "enc_key", "output_encrypted", "output_enc_key", "created_at");
        assertThat(columns("jobs")).contains(
                "id", "video_id", "user_id", "video_name", "source_path", "operations",
                "status", "progress", "output_path", "error", "encrypted", "enc_key",
                "created_at", "started_at", "finished_at", "cancel_requested_at",
                "execution_token", "attempt", "worker_id");
        assertThat(columns("users")).contains(
                "id", "email", "normalized_email", "password_hash", "role", "status",
                "email_verified", "email_verified_at", "created_at", "updated_at");
    }

    @Test
    void flywayHistoryContainsExactlyTheBaseline() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, description, success FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("version")).isEqualTo("1");
        assertThat(history.get(0).get("success")).isEqualTo(1);
    }

    @Test
    void ownershipColumnsAreMandatoryAndConstrained() {
        assertThat(notNull("videos", "user_id")).isTrue();
        assertThat(notNull("jobs", "user_id")).isTrue();
        assertThat(notNull("timeline_projects", "user_id")).isTrue();

        List<String> videoFks = jdbc.queryForList(
                "SELECT \"table\" FROM pragma_foreign_key_list('videos')", String.class);
        assertThat(videoFks).contains("users");
        List<String> jobFks = jdbc.queryForList(
                "SELECT \"table\" FROM pragma_foreign_key_list('jobs')", String.class);
        assertThat(jobFks).contains("videos", "users");
    }

    @Test
    void partialUniqueActiveJobIndexExists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                        + "AND name='idx_jobs_one_active_per_video'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList(
                "SELECT name FROM pragma_table_info('" + table + "')", String.class);
    }

    private boolean notNull(String table, String column) {
        Integer flag = jdbc.queryForObject(
                "SELECT \"notnull\" FROM pragma_table_info('" + table + "') WHERE name=?",
                Integer.class, column);
        return flag != null && flag == 1;
    }
}
