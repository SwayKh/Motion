package db

import (
	"database/sql"
	"errors"
	"fmt"
	"time"

	"VideoProject/internal/logger"
	"VideoProject/internal/models"
)

type Video struct {
	ID string

	OriginalPath string
	OriginalName string
	OutputPath   string

	Status   string
	Progress float64

	Operations string

	Duration float64
	Width    int
	Height   int
	FileSize int64

	Error string

	CreatedAt time.Time
}

type VideoStore struct {
	db *sql.DB
}

func NewVideoStore(db *sql.DB) *VideoStore {
	return &VideoStore{
		db: db,
	}
}

const videoColumns = `
	id,
	original_path,
	output_path,
	status,
	progress,
	operations,
	duration,
	width,
	height,
	error,
	created_at,
	original_name,
	file_size
`

type rowScanner interface {
	Scan(dest ...any) error
}

func scanVideo(row rowScanner) (*models.Video, error) {
	var (
		v            models.Video
		outputPath   sql.NullString
		operations   sql.NullString
		errMsg       sql.NullString
		originalName sql.NullString
		progress     sql.NullFloat64
		duration     sql.NullFloat64
		width        sql.NullInt64
		height       sql.NullInt64
		fileSize     sql.NullInt64
	)

	err := row.Scan(
		&v.ID,
		&v.OriginalPath,
		&outputPath,
		&v.Status,
		&progress,
		&operations,
		&duration,
		&width,
		&height,
		&errMsg,
		&v.CreatedAt,
		&originalName,
		&fileSize,
	)
	if err != nil {
		return nil, err
	}

	v.OutputPath = outputPath.String
	v.Operations = operations.String
	v.Error = errMsg.String
	v.OriginalName = originalName.String
	v.Progress = progress.Float64
	v.Duration = duration.Float64
	v.Width = int(width.Int64)
	v.Height = int(height.Int64)
	v.FileSize = fileSize.Int64

	return &v, nil
}

func (s *VideoStore) Create(video *models.Video) error {
	_, err := s.db.Exec(`
		INSERT INTO videos (
			id,
			original_path,
			original_name,
			file_size,
			status,
			progress,
			duration,
			width,
			height
		)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
	`,
		video.ID,
		video.OriginalPath,
		video.OriginalName,
		video.FileSize,
		video.Status,
		video.Progress,
		video.Duration,
		video.Width,
		video.Height,
	)
	if err != nil {
		return fmt.Errorf("creating video %s: %w", video.ID, err)
	}

	// Read the database-generated timestamp so callers can return a complete
	// record immediately after creating it.
	if err := s.db.QueryRow(
		"SELECT created_at FROM videos WHERE id = ?",
		video.ID,
	).Scan(&video.CreatedAt); err != nil {
		return fmt.Errorf("loading created video %s: %w", video.ID, err)
	}

	return nil
}

func (s *VideoStore) Get(id string) (*models.Video, error) {
	logger.Info.Println("GET ID:", id)
	row := s.db.QueryRow(`
		SELECT `+videoColumns+`
		FROM videos
		WHERE id = ?
	`, id)

	v, err := scanVideo(row)
	if err != nil {
		return nil, fmt.Errorf(
			"getting video %s: %w",
			id,
			err,
		)
	}

	return v, nil
}

func (s *VideoStore) List() ([]models.Video, error) {
	rows, err := s.db.Query(`
		SELECT ` + videoColumns + `
		FROM videos
		ORDER BY created_at DESC
	`)
	if err != nil {
		return nil, fmt.Errorf("listing videos: %w", err)
	}
	defer rows.Close()

	return collectVideos(rows)
}

