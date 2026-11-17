package db

import "database/sql"

// migrateStorage adds at-rest encryption metadata, the email-verification
// token table and grandfathers existing accounts as verified.
func migrateStorage(tx *sql.Tx) error {
	columns := []struct {
		table  string
		column string
		decl   string
	}{
		{"videos", "encrypted", "INTEGER NOT NULL DEFAULT 0"},
		{"videos", "enc_key", "TEXT"},
		{"videos", "output_encrypted", "INTEGER NOT NULL DEFAULT 0"},
		{"videos", "output_enc_key", "TEXT"},
		{"jobs", "encrypted", "INTEGER NOT NULL DEFAULT 0"},
		{"jobs", "enc_key", "TEXT"},
	}
	for _, c := range columns {
		if err := ensureColumn(tx, c.table, c.column, c.decl); err != nil {
			return err
		}
	}

	queries := []string{
		`
		CREATE TABLE IF NOT EXISTS email_verification_tokens (
			id TEXT PRIMARY KEY,
			token_hash TEXT NOT NULL UNIQUE,
			user_id TEXT NOT NULL,
			expires_at DATETIME NOT NULL,
			used_at DATETIME,
			created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
			FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
		);
		`,
		`CREATE INDEX IF NOT EXISTS idx_email_verification_user_id ON email_verification_tokens(user_id);`,
		`CREATE INDEX IF NOT EXISTS idx_email_verification_token_hash ON email_verification_tokens(token_hash);`,
		// Email verification was never enforced before this migration. Treat
		// every existing account as already verified so enabling the feature
		// later cannot lock anyone out.
		`
		UPDATE users
		SET email_verified = 1,
		    email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP)
		WHERE email_verified = 0;
		`,
	}
	for _, q := range queries {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	return nil
}
