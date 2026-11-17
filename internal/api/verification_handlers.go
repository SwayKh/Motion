package api

import (
	"database/sql"
	"net/http"
	"time"

	"github.com/SwayKh/Motion/internal/auth"
	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/email"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/rate"

	"github.com/google/uuid"
)

// issueVerificationEmail creates a fresh single-use verification token for a
// user (invalidating any previous one) and emails the confirmation link.
func issueVerificationEmail(database *sql.DB, cfg *config.Config, sender email.Sender, user *models.User) error {
	store := db.NewVerificationTokenStore(database)
	if err := store.DeleteAllForUser(user.ID); err != nil {
		return err
	}

	token, err := auth.GenerateToken()
	if err != nil {
		return err
	}
	now := time.Now()
	record := &models.EmailVerificationToken{
		ID:        uuid.NewString(),
		TokenHash: auth.HashToken(token),
		UserID:    user.ID,
		ExpiresAt: now.Add(cfg.EmailVerificationTTL),
		CreatedAt: now,
	}
	if err := store.Create(record); err != nil {
		return err
	}

	if sender != nil {
		link := cfg.BaseURL + "/verify?token=" + token
		_ = sender.Send(
			user.Email,
			"Verify your Motion account",
			"Confirm your email address by opening this link:\n"+link+"\n\nIf you did not create a Motion account you can ignore this message.",
		)
	}
	return nil
}

// VerifyEmailHandler consumes a verification token and marks the owning account
// verified.
func VerifyEmailHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVerificationTokenStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Token string `json:"token"`
		}
		if err := decodeJSONBody(w, r, &req); err != nil {
			return
		}
		if req.Token == "" {
			writeError(w, http.StatusBadRequest, "a verification token is required")
			return
		}

		hash := auth.HashToken(req.Token)
		token, err := store.GetValidByTokenHash(hash)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid or expired verification link")
			return
		}
		if err := store.ConsumeAndVerify(token.UserID, hash); err != nil {
			writeError(w, http.StatusBadRequest, "invalid or expired verification link")
			return
		}

		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

// ResendVerificationHandler issues a new verification link for the signed-in
// account. It is a no-op for already-verified accounts.
func ResendVerificationHandler(database *sql.DB, cfg *config.Config, sender email.Sender, limiter *rate.Limiter) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if user.EmailVerified {
			writeJSON(w, http.StatusOK, map[string]string{"status": "already_verified"})
			return
		}
		if limiter != nil && !limiter.Allow(rate.ClientIP(r, cfg.TrustedProxies)) {
			writeError(w, http.StatusTooManyRequests, "too many requests")
			return
		}

		if err := issueVerificationEmail(database, cfg, sender, user); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to send verification email")
			return
		}

		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

// RequireVerified blocks access to a route until the account has verified its
// email address. It is a no-op unless REQUIRE_EMAIL_VERIFICATION is enabled.
func RequireVerified(cfg *config.Config) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		if cfg == nil || !cfg.RequireEmailVerification {
			return next
		}
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			user := getUser(r)
			if user == nil {
				writeError(w, http.StatusUnauthorized, "unauthorized")
				return
			}
			if !user.EmailVerified {
				writeError(w, http.StatusForbidden, "verify your email address to continue")
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
