package db

import (
	"database/sql"

	_ "modernc.org/sqlite"
)

func Open(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}

	_, err = db.Exec(`
		PRAGMA journal_mode = WAL;

		CREATE TABLE IF NOT EXISTS videos (
			id TEXT PRIMARY KEY,
			original_path TEXT NOT NULL,
			output_path TEXT,
			status TEXT NOT NULL,
			created_at DATETIME DEFAULT CURRENT_TIMESTAMP
		);
	`)
	if err != nil {
		return nil, err
	}

	// log.Println("database initialized")
	return db, nil
}