func collectVideos(rows *sql.Rows) ([]models.Video, error) {
	videos := make([]models.Video, 0)

	for rows.Next() {
		v, err := scanVideo(rows)
		if err != nil {
			return nil, err
		}
		videos = append(videos, *v)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	return videos, nil
}

func (s *VideoStore) UpdateStatus(id string, status string) error {
	_, err := s.db.Exec(`
		UPDATE videos
		SET status = ?
		WHERE id = ?
	`,
		status,
		id,
	)
	if err != nil {
		return fmt.Errorf("updating status for video %s: %w", id, err)
	}
	return nil
}

func (s *VideoStore) UpdateProgress(id string, progress float64) error {
	_, err := s.db.Exec(`
		UPDATE videos
		SET progress = ?
		WHERE id = ?
	`,
		progress,
		id,
	)
	if err != nil {
		return err
	}
	return nil
}

func (s *VideoStore) Complete(id string, outputPath string) error {
	_, err := s.db.Exec(`
		UPDATE videos
		SET
			output_path = ?,
			status = ?,
			progress = 100,
			error = NULL
		WHERE id = ?
	`,
		outputPath,
		models.StatusCompleted,
		id,
	)
	if err != nil {
		return fmt.Errorf("completing video %s: %w", id, err)
	}
	return nil
}

func (s *VideoStore) Fail(id string, errMsg string) error {
	_, err := s.db.Exec(`
		UPDATE videos
		SET
			status = ?,
			error = ?
		WHERE id = ?
	`,
		models.StatusFailed,
		errMsg,
		id,
	)
	if err != nil {
		return fmt.Errorf("marking video %s failed: %w", id, err)
	}
	return nil
}

func (s *VideoStore) Delete(id string) error {
	_, err := s.db.Exec(`
		DELETE FROM videos
		WHERE id = ?
	`, id)
	if err != nil {
		return fmt.Errorf("deleting video %s: %w", id, err)
	}
	return nil
}

func (s *VideoStore) ListProcessing() ([]models.Video, error) {
	rows, err := s.db.Query(`
		SELECT `+videoColumns+`
		FROM videos
		WHERE status = ?
	`,
		models.StatusProcessing,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	return collectVideos(rows)
}

func (s *VideoStore) ListExpiredVideos(before time.Time) ([]models.Video, error) {
	rows, err := s.db.Query(`
		SELECT `+videoColumns+`
		FROM videos
		WHERE
			created_at < ?
			AND status IN (?, ?, ?)
			AND NOT EXISTS (
				SELECT 1
				FROM jobs
				WHERE jobs.video_id = videos.id
					AND (
						jobs.status NOT IN (?, ?, ?)
						OR jobs.created_at >= ?
						OR jobs.finished_at >= ?
					)
			)
	`,
		before,
		models.StatusCompleted,
		models.StatusFailed,
		models.StatusCancelled,
		models.StatusCompleted,
		models.StatusFailed,
		models.StatusCancelled,
		before,
		before,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	return collectVideos(rows)
}

type CleanupJob struct {
	ID         string
	OutputPath string
}

type CleanupTarget struct {
	VideoID      string
	OriginalPath string
	OutputPath   string
	Jobs         []CleanupJob
}

func (s *VideoStore) DeleteExpiredVideo(id string, before time.Time) (*CleanupTarget, bool, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return nil, false, fmt.Errorf("starting cleanup for video %s: %w", id, err)
	}
	defer tx.Rollback()

	var (
		target     CleanupTarget
		status     string
		createdAt  time.Time
		outputPath sql.NullString
	)
	err = tx.QueryRow(`
		SELECT original_path, output_path, status, created_at
		FROM videos
		WHERE id = ?
	`, id).Scan(&target.OriginalPath, &outputPath, &status, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, fmt.Errorf("loading expired video %s: %w", id, err)
	}
	if !models.IsTerminalJobStatus(status) || !createdAt.Before(before) {
		return nil, false, nil
	}
	target.VideoID = id
	target.OutputPath = outputPath.String

	rows, err := tx.Query(`
		SELECT id, COALESCE(output_path, ''), status, created_at, finished_at
		FROM jobs
		WHERE video_id = ?
	`, id)
	if err != nil {
		return nil, false, fmt.Errorf("loading jobs for expired video %s: %w", id, err)
	}

	blocked := false
	for rows.Next() {
		var (
			job        CleanupJob
			jobStatus  string
			jobCreated time.Time
			finishedAt sql.NullTime
		)
		if err := rows.Scan(&job.ID, &job.OutputPath, &jobStatus, &jobCreated, &finishedAt); err != nil {
			rows.Close()
			return nil, false, fmt.Errorf("scanning jobs for expired video %s: %w", id, err)
		}
		target.Jobs = append(target.Jobs, job)
		if !models.IsTerminalJobStatus(jobStatus) ||
			!jobCreated.Before(before) ||
			(finishedAt.Valid && !finishedAt.Time.Before(before)) {
			blocked = true
		}
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return nil, false, fmt.Errorf("listing jobs for expired video %s: %w", id, err)
	}
	if err := rows.Close(); err != nil {
		return nil, false, fmt.Errorf("closing jobs for expired video %s: %w", id, err)
	}
	if blocked {
		return nil, false, nil
	}

	result, err := tx.Exec("DELETE FROM videos WHERE id = ?", id)
	if err != nil {
		return nil, false, fmt.Errorf("deleting expired video %s: %w", id, err)
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return nil, false, fmt.Errorf("checking deleted video %s: %w", id, err)
	}
	if affected != 1 {
		return nil, false, nil
	}
	if err := tx.Commit(); err != nil {
		return nil, false, fmt.Errorf("committing cleanup for video %s: %w", id, err)
	}

	return &target, true, nil
}

func (s *VideoStore) Queue(id string, operations string) error {
	_, err := s.db.Exec(`
        UPDATE videos
        SET
            status = ?,
            operations = ?,
            progress = 0,
            error = NULL
        WHERE id = ?
    `,
		models.StatusQueued,
		operations,
		id,
	)

	return err
}

// QueueIfUploaded atomically moves a video from uploaded to queued. The
// conditional update prevents two concurrent requests from queueing the same
// video twice.
func (s *VideoStore) QueueIfUploaded(id string, operations string) (bool, error) {
	result, err := s.db.Exec(`
        UPDATE videos
        SET
            status = ?,
            operations = ?,
            progress = 0,
            error = NULL
        WHERE id = ? AND status = ?
    `,
		models.StatusQueued,
		operations,
		id,
		models.StatusUploaded,
	)
	if err != nil {
		return false, fmt.Errorf("queueing video %s: %w", id, err)
	}

	affected, err := result.RowsAffected()
	if err != nil {
		return false, fmt.Errorf("queueing video %s: %w", id, err)
	}

	return affected == 1, nil
}

func (s *VideoStore) ResetToUploaded(id string) error {
	_, err := s.db.Exec(`
        UPDATE videos
        SET
            status = ?,
            operations = NULL,
            progress = 0,
            error = NULL
        WHERE id = ?
    `,
		models.StatusUploaded,
		id,
	)
	if err != nil {
		return fmt.Errorf("resetting video %s: %w", id, err)
	}
	return nil
}

func (s *VideoStore) ListQueued() ([]models.Video, error) {
	rows, err := s.db.Query(`
        SELECT `+videoColumns+`
        FROM videos
        WHERE status = ?
    `, models.StatusQueued)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	return collectVideos(rows)
}
