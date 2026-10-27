package api

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"VideoProject/internal/auth"
	"VideoProject/internal/config"
	"VideoProject/internal/db"
	"VideoProject/internal/email"
	"VideoProject/internal/models"
	"VideoProject/internal/rate"

	"github.com/google/uuid"
)

func setSessionCookie(w http.ResponseWriter, token string, secure bool, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   secure,
		MaxAge:   maxAge,
	})
}

func clearSessionCookie(w http.ResponseWriter, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     sessionCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   secure,
		MaxAge:   -1,
	})
}

func RegisterHandler(database *sql.DB, cfg *config.Config, regLimiter *rate.Limiter) http.HandlerFunc {
	userStore := db.NewUserStore(database)
	sessionStore := db.NewSessionStoreWithConfig(database, cfg.SessionIdleTTL, cfg.SessionTTL)

	return func(w http.ResponseWriter, r *http.Request) {
		if regLimiter != nil {
			if !regLimiter.Allow(rate.KeyFromRequest(r)) {
				writeError(w, http.StatusTooManyRequests, "too many requests")
				return
			}
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxProcessBodySize)
		var req struct {
			Email           string `json:"email"`
			Password        string `json:"password"`
			PasswordConfirm string `json:"passwordConfirm"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}
		req.Email = strings.TrimSpace(req.Email)
		if req.Password != req.PasswordConfirm {
			writeError(w, http.StatusBadRequest, "passwords do not match")
			return
		}
		if !auth.ValidateEmail(req.Email) {
			writeError(w, http.StatusBadRequest, "invalid email address")
			return
		}
		norm := auth.NormalizeEmail(req.Email)
		if _, err := userStore.GetByNormalizedEmail(norm); err == nil {
			writeError(w, http.StatusConflict, "an account with this email already exists")
			return
		} else if err != db.ErrUserNotFound {
			writeError(w, http.StatusInternalServerError, "failed to create account")
			return
		}

		hash, err := auth.HashPassword(req.Password)
		if err != nil {
			writeError(w, http.StatusBadRequest, "password does not meet policy requirements (minimum 8 characters, at least one letter and one digit)")
			return
		}

		now := time.Now()
		user := &models.User{
			ID:              uuid.NewString(),
			Email:           req.Email,
			NormalizedEmail: norm,
			PasswordHash:    hash,
			Role:            models.UserRoleUser,
			Status:          models.UserStatusActive,
			EmailVerified:   false,
			CreatedAt:       now,
			UpdatedAt:       now,
		}
		if err := userStore.Create(user); err != nil {
			if strings.Contains(strings.ToLower(err.Error()), "unique") {
				writeError(w, http.StatusConflict, "an account with this email already exists")
				return
			}
			writeError(w, http.StatusInternalServerError, "failed to create account")
			return
		}

		// Create session
		token, err := auth.GenerateToken()
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create session")
			return
		}
		sess := &models.Session{
			ID:        uuid.NewString(),
			TokenHash: auth.HashToken(token),
			UserID:    user.ID,
			ExpiresAt: time.Now().Add(cfg.SessionTTL),
			CreatedAt: time.Now(),
		}
		if err := sessionStore.Create(sess); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create session")
			return
		}
		setSessionCookie(w, token, cfg.SecureCookies, int(cfg.SessionTTL.Seconds()))

		writeJSON(w, http.StatusCreated, map[string]any{
			"user": map[string]any{
				"id":    user.ID,
				"email": user.Email,
			},
		})
	}
}

func LoginHandler(database *sql.DB, cfg *config.Config, loginLimiter *rate.Limiter) http.HandlerFunc {
	userStore := db.NewUserStore(database)
	sessionStore := db.NewSessionStoreWithConfig(database, cfg.SessionIdleTTL, cfg.SessionTTL)

	return func(w http.ResponseWriter, r *http.Request) {
		if loginLimiter != nil {
			if !loginLimiter.Allow(rate.KeyFromRequest(r)) {
				writeError(w, http.StatusTooManyRequests, "too many requests")
				return
			}
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxProcessBodySize)
		var req struct {
			Email    string `json:"email"`
			Password string `json:"password"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}
		norm := auth.NormalizeEmail(req.Email)
		user, err := userStore.GetByNormalizedEmail(norm)
		if err != nil {
			// Generic error
			writeError(w, http.StatusUnauthorized, "invalid email or password")
			return
		}
		if user.Status != models.UserStatusActive {
			writeError(w, http.StatusUnauthorized, "invalid email or password")
			return
		}
		if err := auth.CheckPassword(req.Password, user.PasswordHash); err != nil {
			writeError(w, http.StatusUnauthorized, "invalid email or password")
			return
		}

		// Rotate session - revoke existing sessions
		_ = sessionStore.RevokeAllForUser(user.ID)

		token, err := auth.GenerateToken()
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create session")
			return
		}
		sess := &models.Session{
			ID:        uuid.NewString(),
			TokenHash: auth.HashToken(token),
			UserID:    user.ID,
			ExpiresAt: time.Now().Add(cfg.SessionTTL),
			CreatedAt: time.Now(),
		}
		if err := sessionStore.Create(sess); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to create session")
			return
		}
		setSessionCookie(w, token, cfg.SecureCookies, int(cfg.SessionTTL.Seconds()))

		writeJSON(w, http.StatusOK, map[string]any{
			"user": map[string]any{
				"id":    user.ID,
				"email": user.Email,
			},
		})
	}
}

