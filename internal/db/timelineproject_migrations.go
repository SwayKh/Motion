package db

import "database/sql"

// migrateTimelineProjects adds the multitrack timeline store. A timeline
// project records the full track/clip layout as JSON plus a monotonic revision
// used for optimistic concurrency control, so two editors cannot silently
// overwrite each other's work.
func migrateTimelineProjects(tx *sql.Tx) error {
	queries := []string{
		`
		CREATE TABLE IF NOT EXISTS timeline_projects (
			id TEXT PRIMARY KEY,
			user_id TEXT NOT NULL,
			name TEXT NOT NULL,
			project TEXT NOT NULL,
			revision INTEGER NOT NULL DEFAULT 1,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
		);
		`,
		`CREATE INDEX IF NOT EXISTS idx_timeline_projects_user_id ON timeline_projects(user_id);`,
	}
	for _, q := range queries {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	return nil
}
