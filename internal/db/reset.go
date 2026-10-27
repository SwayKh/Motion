package db

import (
	"database/sql"
	"errors"
	"time"

	"github.com/SwayKh/Motion/internal/models"
)

var (
	ErrResetTokenNotFound = errors.New("reset token not found")
	ErrResetTokenExpired  = errors.New("reset token expired")
	ErrResetTokenUsed     = errors.New("reset token used")
)

type ResetTokenStore struct {
	db *sql.DB
}

func NewResetTokenStore(db *sql.DB) *ResetTokenStore {
	return &ResetTokenStore{db: db}
}

func (s *ResetTokenStore) Create(token *models.PasswordResetToken) error {
	_, err := s.db.Exec(`
		INSERT INTO password_reset_tokens (id, token_hash, user_id, expires_at, used_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
	`, token.ID, token.TokenHash, token.UserID, token.ExpiresAt, timePtrToNull(token.UsedAt), token.CreatedAt)
	return err
}

func (s *ResetTokenStore) GetValidByTokenHash(tokenHash string) (*models.PasswordResetToken, error) {
	row := s.db.QueryRow(`
		SELECT id, token_hash, user_id, expires_at, used_at, created_at
		FROM password_reset_tokens WHERE token_hash = ?
	`, tokenHash)
	var t models.PasswordResetToken
	var used sql.NullTime
	var createdAt time.Time
	err := row.Scan(&t.ID, &t.TokenHash, &t.UserID, &t.ExpiresAt, &used, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrResetTokenNotFound
	}
	if err != nil {
		return nil, err
	}
	if used.Valid {
		return nil, ErrResetTokenUsed
	}
	if time.Now().After(t.ExpiresAt) {
		return nil, ErrResetTokenExpired
	}
	t.CreatedAt = createdAt
	if used.Valid {
		t.UsedAt = &used.Time
	}
	return &t, nil
}

func (s *ResetTokenStore) MarkUsed(id string) error {
	_, err := s.db.Exec(`UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP WHERE id = ? AND used_at IS NULL`, id)
	return err
}

func (s *ResetTokenStore) DeleteAllForUser(userID string) error {
	_, err := s.db.Exec(`DELETE FROM password_reset_tokens WHERE user_id = ?`, userID)
	return err
}
