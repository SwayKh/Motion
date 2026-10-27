package models

import (
	"time"
)

type User struct {
	ID              string
	Email           string
	NormalizedEmail string
	PasswordHash    string
	Role            string
	Status          string
	EmailVerified   bool
	EmailVerifiedAt *time.Time
	CreatedAt       time.Time
	UpdatedAt       time.Time
}

const (
	UserRoleUser       = "user"
	UserRoleAdmin      = "admin"
	UserStatusActive   = "active"
	UserStatusDisabled = "disabled"
)

type Session struct {
	ID             string
	TokenHash      string
	UserID         string
	ExpiresAt      time.Time
	LastActivityAt *time.Time
	RevokedAt      *time.Time
	CreatedAt      time.Time
}

type PasswordResetToken struct {
	ID        string
	TokenHash string
	UserID    string
	ExpiresAt time.Time
	UsedAt    *time.Time
	CreatedAt time.Time
}
