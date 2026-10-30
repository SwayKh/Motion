package com.motion.backend.processing;

import com.motion.backend.config.MotionProperties;
import com.motion.backend.media.MediaStore;
import com.motion.backend.time.LegacyTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Explicit job state transitions that mirror the Go database store. */
@Component
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);

    private static final String COLUMNS =
            "id, video_id, video_name, source_path, operations, status, progress, output_path, "
                    + "error, created_at, started_at, finished_at, cancel_requested_at, user_id, "
                    + "encrypted, enc_key";
    private static final String LEASE_COLUMNS =
            COLUMNS + ", execution_token, attempt, worker_id";
    private static final String ACTIVE = "'queued', 'processing', 'cancel_requested'";
    private static final String TERMINAL = "'completed', 'failed', 'cancelled'";

    private static final RowMapper<JobRecord> MAPPER = (rs, rowNum) -> job(rs);

    private static final RowMapper<JobLease> LEASE_MAPPER = (rs, rowNum) -> new JobLease(
            job(rs),
            rs.getString("execution_token"),
            rs.getInt("attempt"),
            rs.getString("worker_id"));

    private final JdbcTemplate jdbc;
    private final MediaStore mediaStore;
    private final MotionProperties properties;

    public JobStore(JdbcTemplate jdbc, MediaStore mediaStore, MotionProperties properties) {
        this.jdbc = jdbc;
        this.mediaStore = mediaStore;
        this.properties = properties;
    }

    /**
     * Atomically persists a queued job after checking every quota. The {@code INSERT ... SELECT}
     * predicate is authoritative; the partial unique index is a second line of defense.
     */
    @Transactional
    public JobRecord createQueued(String videoId, String videoName, String sourcePath,
                                  String operationsJson, String userId) {
        int userLimit = properties.maxConcurrentJobsPerUser();
        int globalLimit = properties.maxGlobalConcurrentJobs();
        int userEnabled = userId != null && userLimit > 0 ? 1 : 0;
        int globalEnabled = globalLimit > 0 ? 1 : 0;

        String id = UUID.randomUUID().toString();
        String now = LegacyTimestamp.format(Instant.now());
        String insert = """
                INSERT INTO jobs (id, video_id, video_name, source_path, operations, status,
                                  progress, created_at, user_id)
                SELECT ?, ?, ?, ?, ?, 'queued', 0, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM jobs WHERE video_id = ? AND status IN (%s)
                )
                AND (? = 0 OR (SELECT COUNT(1) FROM jobs
                    WHERE user_id = ? AND status IN (%s)) < ?)
                AND (? = 0 OR (SELECT COUNT(1) FROM jobs
                    WHERE status IN (%s)) < ?)
                """.formatted(ACTIVE, ACTIVE, ACTIVE);

        int inserted;
        try {
            inserted = jdbc.update(insert,
                    id, videoId, videoName, sourcePath, operationsJson, now, userId,
                    videoId,
                    userEnabled, userId, userLimit,
                    globalEnabled, globalLimit);
        } catch (DataIntegrityViolationException exception) {
            throwQuotaIfExceeded(videoId, userId);
            throw new JobConflictException();
        }
        if (inserted == 0) {
            throwQuotaIfExceeded(videoId, userId);
            throw new JobConflictException();
        }

        jdbc.update("UPDATE videos SET status = 'queued', operations = ?, progress = 0, "
                        + "error = NULL, output_path = NULL WHERE id = ?",
                operationsJson, videoId);
        return findById(id).orElseThrow(() -> new ProcessingException("failed to queue video"));
    }

    public Optional<JobRecord> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jobs WHERE id = ?",
                rs -> rs.next() ? Optional.of(MAPPER.mapRow(rs, 0)) : Optional.empty(), id);
    }

    public Optional<JobLease> findLeaseById(String id) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM jobs WHERE id = ?",
                rs -> rs.next() ? Optional.of(LEASE_MAPPER.mapRow(rs, 0)) : Optional.empty(), id);
    }

    /** Interrupted rows recovered before workers start; oldest first. */
    public List<JobLease> listInterrupted() {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM jobs "
                        + "WHERE status IN ('processing', 'cancel_requested') "
                        + "ORDER BY created_at ASC, id ASC",
                LEASE_MAPPER);
    }

    public List<JobLease> listByStatus(String status) {
        return jdbc.query("SELECT " + LEASE_COLUMNS + " FROM jobs WHERE status = ? "
                        + "ORDER BY created_at ASC, id ASC", LEASE_MAPPER, status);
    }

    public List<JobRecord> listByUser(String userId) {
        return jdbc.query("SELECT DISTINCT j." + COLUMNS.replace(", ", ", j.")
                        + " FROM jobs j LEFT JOIN videos v ON v.id = j.video_id "
                        + "WHERE j.user_id = ? OR v.user_id = ? "
                        + "ORDER BY j.created_at DESC, j.id DESC",
                MAPPER, userId, userId);
    }

    public List<JobRecord> listForVideo(String videoId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM jobs WHERE video_id = ? "
                        + "ORDER BY created_at DESC, id DESC", MAPPER, videoId);
    }

    public List<JobRecord> listAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM jobs", MAPPER);
    }

    public static boolean active(String status) {
        return "queued".equals(status) || "processing".equals(status)
                || "cancel_requested".equals(status);
    }

    public boolean accessible(JobRecord job, String userId) {
        if (job == null || userId == null) {
            return false;
        }
        if (userId.equals(job.userId())) {
            return true;
        }
        return mediaStore.findById(job.videoId())
                .map(video -> userId.equals(video.userId()))
                .orElse(false);
    }

    /** Legacy synchronous start retained until the worker owns job transitions. */
    @Transactional
    public boolean start(String id) {
        String now = LegacyTimestamp.format(Instant.now());
        int updated = jdbc.update("UPDATE jobs SET status = 'processing', started_at = ?, "
                        + "finished_at = NULL, cancel_requested_at = NULL, output_path = NULL, "
                        + "encrypted = 0, enc_key = NULL, error = NULL "
                        + "WHERE id = ? AND status = 'queued'",
                now, id);
        if (updated == 0) {
            return false;
        }
        JobRecord job = findById(id).orElseThrow();
        jdbc.update("UPDATE videos SET status = 'processing', operations = ?, progress = 0, "
                        + "error = NULL, output_path = NULL WHERE id = ?",
                job.operations(), job.videoId());
        return true;
    }

    /** Legacy synchronous progress retained until the worker owns job transitions. */
    @Transactional
    public void updateProgress(String id, double progress) {
        applyProgress(id, null, false, progress);
    }



    /** Legacy synchronous completion retained until the worker owns job transitions. */
    @Transactional
    public Optional<JobRecord> complete(String id, String outputPath, boolean encrypted, String encKey) {
        return finish(id, null, false, outputPath, encrypted, encKey);
    }

    /** Legacy synchronous failure retained until the worker owns job transitions. */
    @Transactional
    public Optional<JobRecord> fail(String id, String message) {
        return fail(id, null, false, message);
    }

    /** Atomically claims the oldest queued job and issues a unique execution lease. */
    @Transactional
    public Optional<JobLease> claimNext(String workerId) {
        String token = UUID.randomUUID().toString();
        String now = LegacyTimestamp.format(Instant.now());
        String sql = """
                UPDATE jobs
                SET status = 'processing', execution_token = ?, worker_id = ?, attempt = attempt + 1,
                    started_at = ?, finished_at = NULL, cancel_requested_at = NULL,
                    progress = 0, output_path = NULL, encrypted = 0, enc_key = NULL, error = NULL
                WHERE id = (
                    SELECT id FROM jobs WHERE status = 'queued'
                    ORDER BY created_at ASC, id ASC LIMIT 1
                )
                RETURNING %s
                """.formatted(LEASE_COLUMNS);
        Optional<JobLease> claimed = jdbc.query(sql, statement -> {
            statement.setString(1, token);
            statement.setString(2, workerId);
            statement.setString(3, now);
        }, rs -> rs.next() ? Optional.of(LEASE_MAPPER.mapRow(rs, 0)) : Optional.empty());
        claimed.ifPresent(lease -> jdbc.update(
                "UPDATE videos SET status = 'processing', operations = ?, progress = 0, "
                        + "error = NULL, output_path = NULL WHERE id = ?",
                lease.job().operations(), lease.job().videoId()));
        return claimed;
    }

    @Transactional
    public boolean updateProgress(String id, String token, double progress) {
        return applyProgress(id, token, true, progress);
    }

    @Transactional
    public Optional<JobRecord> complete(String id, String token, String outputPath,
                                        boolean encrypted, String encKey) {
        return finish(id, token, true, outputPath, encrypted, encKey);
    }

    @Transactional
    public Optional<JobRecord> fail(String id, String token, String message) {
        return fail(id, token, true, message);
    }

    /**
     * Requests cancellation. Queued jobs become cancelled immediately; running jobs enter Go's
     * cancel_requested substate and keep their lease until the worker finalizes them.
     */
    @Transactional
    public CancelResult requestCancel(String id) {
        JobLease current = findLeaseById(id).orElse(null);
        if (current == null) {
            return CancelResult.notCancellable();
        }
        String status = current.job().status();
        String now = LegacyTimestamp.format(Instant.now());

        if ("queued".equals(status)) {
            int updated = jdbc.update("UPDATE jobs SET status = 'cancelled', finished_at = ?, "
                            + "error = NULL, output_path = NULL, encrypted = 0, enc_key = NULL, "
                            + "progress = 0 WHERE id = ? AND status = 'queued'",
                    now, id);
            if (updated == 0) {
                return CancelResult.notCancellable();
            }
            jdbc.update("UPDATE videos SET status = 'cancelled', progress = 0, output_path = NULL, "
                            + "output_encrypted = 0, output_enc_key = NULL, error = NULL WHERE id = ?",
                    current.job().videoId());
            return CancelResult.queued(current.token(), findLeaseById(id).orElseThrow().job());
        }
        if ("processing".equals(status)) {
            int updated = jdbc.update("UPDATE jobs SET status = 'cancel_requested', "
                            + "cancel_requested_at = ? WHERE id = ? AND status = 'processing'",
                    now, id);
            if (updated == 0) {
                return CancelResult.notCancellable();
            }
            jdbc.update("UPDATE videos SET status = 'cancel_requested' WHERE id = ?",
                    current.job().videoId());
            return CancelResult.running(current.token(), findLeaseById(id).orElseThrow().job());
        }
        return CancelResult.notCancellable();
    }

    @Transactional
    public boolean markCancelled(String id, String token) {
        String now = LegacyTimestamp.format(Instant.now());
        int updated = jdbc.update("UPDATE jobs SET status = 'cancelled', finished_at = ?, "
                        + "error = NULL, output_path = NULL, encrypted = 0, enc_key = NULL "
                        + "WHERE id = ? AND execution_token = ? "
                        + "AND status IN ('processing', 'cancel_requested')",
                now, id, token);
        if (updated == 0) {
            return false;
        }
        JobLease lease = findLeaseById(id).orElseThrow();
        jdbc.update("UPDATE videos SET status = 'cancelled', output_path = NULL, "
                        + "output_encrypted = 0, output_enc_key = NULL, error = NULL WHERE id = ?",
                lease.job().videoId());
        return true;
    }

    /** Requeues an interrupted lease for startup/shutdown recovery, invalidating the old token. */
    @Transactional
    public boolean requeueInterrupted(String id, String token) {
        int updated = jdbc.update("UPDATE jobs SET status = 'queued', execution_token = NULL, "
                        + "worker_id = NULL, started_at = NULL, finished_at = NULL, "
                        + "cancel_requested_at = NULL, progress = 0, output_path = NULL, "
                        + "encrypted = 0, enc_key = NULL, error = NULL "
                        + "WHERE id = ? AND execution_token = ? AND status = 'processing'",
                id, token);
        if (updated == 0) {
            return false;
        }
        JobLease lease = findLeaseById(id).orElseThrow();
        jdbc.update("UPDATE videos SET status = 'queued', operations = ?, progress = 0, "
                        + "error = NULL, output_path = NULL WHERE id = ?",
                lease.job().operations(), lease.job().videoId());
        return true;
    }

    /**
     * Deletes one already-approved expired terminal row, rechecking status and active-job state
     * inside the transaction. The caller is responsible for the age and current-output checks.
     */
    @Transactional
    public boolean deleteExpiredHistory(String id) {
        int deleted = jdbc.update("DELETE FROM jobs WHERE id = ? AND status IN (" + TERMINAL + ") "
                + "AND NOT EXISTS (SELECT 1 FROM jobs active WHERE active.video_id = "
                + "(SELECT video_id FROM jobs WHERE id = ?) "
                + "AND active.status IN (" + ACTIVE + "))", id, id);
        return deleted > 0;
    }

    @Transactional
    public void deleteAndResetVideo(JobRecord job) {
        jdbc.update("DELETE FROM jobs WHERE id = ?", job.id());
        if (job.outputPath() != null && !job.outputPath().isBlank()) {
            jdbc.update("UPDATE videos SET status = 'uploaded', progress = 0, output_path = NULL, "
                            + "output_encrypted = 0, output_enc_key = NULL, error = NULL "
                            + "WHERE id = ? AND output_path = ?",
                    job.videoId(), job.outputPath());
        } else {
            jdbc.update("UPDATE videos SET status = 'uploaded', progress = 0, error = NULL "
                    + "WHERE id = ? AND output_path IS NULL", job.videoId());
        }
    }

    private boolean applyProgress(String id, String token, boolean leased, double progress) {
        double clamped = Math.max(0, Math.min(100, progress));
        String sql = "UPDATE jobs SET progress = ? WHERE id = ? AND status = 'processing'";
        int updated;
        if (leased) {
            sql += " AND execution_token = ? AND cancel_requested_at IS NULL";
            updated = jdbc.update(sql, clamped, id, token);
        } else {
            updated = jdbc.update(sql, clamped, id);
        }
        if (updated == 0) {
            return false;
        }
        jdbc.update("UPDATE videos SET progress = ? WHERE id = "
                        + "(SELECT video_id FROM jobs WHERE id = ?)",
                clamped, id);
        return true;
    }

    private Optional<JobRecord> finish(String id, String token, boolean leased, String outputPath,
                                       boolean encrypted, String encKey) {
        String now = LegacyTimestamp.format(Instant.now());
        String sql = "UPDATE jobs SET status = 'completed', progress = 100, "
                + "output_path = ?, encrypted = ?, enc_key = ?, error = NULL, finished_at = ? "
                + "WHERE id = ? AND status = 'processing' AND ? IS NOT NULL AND ? <> ''";
        int updated;
        if (leased) {
            sql += " AND execution_token = ? AND cancel_requested_at IS NULL";
            updated = jdbc.update(sql, outputPath, encrypted ? 1 : 0, encKey, now,
                    id, outputPath, outputPath, token);
        } else {
            updated = jdbc.update(sql, outputPath, encrypted ? 1 : 0, encKey, now,
                    id, outputPath, outputPath);
        }
        if (updated == 0) {
            return Optional.empty();
        }
        JobRecord job = findById(id).orElseThrow();
        jdbc.update("UPDATE videos SET status = 'completed', progress = 100, output_path = ?, "
                        + "output_encrypted = ?, output_enc_key = ?, error = NULL WHERE id = ?",
                outputPath, encrypted ? 1 : 0, encKey, job.videoId());
        return Optional.of(job);
    }

    private Optional<JobRecord> fail(String id, String token, boolean leased, String message) {
        String now = LegacyTimestamp.format(Instant.now());
        String sql = "UPDATE jobs SET status = 'failed', error = ?, output_path = NULL, "
                + "encrypted = 0, enc_key = NULL, finished_at = ? "
                + "WHERE id = ? AND status = 'processing'";
        int updated;
        if (leased) {
            sql += " AND execution_token = ? AND cancel_requested_at IS NULL";
            updated = jdbc.update(sql, safeMessage(message), now, id, token);
        } else {
            updated = jdbc.update(sql, safeMessage(message), now, id);
        }
        if (updated == 0) {
            return Optional.empty();
        }
        JobRecord job = findById(id).orElseThrow();
        jdbc.update("UPDATE videos SET status = 'failed', progress = ?, output_path = NULL, "
                        + "output_encrypted = 0, output_enc_key = NULL, error = ? WHERE id = ?",
                job.progress(), safeMessage(message), job.videoId());
        return Optional.of(job);
    }

    private void throwQuotaIfExceeded(String videoId, String userId) {
        // Go checks one-active-job-per-video before global/per-user quotas.
        if (mediaStore.hasActiveJobs(videoId)) {
            throw new JobConflictException();
        }
        int userLimit = properties.maxConcurrentJobsPerUser();
        int globalLimit = properties.maxGlobalConcurrentJobs();
        if (userLimit > 0 && userId != null && countActive(userId) >= userLimit) {
            throw new JobQuotaException();
        }
        if (globalLimit > 0 && countActive(null) >= globalLimit) {
            throw new JobQuotaException();
        }
    }

    private int countActive(String userId) {
        String sql = "SELECT COUNT(1) FROM jobs WHERE status IN (" + ACTIVE + ")";
        return userId == null
                ? jdbc.queryForObject(sql, Integer.class)
                : jdbc.queryForObject(sql + " AND user_id = ?", Integer.class, userId);
    }

    private int countQueued() {
        Integer count = jdbc.queryForObject("SELECT COUNT(1) FROM jobs WHERE status = 'queued'",
                Integer.class);
        return count == null ? 0 : count;
    }

    /** Never persist control characters or FFmpeg stderr in a user-visible job error. */
    static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "video processing failed";
        }
        String clean = message.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private static JobRecord job(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JobRecord(
                rs.getString("id"),
                rs.getString("video_id"),
                rs.getString("video_name"),
                rs.getString("source_path"),
                rs.getString("operations"),
                rs.getString("status"),
                rs.getDouble("progress"),
                rs.getString("output_path"),
                rs.getString("error"),
                rs.getString("created_at"),
                rs.getString("started_at"),
                rs.getString("finished_at"),
                rs.getString("cancel_requested_at"),
                rs.getString("user_id"),
                rs.getInt("encrypted") != 0,
                rs.getString("enc_key"));
    }
}
