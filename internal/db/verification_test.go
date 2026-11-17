package db

import (
	"database/sql"
	"errors"
	"testing"
	"time"

	"github.com/SwayKh/Motion/internal/models"
)

func insertTestUser(t *testing.T, database *sql.DB, id string) {
	t.Helper()

	now := time.Now()
	if _, err := database.Exec(`
		INSERT INTO users (id, email, normalized_email, password_hash, role, status, email_verified, created_at, updated_at)
		VALUES (?, ?, ?, ?, 'user', 'active', 0, ?, ?)
	`, id, id+"@example.com", id+"@example.com", "hash", now, now); err != nil {
		t.Fatalf("insert test user: %v", err)
	}
}

func TestVerificationTokenLifecycle(t *testing.T) {
	database := openTestDB(t)
	insertTestUser(t, database, "user-1")

	store := NewVerificationTokenStore(database)
	users := NewUserStore(database)
	now := time.Now()

	token := &models.EmailVerificationToken{
		ID:        "tok-1",
		TokenHash: "hash-1",
		UserID:    "user-1",
		ExpiresAt: now.Add(time.Hour),
		CreatedAt: now,
	}
	if err := store.Create(token); err != nil {
		t.Fatalf("create token: %v", err)
	}

	got, err := store.GetValidByTokenHash("hash-1")
	if err != nil {
		t.Fatalf("get valid token: %v", err)
	}
	if got.UserID != "user-1" {
		t.Fatalf("token user = %q, want user-1", got.UserID)
	}

	if err := store.ConsumeAndVerify("user-1", "hash-1"); err != nil {
		t.Fatalf("consume token: %v", err)
	}

	user, err := users.GetByID("user-1")
	if err != nil {
		t.Fatalf("reload user: %v", err)
	}
	if !user.EmailVerified {
		t.Fatal("user was not marked verified")
	}
	if user.EmailVerifiedAt == nil {
		t.Fatal("verified timestamp was not recorded")
	}

	if _, err := store.GetValidByTokenHash("hash-1"); !errors.Is(err, ErrVerificationTokenUsed) {
		t.Fatalf("used token error = %v, want ErrVerificationTokenUsed", err)
	}
	if err := store.ConsumeAndVerify("user-1", "hash-1"); !errors.Is(err, ErrVerificationTokenUsed) {
		t.Fatalf("replay error = %v, want ErrVerificationTokenUsed", err)
	}
}

func TestVerificationTokenExpiredAndMissing(t *testing.T) {
	database := openTestDB(t)
	insertTestUser(t, database, "user-2")

	store := NewVerificationTokenStore(database)
	now := time.Now()
	if err := store.Create(&models.EmailVerificationToken{
		ID:        "tok-2",
		TokenHash: "hash-2",
		UserID:    "user-2",
		ExpiresAt: now.Add(-time.Hour),
		CreatedAt: now.Add(-2 * time.Hour),
	}); err != nil {
		t.Fatalf("create expired token: %v", err)
	}

	if _, err := store.GetValidByTokenHash("hash-2"); !errors.Is(err, ErrVerificationTokenExpired) {
		t.Fatalf("expired token error = %v, want ErrVerificationTokenExpired", err)
	}
	if _, err := store.GetValidByTokenHash("missing"); !errors.Is(err, ErrVerificationTokenNotFound) {
		t.Fatalf("missing token error = %v, want ErrVerificationTokenNotFound", err)
	}
}

func TestVerificationTokenCleanup(t *testing.T) {
	database := openTestDB(t)
	insertTestUser(t, database, "user-3")

	store := NewVerificationTokenStore(database)
	now := time.Now()
	if err := store.Create(&models.EmailVerificationToken{
		ID: "active", TokenHash: "hash-active", UserID: "user-3",
		ExpiresAt: now.Add(time.Hour), CreatedAt: now,
	}); err != nil {
		t.Fatalf("create active token: %v", err)
	}
	if err := store.Create(&models.EmailVerificationToken{
		ID: "expired", TokenHash: "hash-expired", UserID: "user-3",
		ExpiresAt: now.Add(-time.Hour), CreatedAt: now.Add(-2 * time.Hour),
	}); err != nil {
		t.Fatalf("create expired token: %v", err)
	}

	removed, err := store.DeleteExpired(now)
	if err != nil {
		t.Fatalf("delete expired: %v", err)
	}
	if removed != 1 {
		t.Fatalf("removed = %d, want 1", removed)
	}

	if err := store.DeleteAllForUser("user-3"); err != nil {
		t.Fatalf("delete all for user: %v", err)
	}
	if _, err := store.GetValidByTokenHash("hash-active"); !errors.Is(err, ErrVerificationTokenNotFound) {
		t.Fatalf("active token error = %v, want ErrVerificationTokenNotFound", err)
	}
}