func LogoutHandler(database *sql.DB, cfg *config.Config) http.HandlerFunc {
	sessionStore := db.NewSessionStoreWithConfig(database, cfg.SessionIdleTTL, cfg.SessionTTL)

	return func(w http.ResponseWriter, r *http.Request) {
		cookie, err := r.Cookie(sessionCookieName)
		if err == nil && cookie.Value != "" {
			tokenHash := auth.HashToken(cookie.Value)
			// Try to find session by hash
			if sess, err := sessionStore.GetValidByTokenHash(tokenHash); err == nil {
				_ = sessionStore.Revoke(sess.ID)
			}
		}
		clearSessionCookie(w, cfg.SecureCookies)
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

func MeHandler(database *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"user": map[string]any{
				"id":            user.ID,
				"email":         user.Email,
				"role":          user.Role,
				"emailVerified": user.EmailVerified,
			},
		})
	}
}

func ChangePasswordHandler(database *sql.DB, cfg *config.Config) http.HandlerFunc {
	userStore := db.NewUserStore(database)
	sessionStore := db.NewSessionStoreWithConfig(database, cfg.SessionIdleTTL, cfg.SessionTTL)

	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxProcessBodySize)
		var req struct {
			CurrentPassword    string `json:"currentPassword"`
			NewPassword        string `json:"newPassword"`
			NewPasswordConfirm string `json:"newPasswordConfirm"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}
		if req.NewPassword != req.NewPasswordConfirm {
			writeError(w, http.StatusBadRequest, "passwords do not match")
			return
		}
		if err := auth.CheckPassword(req.CurrentPassword, user.PasswordHash); err != nil {
			writeError(w, http.StatusUnauthorized, "invalid current password")
			return
		}
		hash, err := auth.HashPassword(req.NewPassword)
		if err != nil {
			writeError(w, http.StatusBadRequest, "password does not meet policy requirements (minimum 8 characters, at least one letter and one digit)")
			return
		}
		if err := userStore.UpdatePassword(user.ID, hash); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to update password")
			return
		}
		// Revoke all sessions except maybe current? Requirement says "revoke existing sessions after a password change" - revoke all
		sess := getSession(r)
		if sess != nil {
			_ = sessionStore.RevokeAllExcept(user.ID, sess.ID)
		} else {
			_ = sessionStore.RevokeAllForUser(user.ID)
		}

		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

func RequestPasswordResetHandler(database *sql.DB, cfg *config.Config, resetLimiter *rate.Limiter, sender email.Sender) http.HandlerFunc {
	userStore := db.NewUserStore(database)
	resetStore := db.NewResetTokenStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		if resetLimiter != nil {
			if !resetLimiter.Allow(rate.KeyFromRequest(r)) {
				writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
				return
			}
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxProcessBodySize)
		var req struct {
			Email string `json:"email"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}
		norm := auth.NormalizeEmail(req.Email)
		user, err := userStore.GetByNormalizedEmail(norm)
		if err == nil {
			token, err := auth.GenerateToken()
			if err == nil {
				reset := &models.PasswordResetToken{
					ID:        uuid.NewString(),
					TokenHash: auth.HashToken(token),
					UserID:    user.ID,
					ExpiresAt: time.Now().Add(15 * time.Minute),
					CreatedAt: time.Now(),
				}
				_ = resetStore.Create(reset)
				// Try to send email if configured
				if sender != nil {
					_ = sender.Send(user.Email, "Password reset", "Use this token to reset your password: "+token+"\nOr visit: "+cfg.BaseURL+"/reset?token="+token)
				}
				// In dev/test without email, do NOT expose token in API response
			}
		}
		// Always return generic response
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

func ResetPasswordHandler(database *sql.DB, cfg *config.Config) http.HandlerFunc {
	resetStore := db.NewResetTokenStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, maxProcessBodySize)
		var req struct {
			Token              string `json:"token"`
			NewPassword        string `json:"newPassword"`
			NewPasswordConfirm string `json:"newPasswordConfirm"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}
		if req.NewPassword != req.NewPasswordConfirm {
			writeError(w, http.StatusBadRequest, "passwords do not match")
			return
		}
		if req.Token == "" {
			writeError(w, http.StatusBadRequest, "invalid token")
			return
		}
		tokenHash := auth.HashToken(req.Token)
		// Find token to get userID
		reset, err := resetStore.GetValidByTokenHash(tokenHash)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid or expired token")
			return
		}
		hash, err := auth.HashPassword(req.NewPassword)
		if err != nil {
			writeError(w, http.StatusBadRequest, "password does not meet policy requirements (minimum 8 characters, at least one letter and one digit)")
			return
		}
		if err := resetStore.ConsumeTokenAndResetPassword(reset.UserID, tokenHash, hash); err != nil {
			writeError(w, http.StatusBadRequest, "invalid or expired token")
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}
