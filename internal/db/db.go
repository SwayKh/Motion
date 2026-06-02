package db

import (
	"database/sql"
	"fmt"

	"VideoProject/internal/logger"

	_ "modernc.org/sqlite"
)

func Open(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("opening db: %w", err)
	}

	if err := setup(db); err != nil {
		return nil, err
	}
	logger.Info.Printf("database opened path=%s", path)

	return db, nil
}

func setup(db *sql.DB) error {
	queries := []string{
		`PRAGMA journal_mode=WAL;`,

		`
        CREATE TABLE IF NOT EXISTS videos (
            id TEXT PRIMARY KEY,

            original_path TEXT NOT NULL,
            output_path TEXT,

            status TEXT NOT NULL,
            progress REAL DEFAULT 0,

            operations TEXT,

            duration REAL,
            width INTEGER,
            height INTEGER,

            error TEXT,

            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
        );
        `,

		`
        CREATE INDEX IF NOT EXISTS idx_videos_status
        ON videos(status);
        `,
	}

	for _, q := range queries {
		if _, err := db.Exec(q); err != nil {
			return fmt.Errorf("setting up db: %w", err)
		}
	}

	return nil
}
