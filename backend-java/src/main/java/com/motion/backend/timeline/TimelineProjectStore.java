package com.motion.backend.timeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.motion.backend.time.LegacyTimestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Direct JDBC access to the application {@code timeline_projects} table. */
@Component
public class TimelineProjectStore {

    private static final String COLUMNS =
            "id, user_id, name, project, revision, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TimelineProjectStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public TimelineProject create(String userId, TimelineProject project) {
        String id = project.id() == null ? UUID.randomUUID().toString() : project.id();
        String now = LegacyTimestamp.format(Instant.now());
        TimelineProject stored = project.withMetadata(id, 1, now, now);
        jdbc.update("INSERT INTO timeline_projects "
                        + "(id, user_id, name, project, revision, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, userId, stored.name(), json(stored), 1, now, now);
        return displayMetadata(stored);
    }

    public List<TimelineProject> list(String userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM timeline_projects "
                        + "WHERE user_id = ? ORDER BY updated_at DESC",
                (rs, rowNum) -> read(rs), userId);
    }

    public Optional<TimelineProject> get(String userId, String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM timeline_projects WHERE id = ? AND user_id = ?",
                rs -> rs.next() ? Optional.of(read(rs)) : Optional.empty(), id, userId);
    }

    public TimelineProject update(String userId, TimelineProject project) {
        String id = project.id();
        String existingCreatedAt = jdbc.query(
                "SELECT created_at FROM timeline_projects WHERE id = ? AND user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id, userId);
        if (existingCreatedAt == null) {
            throw new TimelineProjectNotFoundException();
        }

        int expectedRevision = project.revision() <= 0 ? 1 : project.revision();
        String now = LegacyTimestamp.format(Instant.now());
        // Go marshals before bumping the revision; reads override metadata from columns anyway.
        String payload = json(project.withMetadata(id, expectedRevision, existingCreatedAt, now));
        int updated = jdbc.update("UPDATE timeline_projects SET name = ?, project = ?, revision = ?, "
                        + "updated_at = ? WHERE id = ? AND user_id = ? AND revision = ?",
                project.name(), payload, expectedRevision + 1, now, id, userId, expectedRevision);
        if (updated == 0) {
            throw new TimelineRevisionConflictException();
        }
        return displayMetadata(project.withMetadata(
                id, expectedRevision + 1, existingCreatedAt, now));
    }

    public void delete(String userId, String id) {
        int deleted = jdbc.update("DELETE FROM timeline_projects WHERE id = ? AND user_id = ?", id, userId);
        if (deleted == 0) {
            throw new TimelineProjectNotFoundException();
        }
    }

    private TimelineProject read(ResultSet rs) throws SQLException {
        try {
            TimelineProject project = mapper.readValue(rs.getString("project"), TimelineProject.class);
            return displayMetadata(project.withStoredMetadata(
                    rs.getString("id"),
                    rs.getString("name"),
                    rs.getInt("revision"),
                    rs.getString("created_at"),
                    rs.getString("updated_at")));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("decoding timeline project", exception);
        }
    }

    private static TimelineProject displayMetadata(TimelineProject project) {
        return project.withMetadata(
                project.id(), project.revision(), formatTime(project.createdAt()), formatTime(project.updatedAt()));
    }

    private static String formatTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LegacyTimestamp.parse(value).toString();
        } catch (RuntimeException exception) {
            return value;
        }
    }

    private String json(TimelineProject project) {
        try {
            return mapper.writeValueAsString(project);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("encoding timeline project", exception);
        }
    }
}
