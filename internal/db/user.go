package db

import (
	"database/sql"
	"errors"
	"time"

	"github.com/SwayKh/Motion/internal/models"
)

var (
	ErrUserNotFound = errors.New("user not found")
	ErrUserExists   = errors.New("user exists")
	ErrUserDisabled = errors.New("user disabled")
)

type UserStore struct {
	db *sql.DB
}

func NewUserStore(db *sql.DB) *UserStore {
	return &UserStore{db: db}
}

func (s *UserStore) Create(user *models.User) error {
	if _, err := s.db.Exec(`
		INSERT INTO users (id, email, normalized_email, password_hash, role, status, email_verified, email_verified_at, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`, user.ID, user.Email, user.NormalizedEmail, user.PasswordHash, user.Role, user.Status, boolToInt(user.EmailVerified), timePtrToNull(user.EmailVerifiedAt), user.CreatedAt, user.UpdatedAt); err != nil {
		return err
	}
	return nil
}

func (s *UserStore) GetByNormalizedEmail(normalized string) (*models.User, error) {
	row := s.db.QueryRow(`
		SELECT id, email, normalized_email, password_hash, role, status, email_verified, email_verified_at, created_at, updated_at
		FROM users WHERE normalized_email = ?
	`, normalized)
	return scanUser(row)
}

func (s *UserStore) GetByID(id string) (*models.User, error) {
	row := s.db.QueryRow(`
		SELECT id, email, normalized_email, password_hash, role, status, email_verified, email_verified_at, created_at, updated_at
		FROM users WHERE id = ?
	`, id)
	return scanUser(row)
}

func scanUser(row *sql.Row) (*models.User, error) {
	var u models.User
	var emailVerified int
	var emailVerifiedAt sql.NullTime
	var createdAt, updatedAt time.Time
	err := row.Scan(&u.ID, &u.Email, &u.NormalizedEmail, &u.PasswordHash, &u.Role, &u.Status, &emailVerified, &emailVerifiedAt, &createdAt, &updatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}
	u.EmailVerified = emailVerified == 1
	if emailVerifiedAt.Valid {
		u.EmailVerifiedAt = &emailVerifiedAt.Time
	}
	u.CreatedAt = createdAt
	u.UpdatedAt = updatedAt
	return &u, nil
}

func (s *UserStore) UpdatePassword(userID, passwordHash string) error {
	res, err := s.db.Exec(`UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?`, passwordHash, userID)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrUserNotFound
	}
	return nil
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func timePtrToNull(t *time.Time) interface{} {
	if t == nil {
		return nil
	}
	return *t
}
