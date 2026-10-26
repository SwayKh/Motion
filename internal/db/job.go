package db

import (
	"database/sql"
	"errors"
	"fmt"
	"strings"

	"VideoProject/internal/models"

	"github.com/google/uuid"
)

var (
	ErrVideoNotFound     = errors.New("video not found")
	ErrJobNotFound       = errors.New("job not found")
	ErrActiveJob         = errors.New("video already has an active job")
	ErrJobNotCancellable = errors.New("job cannot be cancelled")
)

type JobStore struct {
	db *sql.DB
}

func NewJobStore(db *sql.DB) *JobStore {
	return &JobStore{db: db}
}

const jobColumns = `
	id,
	video_id,
	COALESCE(video_name, ''),
	source_path,
	operations,
	status,
	COALESCE(progress, 0),
	COALESCE(output_path, ''),
	COALESCE(error, ''),
	created_at,
	started_at,
	finished_at,
	cancel_requested_at
`

func scanJob(row rowScanner) (*models.Job, error) {
	var (
		job               models.Job
		startedAt         sql.NullTime
		finishedAt        sql.NullTime
		cancelRequestedAt sql.NullTime
	)

	err := row.Scan(
		&job.ID,
		&job.VideoID,
		&job.VideoName,
		&job.SourcePath,
		&job.Operations,
		&job.Status,
		&job.Progress,
		&job.OutputPath,
		&job.Error,
		&job.CreatedAt,
		&startedAt,
		&finishedAt,
		&cancelRequestedAt,
	)
	if err != nil {
		return nil, err
	}

	if startedAt.Valid {
		value := startedAt.Time
		job.StartedAt = &value
	}
	if finishedAt.Valid {
		value := finishedAt.Time
		job.FinishedAt = &value
	}
	if cancelRequestedAt.Valid {
		value := cancelRequestedAt.Time
		job.CancelRequestedAt = &value
	}

	return &job, nil
}

func (s *JobStore) CreateQueued(videoID, operations string) (*models.Job, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, fmt.Errorf("starting job creation: %w", err)
	}
	defer tx.Rollback()

	var (
		sourcePath string
		videoName  sql.NullString
	)

	err = tx.QueryRow(`
		SELECT original_path, original_name
		FROM videos
		WHERE id = ?
	`, videoID).Scan(&sourcePath, &videoName)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrVideoNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("loading video %s: %w", videoID, err)
	}
	if sourcePath == "" {
		return nil, fmt.Errorf("video %s has no source file", videoID)
	}

	var active int
	if err := tx.QueryRow(`
		SELECT COUNT(1)
		FROM jobs
		WHERE video_id = ? AND status IN ('queued', 'processing', 'cancel_requested')
	`, videoID).Scan(&active); err != nil {
		return nil, fmt.Errorf("checking active jobs for video %s: %w", videoID, err)
	}
	if active > 0 {
		return nil, ErrActiveJob
	}

	jobID := uuid.NewString()
	_, err = tx.Exec(`
		INSERT INTO jobs (
			id,
			video_id,
			video_name,
			source_path,
			operations,
			status,
			progress,
			created_at
		)
		VALUES (?, ?, ?, ?, ?, ?, 0, STRFTIME('%Y-%m-%d %H:%M:%f', 'now'))
	`,
		jobID,
		videoID,
		videoName.String,
		sourcePath,
		operations,
		models.StatusQueued,
	)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			return nil, ErrActiveJob
		}
		return nil, fmt.Errorf("creating job for video %s: %w", videoID, err)
	}

	_, err = tx.Exec(`
		UPDATE videos
		SET
			status = ?,
			operations = ?,
			progress = 0,
			error = NULL,
			output_path = NULL
		WHERE id = ?
	`,
		models.StatusQueued,
		operations,
		videoID,
	)
	if err != nil {
		return nil, fmt.Errorf("queueing video %s: %w", videoID, err)
	}

	if err := tx.Commit(); err != nil {
		return nil, fmt.Errorf("committing job creation: %w", err)
	}

	return s.Get(jobID)
}

func (s *JobStore) Get(id string) (*models.Job, error) {
	row := s.db.QueryRow(`
		SELECT `+jobColumns+`
		FROM jobs
		WHERE id = ?
	`, id)

	job, err := scanJob(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrJobNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("getting job %s: %w", id, err)
	}
	return job, nil
}

func (s *JobStore) List(limit int) ([]models.Job, error) {
	if limit <= 0 || limit > 500 {
		limit = 200
	}

	rows, err := s.db.Query(`
		SELECT `+jobColumns+`
		FROM jobs
		ORDER BY created_at DESC, id DESC
		LIMIT ?
	`, limit)
	if err != nil {
		return nil, fmt.Errorf("listing jobs: %w", err)
	}
	defer rows.Close()

	return collectJobs(rows)
}

