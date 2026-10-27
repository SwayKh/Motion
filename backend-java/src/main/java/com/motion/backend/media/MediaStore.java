package com.motion.backend.media;

import com.motion.backend.processing.JobState;
import com.motion.backend.time.LegacyTimestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Direct JDBC access to the application {@code videos} and {@code jobs} tables.
 *
 * <p>The Phase 1 decision is preserved: JDBC is the only persistence model. Queries are always
 * ownership-aware at the caller boundary, and active-job checks reuse the exact Go statuses.</p>
 */
@Component
public class MediaStore {

    private static final String VIDEO_COLUMNS =
            "id, original_path, original_name, file_size, output_path, status, progress, "
                    + "operations, duration, width, height, error, created_at, user_id, "
                    + "encrypted, enc_key, output_encrypted, output_enc_key";

    private static final RowMapper<MediaRecord> VIDEO_MAPPER = (rs, rowNum) -> new MediaRecord(
            rs.getString("id"),
            rs.getString("original_path"),
            rs.getString("original_name"),
            rs.getLong("file_size"),
            rs.getString("output_path"),
            rs.getString("status"),
            rs.getDouble("progress"),
            rs.getString("operations"),
            rs.getDouble("duration"),
            rs.getInt("width"),
            rs.getInt("height"),
            rs.getString("error"),
            rs.getString("created_at"),
            rs.getString("user_id"),
            rs.getInt("encrypted") != 0,
            rs.getString("enc_key"),
            rs.getInt("output_encrypted") != 0,
            rs.getString("output_enc_key"));

    private final JdbcTemplate jdbc;

    public MediaStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MediaRecord> findById(String id) {
        return jdbc.query("SELECT " + VIDEO_COLUMNS + " FROM videos WHERE id = ?",
                rs -> rs.next() ? Optional.of(VIDEO_MAPPER.mapRow(rs, 0)) : Optional.empty(), id);
    }

    public List<MediaRecord> listByUser(String userId) {
        return jdbc.query("SELECT " + VIDEO_COLUMNS + " FROM videos WHERE user_id = ? "
                + "ORDER BY created_at DESC", VIDEO_MAPPER, userId);
    }

    public void insert(MediaRecord video) {
        jdbc.update("INSERT INTO videos (id, original_path, original_name, file_size, output_path, "
                        + "status, progress, operations, duration, width, height, error, created_at, "
                        + "user_id, encrypted, enc_key, output_encrypted, output_enc_key) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                video.id(), video.originalPath(), video.originalName(), video.fileSize(),
                video.outputPath(), video.status(), video.progress(), video.operations(),
                video.duration(), video.width(), video.height(), video.error(), video.createdAt(),
                video.userId(), video.encrypted() ? 1 : 0, video.encKey(),
                video.outputEncrypted() ? 1 : 0, video.outputEncKey());
    }

    public void delete(String id) {
        jdbc.update("DELETE FROM videos WHERE id = ?", id);
    }

    public List<MediaRecord> listAllVideos() {
        return jdbc.query("SELECT " + VIDEO_COLUMNS + " FROM videos", VIDEO_MAPPER);
    }

    public record JobArtifact(String id, String outputPath, String status, boolean encrypted) {
    }

    public List<JobArtifact> listAllJobArtifacts() {
        return jdbc.query("SELECT id, output_path, status, encrypted FROM jobs",
                (rs, rowNum) -> new JobArtifact(
                        rs.getString("id"), rs.getString("output_path"), rs.getString("status"),
                        rs.getInt("encrypted") != 0));
    }

