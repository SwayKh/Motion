package db

import (
	"database/sql"
	"errors"
	"time"

	"VideoProject/internal/models"
)

var (
	ErrSessionNotFound = errors.New("session not found")
	ErrSessionExpired  = errors.New("session expired")
	ErrSessionRevoked  = errors.New("session revoked")
)

type SessionStore struct {
	db          *sql.DB
	idleTTL     time.Duration
	absoluteTTL time.Duration
}

func NewSessionStore(db *sql.DB) *SessionStore {
	return &SessionStore{db: db, idleTTL: 2 * time.Hour, absoluteTTL: 24 * time.Hour}
}

func NewSessionStoreWithConfig(db *sql.DB, idleTTL, absoluteTTL time.Duration) *SessionStore {
	if idleTTL <= 0 {
		idleTTL = 2 * time.Hour
	}
	if absoluteTTL <= 0 {
		absoluteTTL = 24 * time.Hour
	}
	return &SessionStore{db: db, idleTTL: idleTTL, absoluteTTL: absoluteTTL}
}

func (s *SessionStore) Create(sess *models.Session) error {
	_, err := s.db.Exec(`
		INSERT INTO sessions (id, token_hash, user_id, expires_at, last_activity_at, revoked_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	`, sess.ID, sess.TokenHash, sess.UserID, sess.ExpiresAt, timePtrToNull(sess.LastActivityAt), timePtrToNull(sess.RevokedAt), sess.CreatedAt)
	return err
}

func (s *SessionStore) GetValidByTokenHash(tokenHash string) (*models.Session, error) {
	row := s.db.QueryRow(`
		SELECT id, token_hash, user_id, expires_at, last_activity_at, revoked_at, created_at
		FROM sessions WHERE token_hash = ?
	`, tokenHash)
	var sess models.Session
	var lastAct sql.NullTime
	var revoked sql.NullTime
	var createdAt time.Time
	err := row.Scan(&sess.ID, &sess.TokenHash, &sess.UserID, &sess.ExpiresAt, &lastAct, &revoked, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrSessionNotFound
	}
	if err != nil {
		return nil, err
	}
	if revoked.Valid {
		return nil, ErrSessionRevoked
	}
	now := time.Now()
	if now.After(sess.ExpiresAt) {
		return nil, ErrSessionExpired
	}
	// Enforce idle timeout
	idleTTL := s.idleTTL
	if idleTTL <= 0 {
		idleTTL = 2 * time.Hour
	}
	if lastAct.Valid {
		if now.Sub(lastAct.Time) > idleTTL {
			return nil, ErrSessionExpired
		}
		sess.LastActivityAt = &lastAct.Time
	} else {
		if now.Sub(createdAt) > idleTTL {
			return nil, ErrSessionExpired
		}
	}
	sess.CreatedAt = createdAt
	if revoked.Valid {
		sess.RevokedAt = &revoked.Time
	}
	return &sess, nil
}

func (s *SessionStore) Touch(id string) error {
	_, err := s.db.Exec(`UPDATE sessions SET last_activity_at = CURRENT_TIMESTAMP WHERE id = ? AND revoked_at IS NULL`, id)
	return err
}

func (s *SessionStore) Revoke(id string) error {
	_, err := s.db.Exec(`UPDATE sessions SET revoked_at = CURRENT_TIMESTAMP WHERE id = ?`, id)
	return err
}

func (s *SessionStore) RevokeAllForUser(userID string) error {
	_, err := s.db.Exec(`UPDATE sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL`, userID)
	return err
}

func (s *SessionStore) RevokeAllExcept(userID, exceptSessionID string) error {
	_, err := s.db.Exec(`UPDATE sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL AND id != ?`, userID, exceptSessionID)
	return err
}
