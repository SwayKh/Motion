package db

import (
	"database/sql"
	"time"
)

type Video struct {
	ID           string
	OriginalPath string
	OutputPath   sql.NullString
	Status       string
	CreatedAt    time.Time
}

func CreateVideo(db *sql.DB, v Video) error {
	_, err := db.Exec(`INSERT INTO videos ( id, original_path, status) VALUES (?, ?, ?)`,
		v.ID,
		v.OriginalPath,
		v.Status,
	)
	return err
}

func GetVideo(db *sql.DB, id string) (*Video, error) {
	row := db.QueryRow(`SELECT id, original_path, output_path, status, created_at
		                FROM videos
		                WHERE id = ?`, id)

	var v Video

	err := row.Scan(
		&v.ID,
		&v.OriginalPath,
		&v.OutputPath,
		&v.Status,
		&v.CreatedAt,
	)
	if err != nil {
		return nil, err
	}

	return &v, nil
}

func CompleteVideo(db *sql.DB, id string, outputPath string) error {
	_, err := db.Exec(`UPDATE videos
		               SET output_path = ?, status = 'completed'
		               WHERE id = ?`, outputPath, id)

	return err
}