func (s *JobStore) ListForVideo(videoID string) ([]models.Job, error) {
	rows, err := s.db.Query(`
		SELECT `+jobColumns+`
		FROM jobs
		WHERE video_id = ?
		ORDER BY created_at DESC, id DESC
	`, videoID)
	if err != nil {
		return nil, fmt.Errorf("listing jobs for video %s: %w", videoID, err)
	}
	defer rows.Close()

	return collectJobs(rows)
}

func (s *JobStore) ListByStatus(statuses ...string) ([]models.Job, error) {
	if len(statuses) == 0 {
		return []models.Job{}, nil
	}

	placeholders := make([]string, len(statuses))
	args := make([]any, len(statuses))
	for i, status := range statuses {
		placeholders[i] = "?"
		args[i] = status
	}

	rows, err := s.db.Query(`
		SELECT `+jobColumns+`
		FROM jobs
		WHERE status IN (`+strings.Join(placeholders, ", ")+`)
		ORDER BY created_at ASC, id ASC
	`, args...)
	if err != nil {
		return nil, fmt.Errorf("listing jobs by status: %w", err)
	}
	defer rows.Close()

	return collectJobs(rows)
}

func collectJobs(rows *sql.Rows) ([]models.Job, error) {
	jobs := make([]models.Job, 0)
	for rows.Next() {
		job, err := scanJob(rows)
		if err != nil {
			return nil, err
		}
		jobs = append(jobs, *job)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return jobs, nil
}

func (s *JobStore) ClaimNext() (*models.Job, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, fmt.Errorf("starting job claim: %w", err)
	}
	defer tx.Rollback()

	row := tx.QueryRow(`
		UPDATE jobs
		SET
			status = ?,
			progress = 0,
			started_at = CURRENT_TIMESTAMP,
			finished_at = NULL,
			cancel_requested_at = NULL,
			output_path = NULL,
			error = NULL
		WHERE id = (
			SELECT id
			FROM jobs
			WHERE status = ?
			ORDER BY created_at ASC, id ASC
			LIMIT 1
		)
		AND status = ?
		RETURNING `+jobColumns,
		models.StatusProcessing,
		models.StatusQueued,
		models.StatusQueued,
	)

	job, err := scanJob(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, sql.ErrNoRows
	}
	if err != nil {
		return nil, fmt.Errorf("claiming queued job: %w", err)
	}

	if _, err := tx.Exec(`
		UPDATE videos
		SET
			status = ?,
			operations = ?,
			progress = 0,
			error = NULL,
			output_path = NULL
		WHERE id = ?
	`,
		models.StatusProcessing,
		job.Operations,
		job.VideoID,
	); err != nil {
		return nil, fmt.Errorf("updating claimed video %s: %w", job.VideoID, err)
	}

	if err := tx.Commit(); err != nil {
		return nil, fmt.Errorf("committing job claim: %w", err)
	}

	return job, nil
}

func (s *JobStore) UpdateProgress(id string, progress float64) error {
	if progress < 0 {
		progress = 0
	}
	if progress > 100 {
		progress = 100
	}

	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	result, err := tx.Exec(`
		UPDATE jobs
		SET progress = ?
		WHERE id = ? AND status = ?
	`, progress, id, models.StatusProcessing)
	if err != nil {
		return fmt.Errorf("updating job progress %s: %w", id, err)
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("updating job progress %s: %w", id, err)
	}
	if affected == 0 {
		return nil
	}

	if _, err := tx.Exec(`
		UPDATE videos
		SET progress = ?
		WHERE id = (SELECT video_id FROM jobs WHERE id = ?)
	`, progress, id); err != nil {
		return fmt.Errorf("updating video progress for job %s: %w", id, err)
	}

	return tx.Commit()
}

