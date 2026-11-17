package db

import (
	"database/sql"
)

func migrateAuth(tx *sql.Tx) error {
	queries := []string{
		`
		CREATE TABLE IF NOT EXISTS users (
			id TEXT PRIMARY KEY,
			email TEXT NOT NULL,
			normalized_email TEXT NOT NULL UNIQUE,
			password_hash TEXT NOT NULL,
			role TEXT NOT NULL DEFAULT 'user',
			status TEXT NOT NULL DEFAULT 'active',
			email_verified INTEGER NOT NULL DEFAULT 0,
			email_verified_at DATETIME,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
		);
		`,
		`CREATE INDEX IF NOT EXISTS idx_users_normalized_email ON users(normalized_email);`,
		`
		CREATE TABLE IF NOT EXISTS sessions (
			id TEXT PRIMARY KEY,
			token_hash TEXT NOT NULL UNIQUE,
			user_id TEXT NOT NULL,
			expires_at DATETIME NOT NULL,
			last_activity_at DATETIME,
			revoked_at DATETIME,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
		);
		`,
		`CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id);`,
		`CREATE INDEX IF NOT EXISTS idx_sessions_token_hash ON sessions(token_hash);`,
		`
		CREATE TABLE IF NOT EXISTS password_reset_tokens (
			id TEXT PRIMARY KEY,
			token_hash TEXT NOT NULL UNIQUE,
			user_id TEXT NOT NULL,
			expires_at DATETIME NOT NULL,
			used_at DATETIME,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
		);
		`,
		`CREATE INDEX IF NOT EXISTS idx_password_reset_user_id ON password_reset_tokens(user_id);`,
		`CREATE INDEX IF NOT EXISTS idx_password_reset_token_hash ON password_reset_tokens(token_hash);`,
	}
	for _, q := range queries {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	return nil
}

func migrateOwnership(tx *sql.Tx) error {
	if err := ensureColumn(tx, "videos", "user_id", "TEXT"); err != nil {
		return err
	}
	if err := ensureColumn(tx, "jobs", "user_id", "TEXT"); err != nil {
		return err
	}
	// Create indexes for ownership queries
	if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS idx_videos_user_id ON videos(user_id);`); err != nil {
		return err
	}
	if _, err := tx.Exec(`CREATE INDEX IF NOT EXISTS idx_jobs_user_id ON jobs(user_id);`); err != nil {
		return err
	}
	return nil
}
