package db

import (
	"database/sql"
	"errors"
	"time"

	"github.com/SwayKh/Motion/internal/models"
)

var (
	ErrVerificationTokenNotFound = errors.New("verification token not found")
	ErrVerificationTokenExpired  = errors.New("verification token expired")
	ErrVerificationTokenUsed     = errors.New("verification token used")
)

// VerificationTokenStore persists single-use email-verification tokens.
type VerificationTokenStore struct {
	db *sql.DB
}

func NewVerificationTokenStore(db *sql.DB) *VerificationTokenStore {
	return &VerificationTokenStore{db: db}
}

func (s *VerificationTokenStore) Create(token *models.EmailVerificationToken) error {
	_, err := s.db.Exec(`
		INSERT INTO email_verification_tokens (id, token_hash, user_id, expires_at, used_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
	`, token.ID, token.TokenHash, token.UserID, token.ExpiresAt, timePtrToNull(token.UsedAt), token.CreatedAt)
	return err
}

func (s *VerificationTokenStore) GetValidByTokenHash(tokenHash string) (*models.EmailVerificationToken, error) {
	row := s.db.QueryRow(`
		SELECT id, token_hash, user_id, expires_at, used_at, created_at
		FROM email_verification_tokens WHERE token_hash = ?
	`, tokenHash)
	var t models.EmailVerificationToken
	var used sql.NullTime
	var createdAt time.Time
	err := row.Scan(&t.ID, &t.TokenHash, &t.UserID, &t.ExpiresAt, &used, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrVerificationTokenNotFound
	}
	if err != nil {
		return nil, err
	}
	if used.Valid {
		return nil, ErrVerificationTokenUsed
	}
	if time.Now().After(t.ExpiresAt) {
		return nil, ErrVerificationTokenExpired
	}
	t.CreatedAt = createdAt
	return &t, nil
}

// ConsumeAndVerify atomically marks the token used and the owning user
// verified. It refuses to run twice for the same token so a token cannot be
// replayed to re-verify a different state.
func (s *VerificationTokenStore) ConsumeAndVerify(userID, tokenHash string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	result, err := tx.Exec(`
		UPDATE email_verification_tokens
		SET used_at = CURRENT_TIMESTAMP
		WHERE token_hash = ? AND user_id = ? AND used_at IS NULL
	`, tokenHash, userID)
	if err != nil {
		return err
	}
	affected, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if affected == 0 {
		return ErrVerificationTokenUsed
	}

	if _, err := tx.Exec(`
		UPDATE users
		SET email_verified = 1,
		    email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP),
		    updated_at = CURRENT_TIMESTAMP
		WHERE id = ?
	`, userID); err != nil {
		return err
	}

	return tx.Commit()
}

// DeleteAllForUser removes every outstanding token for a user. It is used when
// a new token is issued so only the newest link is valid.
func (s *VerificationTokenStore) DeleteAllForUser(userID string) error {
	_, err := s.db.Exec(`DELETE FROM email_verification_tokens WHERE user_id = ?`, userID)
	return err
}

// DeleteExpired removes tokens that expired or were already used before the
// given time.
func (s *VerificationTokenStore) DeleteExpired(before time.Time) (int64, error) {
	result, err := s.db.Exec(`
		DELETE FROM email_verification_tokens
		WHERE expires_at < ? OR (used_at IS NOT NULL AND used_at < ?)
	`, before, before)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}
