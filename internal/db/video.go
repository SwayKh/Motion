package db

import (
	"database/sql"
	"time"

	"VideoProject/internal/models"
)

type Video struct {
	ID string

	OriginalPath string
	OutputPath   string

	Status   string
	Progress float64

	Duration float64
	Width    int
	Height   int

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

func (s *VideoStore) Create(video models.Video) error {
	_, err := s.db.Exec(`
		INSERT INTO videos (
			id,
			original_path,
			status,
			progress,
			duration,
			width,
			height
		)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	`,
		video.ID,
		video.OriginalPath,
		video.Status,
		video.Progress,
		video.Duration,
		video.Width,
		video.Height,
	)

	return err
}

func (s *VideoStore) Get(id string) (*models.Video, error) {
	row := s.db.QueryRow(`
		SELECT
			id,
			original_path,
			output_path,
			status,
			progress,
			duration,
			width,
			height,
			error,
			created_at
		FROM videos
		WHERE id = ?
	`, id)

	var v models.Video

	err := row.Scan(
		&v.ID,
		&v.OriginalPath,
		&v.OutputPath,
		&v.Status,
		&v.Progress,
		&v.Duration,
		&v.Width,
		&v.Height,
		&v.Error,
		&v.CreatedAt,
	)
	if err != nil {
		return nil, err
	}

	return &v, nil
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

	return err
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

	return err
}

func (s *VideoStore) Complete(id string, outputPath string) error {
	_, err := s.db.Exec(`
		UPDATE videos
		SET
			output_path = ?,
			status = ?,
			progress = 100
		WHERE id = ?
	`,
		outputPath,
		models.StatusCompleted,
		id,
	)

	return err
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

	return err
}

func (s *VideoStore) Delete(id string) error {
	_, err := s.db.Exec(`
		DELETE FROM videos
		WHERE id = ?
	`, id)

	return err
}

func (s *VideoStore) ListProcessing() ([]models.Video, error) {
	rows, err := s.db.Query(`
		SELECT
			id,
			original_path,
			output_path,
			status,
			progress,
			duration,
			width,
			height,
			error,
			created_at
		FROM videos
		WHERE status = ?
	`,
		models.StatusProcessing,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var videos []models.Video

	for rows.Next() {
		var v models.Video

		err := rows.Scan(
			&v.ID,
			&v.OriginalPath,
			&v.OutputPath,
			&v.Status,
			&v.Progress,
			&v.Duration,
			&v.Width,
			&v.Height,
			&v.Error,
			&v.CreatedAt,
		)
		if err != nil {
			return nil, err
		}

		videos = append(videos, v)
	}

	return videos, nil
}