// RequestCancel atomically cancels a queued job or requests cancellation of a
// running job. It returns the updated job and whether a running process must be
// interrupted.
func (s *JobStore) RequestCancel(id string) (*models.Job, bool, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback()

	row := tx.QueryRow(`
		UPDATE jobs
		SET
			status = CASE
				WHEN status = 'processing' THEN 'cancel_requested'
				ELSE 'cancelled'
			END,
			cancel_requested_at = CASE
				WHEN status = 'processing' THEN CURRENT_TIMESTAMP
				ELSE cancel_requested_at
			END,
			finished_at = CASE
				WHEN status = 'queued' THEN CURRENT_TIMESTAMP
				ELSE finished_at
			END,
			error = CASE
				WHEN status = 'queued' THEN NULL
				ELSE error
			END
		WHERE id = ? AND status IN ('queued', 'processing')
		RETURNING `+jobColumns,
		id,
	)

	job, err := scanJob(row)
	if errors.Is(err, sql.ErrNoRows) {
		var exists int
		if checkErr := tx.QueryRow("SELECT COUNT(1) FROM jobs WHERE id = ?", id).Scan(&exists); checkErr != nil {
			return nil, false, checkErr
		}
		if exists == 0 {
			return nil, false, ErrJobNotFound
		}
		return nil, false, ErrJobNotCancellable
	}
	if err != nil {
		return nil, false, fmt.Errorf("requesting cancellation for job %s: %w", id, err)
	}

	videoStatus := models.StatusCancelled
	if job.Status == models.StatusCancelRequested {
		videoStatus = models.StatusCancelRequested
	}

	if _, err := tx.Exec(`
		UPDATE videos
		SET
			status = ?,
			progress = CASE WHEN ? = 'cancelled' THEN 0 ELSE progress END,
			output_path = CASE WHEN ? = 'cancelled' THEN NULL ELSE output_path END
		WHERE id = ?
	`, videoStatus, videoStatus, videoStatus, job.VideoID); err != nil {
		return nil, false, fmt.Errorf("updating video cancellation for job %s: %w", id, err)
	}

	if err := tx.Commit(); err != nil {
		return nil, false, fmt.Errorf("committing job cancellation %s: %w", id, err)
	}

	return job, job.Status == models.StatusCancelRequested, nil
}

func (s *JobStore) MarkCancelled(id string) (*models.Job, bool, error) {
	return s.finishTransition(
		id,
		"cancelled",
		`
		UPDATE jobs
		SET
			status = ?,
			finished_at = CURRENT_TIMESTAMP,
			output_path = NULL,
			error = NULL
		WHERE id = ? AND status IN ('queued', 'processing', 'cancel_requested')
	`,
		models.StatusCancelled,
		id,
	)
}

func (s *JobStore) Complete(id, outputPath string) (*models.Job, bool, error) {
	return s.finishTransition(
		id,
		"completed",
		`
		UPDATE jobs
		SET
			status = ?,
			progress = 100,
			output_path = ?,
			error = NULL,
			finished_at = CURRENT_TIMESTAMP
		WHERE id = ? AND status = ?
	`,
		models.StatusCompleted,
		outputPath,
		id,
		models.StatusProcessing,
	)
}

func (s *JobStore) Fail(id, message string) (*models.Job, bool, error) {
	return s.finishTransition(
		id,
		"failed",
		`
		UPDATE jobs
		SET
			status = ?,
			error = ?,
			output_path = NULL,
			finished_at = CURRENT_TIMESTAMP
		WHERE id = ? AND status = ?
	`,
		models.StatusFailed,
		message,
		id,
		models.StatusProcessing,
	)
}

func (s *JobStore) Requeue(id string) (*models.Job, bool, error) {
	return s.finishTransition(
		id,
		"queued",
		`
		UPDATE jobs
		SET
			status = ?,
			progress = 0,
			started_at = NULL,
			finished_at = NULL,
			cancel_requested_at = NULL,
			output_path = NULL,
			error = NULL
		WHERE id = ? AND status = ?
	`,
		models.StatusQueued,
		id,
		models.StatusProcessing,
	)
}

func (s *JobStore) finishTransition(
	id string,
	videoStatus string,
	query string,
	args ...any,
) (*models.Job, bool, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback()

	row := tx.QueryRow(query+` RETURNING `+jobColumns, args...)
	job, err := scanJob(row)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, fmt.Errorf("transitioning job %s: %w", id, err)
	}

	if _, err := tx.Exec(`
		UPDATE videos
		SET
			status = ?,
			progress = ?,
			output_path = CASE WHEN ? = 'completed' THEN ? ELSE NULL END,
			error = CASE WHEN ? = 'failed' THEN ? ELSE NULL END
		WHERE id = ?
	`,
		videoStatus,
		job.Progress,
		videoStatus,
		job.OutputPath,
		videoStatus,
		job.Error,
		job.VideoID,
	); err != nil {
		return nil, false, fmt.Errorf("updating video for job %s: %w", id, err)
	}

	if err := tx.Commit(); err != nil {
		return nil, false, fmt.Errorf("committing job transition %s: %w", id, err)
	}

	return job, true, nil
}

func (s *JobStore) DeleteForVideo(videoID string) error {
	if _, err := s.db.Exec("DELETE FROM jobs WHERE video_id = ?", videoID); err != nil {
		return fmt.Errorf("deleting jobs for video %s: %w", videoID, err)
	}
	return nil
}

func (s *JobStore) Delete(id string) error {
	if _, err := s.db.Exec("DELETE FROM jobs WHERE id = ?", id); err != nil {
		return fmt.Errorf("deleting job %s: %w", id, err)
	}
	return nil
}