    public boolean isActiveJob(String id) {
        Boolean result = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM jobs WHERE id = ? "
                        + "AND status IN ('queued', 'processing', 'cancel_requested'))",
                Boolean.class, id);
        return Boolean.TRUE.equals(result);
    }

    /** Updates every DB reference that points at a path after a verified in-place encryption. */
    public int markEncryptedByPath(String path, String wrappedKey) {
        int updated = jdbc.update(
                "UPDATE videos SET encrypted = 1, enc_key = ? WHERE original_path = ?",
                wrappedKey, path);
        updated += jdbc.update(
                "UPDATE videos SET output_encrypted = 1, output_enc_key = ? WHERE output_path = ?",
                wrappedKey, path);
        updated += jdbc.update(
                "UPDATE jobs SET encrypted = 1, enc_key = ? WHERE output_path = ?",
                wrappedKey, path);
        return updated;
    }

    public boolean hasActiveJobs(String videoId) {
        Boolean result = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM jobs WHERE video_id = ? "
                        + "AND status IN ('queued', 'processing', 'cancel_requested'))",
                Boolean.class, videoId);
        return Boolean.TRUE.equals(result);
    }

    public List<String> listJobOutputPaths(String videoId) {
        return jdbc.query("SELECT COALESCE(output_path, '') FROM jobs WHERE video_id = ?",
                (rs, rowNum) -> rs.getString(1), videoId);
    }

    public void deleteJobsForVideo(String videoId) {
        jdbc.update("DELETE FROM jobs WHERE video_id = ?", videoId);
    }

    public int countSourceFilesForUser(String userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM videos WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    public int countCompletedOutputsForUser(String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM jobs WHERE user_id = ? AND status = 'completed'", Integer.class, userId);
        return count == null ? 0 : count;
    }

    public long sumStoredBytesForUser(String userId) {
        Long used = jdbc.queryForObject(
                "SELECT COALESCE(SUM(file_size), 0) FROM videos WHERE user_id = ?", Long.class, userId);
        return used == null ? 0L : used;
    }

    public long sumStoredBytesAll() {
        Long used = jdbc.queryForObject("SELECT COALESCE(SUM(file_size), 0) FROM videos", Long.class);
        return used == null ? 0L : used;
    }

    public record ExpiredVideoCandidate(String id) {
    }

    public record ExpiredVideoJob(String id, String outputPath) {
    }

    public record ExpiredVideoTarget(String videoId, String originalPath, String outputPath,
                                     List<ExpiredVideoJob> jobs) {
    }

    /** Lists videos eligible for application expired-video cleanup (terminal, old, no blocking jobs). */
    public List<ExpiredVideoCandidate> listExpiredVideos(Instant before) {
        List<VideoRow> videos = jdbc.query(
                "SELECT id, original_path, output_path, status, created_at FROM videos "
                        + "WHERE status IN ('completed', 'failed', 'cancelled')",
                (rs, rowNum) -> new VideoRow(
                        rs.getString("id"),
                        rs.getString("original_path"),
                        rs.getString("output_path"),
                        rs.getString("status"),
                        rs.getString("created_at")));
        List<ExpiredVideoCandidate> eligible = new ArrayList<>();
        for (VideoRow video : videos) {
            if (!isBefore(video.createdAt(), before)) {
                continue;
            }
            if (hasBlockingJobForExpiry(video.id(), before)) {
                continue;
            }
            eligible.add(new ExpiredVideoCandidate(video.id()));
        }
        return eligible;
    }

    private boolean hasBlockingJobForExpiry(String videoId, Instant before) {
        List<JobRow> jobs = jdbc.query(
                "SELECT id, COALESCE(output_path, '') AS output_path, status, created_at, finished_at FROM jobs WHERE video_id = ?",
                (rs, rowNum) -> new JobRow(
                        rs.getString("id"),
                        rs.getString("output_path"),
                        rs.getString("status"),
                        rs.getString("created_at"),
                        rs.getString("finished_at")),
                videoId);
        for (JobRow job : jobs) {
            if (!JobState.isTerminal(job.status())) {
                return true;
            }
            if (!isBefore(job.createdAt(), before)) {
                return true;
            }
            if (job.finishedAt() != null && !job.finishedAt().isBlank() && !isBefore(job.finishedAt(), before)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Transactionally rechecks eligibility and deletes an expired video row, mirroring Go's
     * {@code DeleteExpiredVideo}. Returns the cleanup target only when the row was actually deleted.
     */
    @Transactional
    public Optional<ExpiredVideoTarget> deleteExpiredVideo(String id, Instant before) {
        String cutoff = LegacyTimestamp.format(before);
        List<VideoRow> rows = jdbc.query(
                "SELECT id, original_path, output_path, status, created_at FROM videos WHERE id = ?",
                (rs, rowNum) -> new VideoRow(
                        rs.getString("id"),
                        rs.getString("original_path"),
                        rs.getString("output_path"),
                        rs.getString("status"),
                        rs.getString("created_at")),
                id);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        VideoRow video = rows.get(0);
        if (!isTerminalVideoStatus(video.status()) || !isBefore(video.createdAt(), before)) {
            return Optional.empty();
        }

        List<JobRow> jobs = jdbc.query(
                "SELECT id, COALESCE(output_path, '') AS output_path, status, created_at, finished_at FROM jobs WHERE video_id = ?",
                (rs, rowNum) -> new JobRow(
                        rs.getString("id"),
                        rs.getString("output_path"),
                        rs.getString("status"),
                        rs.getString("created_at"),
                        rs.getString("finished_at")),
                id);
        List<ExpiredVideoJob> cleanupJobs = new ArrayList<>(jobs.size());
        for (JobRow job : jobs) {
            cleanupJobs.add(new ExpiredVideoJob(job.id(), job.outputPath()));
            if (!JobState.isTerminal(job.status())
                    || !isBefore(job.createdAt(), before)
                    || (job.finishedAt() != null && !job.finishedAt().isBlank()
                        && !isBefore(job.finishedAt(), before))) {
                return Optional.empty();
            }
        }

        int affected = jdbc.update("DELETE FROM videos WHERE id = ?", id);
        if (affected != 1) {
            return Optional.empty();
        }
        return Optional.of(new ExpiredVideoTarget(
                video.id(), video.originalPath(), video.outputPath(), cleanupJobs));
    }

    private static boolean isTerminalVideoStatus(String status) {
        return "completed".equals(status) || "failed".equals(status) || "cancelled".equals(status);
    }

    private static boolean isBefore(String timestamp, Instant cutoff) {
        if (timestamp == null || timestamp.isBlank()) {
            return false;
        }
        try {
            return LegacyTimestamp.parse(timestamp).isBefore(cutoff);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private record VideoRow(String id, String originalPath, String outputPath, String status, String createdAt) {
    }

    private record JobRow(String id, String outputPath, String status, String createdAt, String finishedAt) {
    }
}
