package db

import "database/sql"

// migrateEditProjects adds the reusable edit-project store. A project records
// the source video and the operations so an editing session can be re-opened
// and re-applied without rebuilding it from scratch.
func migrateEditProjects(tx *sql.Tx) error {
	queries := []string{
		`
		CREATE TABLE IF NOT EXISTS edit_projects (
			id TEXT PRIMARY KEY,
			user_id TEXT NOT NULL,
			video_id TEXT NOT NULL,
			name TEXT NOT NULL,
			request TEXT NOT NULL,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
			FOREIGN KEY (video_id) REFERENCES videos(id) ON DELETE CASCADE
		);
		`,
		`CREATE INDEX IF NOT EXISTS idx_edit_projects_user_id ON edit_projects(user_id);`,
		`CREATE INDEX IF NOT EXISTS idx_edit_projects_video_id ON edit_projects(video_id);`,
	}
	for _, q := range queries {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	return nil
}
