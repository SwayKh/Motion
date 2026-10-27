package db

import (
	"database/sql"
	"fmt"

	"github.com/SwayKh/Motion/internal/logger"

	_ "modernc.org/sqlite"
)

func Open(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("opening db: %w", err)
	}

	db.SetMaxOpenConns(1)
	db.SetMaxIdleConns(1)

	if err := setup(db); err != nil {
		db.Close()
		return nil, err
	}
	logger.Info.Printf("database opened path=%s", path)

	return db, nil
}

type migration struct {
	version int
	apply   func(*sql.Tx) error
}

func setup(db *sql.DB) error {
	pragmas := []string{
		`PRAGMA journal_mode=WAL;`,
		`PRAGMA busy_timeout=5000;`,
		`PRAGMA foreign_keys=ON;`,
	}
	for _, query := range pragmas {
		if _, err := db.Exec(query); err != nil {
			return fmt.Errorf("configuring sqlite: %w", err)
		}
	}

	if _, err := db.Exec(`
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version INTEGER PRIMARY KEY,
			applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
		);
	`); err != nil {
		return fmt.Errorf("creating schema migrations: %w", err)
	}

	migrations := []migration{
		{version: 1, apply: migrateVideos},
		{version: 2, apply: migrateJobs},
		{version: 3, apply: migrateAuth},
		{version: 4, apply: migrateOwnership},
	}

	for _, migration := range migrations {
		if err := applyMigration(db, migration); err != nil {
			return err
		}
	}

	return nil
}

func applyMigration(db *sql.DB, migration migration) error {
	var applied int
	if err := db.QueryRow(
		"SELECT COUNT(1) FROM schema_migrations WHERE version = ?",
		migration.version,
	).Scan(&applied); err != nil {
		return fmt.Errorf("checking schema migration %d: %w", migration.version, err)
	}
	if applied > 0 {
		return nil
	}

	tx, err := db.Begin()
	if err != nil {
		return fmt.Errorf("starting schema migration %d: %w", migration.version, err)
	}
	defer tx.Rollback()

	if err := migration.apply(tx); err != nil {
		return fmt.Errorf("running schema migration %d: %w", migration.version, err)
	}
	if _, err := tx.Exec(
		"INSERT INTO schema_migrations (version) VALUES (?)",
		migration.version,
	); err != nil {
		return fmt.Errorf("recording schema migration %d: %w", migration.version, err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("committing schema migration %d: %w", migration.version, err)
	}

	return nil
}

func migrateVideos(tx *sql.Tx) error {
	queries := []string{
		`
        CREATE TABLE IF NOT EXISTS videos (
            id TEXT PRIMARY KEY,

            original_path TEXT NOT NULL,
            original_name TEXT,
            file_size INTEGER,
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

	for _, query := range queries {
		if _, err := tx.Exec(query); err != nil {
			return err
		}
	}

	// Lightweight migrations for databases created before these columns
	// existed. Existing rows keep working; new uploads fill them in.
	columns := []struct {
		column string
		decl   string
	}{
		{"original_name", "TEXT"},
		{"file_size", "INTEGER"},
	}

	for _, column := range columns {
		if err := ensureColumn(tx, "videos", column.column, column.decl); err != nil {
			return err
		}
	}

	return nil
}

func migrateJobs(tx *sql.Tx) error {
	queries := []string{
		`
        CREATE TABLE IF NOT EXISTS jobs (
            id TEXT PRIMARY KEY,
            video_id TEXT NOT NULL,

            video_name TEXT,
            source_path TEXT NOT NULL,
            operations TEXT NOT NULL,

            status TEXT NOT NULL,
            progress REAL NOT NULL DEFAULT 0,

            output_path TEXT,
            error TEXT,

            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            started_at DATETIME,
            finished_at DATETIME,
            cancel_requested_at DATETIME,

            FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE
        );
        `,
		`CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);`,
		`CREATE INDEX IF NOT EXISTS idx_jobs_video ON jobs(video_id);`,
		`CREATE INDEX IF NOT EXISTS idx_jobs_created ON jobs(created_at);`,
		`
        CREATE UNIQUE INDEX IF NOT EXISTS idx_jobs_one_active_per_video
        ON jobs(video_id)
        WHERE status IN ('queued', 'processing', 'cancel_requested');
        `,
		`
        INSERT INTO jobs (
            id,
            video_id,
            video_name,
            source_path,
            operations,
            status,
            progress,
            output_path,
            error,
            created_at,
            started_at,
            finished_at,
            cancel_requested_at
        )
        SELECT
            'legacy-' || id,
            id,
            original_name,
            original_path,
            COALESCE(NULLIF(operations, ''), '{"operations":[]}'),
            status,
            COALESCE(progress, 0),
            output_path,
            error,
            COALESCE(created_at, CURRENT_TIMESTAMP),
            CASE WHEN status IN ('processing', 'completed', 'failed') THEN COALESCE(created_at, CURRENT_TIMESTAMP) END,
            CASE WHEN status IN ('completed', 'failed') THEN COALESCE(created_at, CURRENT_TIMESTAMP) END,
            CASE WHEN status = 'cancel_requested' THEN COALESCE(created_at, CURRENT_TIMESTAMP) END
        FROM videos
        WHERE status IN ('queued', 'processing', 'cancel_requested', 'cancelled', 'completed', 'failed')
          AND NOT EXISTS (
              SELECT 1 FROM jobs WHERE jobs.video_id = videos.id
          );
        `,
	}

	for _, query := range queries {
		if _, err := tx.Exec(query); err != nil {
			return err
		}
	}

	return nil
}

type queryExecer interface {
	Query(string, ...any) (*sql.Rows, error)
	Exec(string, ...any) (sql.Result, error)
}

func ensureColumn(db queryExecer, table, column, declaration string) error {
	rows, err := db.Query("PRAGMA table_info(" + table + ")")
	if err != nil {
		return fmt.Errorf("inspecting table %s: %w", table, err)
	}

	found := false
	for rows.Next() {
		var (
			cid        int
			name       string
			ctype      string
			notNull    int
			defaultV   sql.NullString
			primaryKey int
		)

		if err := rows.Scan(&cid, &name, &ctype, &notNull, &defaultV, &primaryKey); err != nil {
			rows.Close()
			return fmt.Errorf("inspecting table %s: %w", table, err)
		}
		if name == column {
			found = true
		}
	}
	if err := rows.Close(); err != nil {
		return fmt.Errorf("inspecting table %s: %w", table, err)
	}
	if err := rows.Err(); err != nil {
		return fmt.Errorf("inspecting table %s: %w", table, err)
	}

	if found {
		return nil
	}

	_, err = db.Exec(fmt.Sprintf(
		"ALTER TABLE %s ADD COLUMN %s %s",
		table,
		column,
		declaration,
	))
	if err != nil {
		return fmt.Errorf("adding column %s.%s: %w", table, column, err)
	}

	return nil
}
