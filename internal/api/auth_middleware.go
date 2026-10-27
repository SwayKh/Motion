package api

import (
	"VideoProject/internal/config"
	"database/sql"
	"net/http"

	"VideoProject/internal/auth"
	"VideoProject/internal/db"
)

const sessionCookieName = "cinecut_session"

func AuthMiddleware(database *sql.DB) func(http.Handler) http.Handler {
	userStore := db.NewUserStore(database)
	cfg := config.Load()
	sessionStore := db.NewSessionStoreWithConfig(database, cfg.SessionIdleTTL, cfg.SessionTTL)

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			cookie, err := r.Cookie(sessionCookieName)
			if err != nil || cookie.Value == "" {
				writeError(w, http.StatusUnauthorized, "unauthorized")
				return
			}

			tokenHash := auth.HashToken(cookie.Value)
			sess, err := sessionStore.GetValidByTokenHash(tokenHash)
			if err != nil {
				writeError(w, http.StatusUnauthorized, "unauthorized")
				return
			}

			user, err := userStore.GetByID(sess.UserID)
			if err != nil {
				writeError(w, http.StatusUnauthorized, "unauthorized")
				return
			}
			if user.Status != "active" {
				writeError(w, http.StatusUnauthorized, "unauthorized")
				return
			}

			// Touch session (best effort)
			_ = sessionStore.Touch(sess.ID)

			r = withUser(r, user)
			r = withSession(r, sess)
			next.ServeHTTP(w, r)
		})
	}
}

func OptionalAuthMiddleware(database *sql.DB) func(http.Handler) http.Handler {
	userStore := db.NewUserStore(database)
	cfg := config.Load()
	sessionStore := db.NewSessionStoreWithConfig(database, cfg.SessionIdleTTL, cfg.SessionTTL)

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			cookie, err := r.Cookie(sessionCookieName)
			if err == nil && cookie.Value != "" {
				tokenHash := auth.HashToken(cookie.Value)
				sess, err := sessionStore.GetValidByTokenHash(tokenHash)
				if err == nil {
					user, err := userStore.GetByID(sess.UserID)
					if err == nil && user.Status == "active" {
						_ = sessionStore.Touch(sess.ID)
						r = withUser(r, user)
						r = withSession(r, sess)
					}
				}
			}
			next.ServeHTTP(w, r)
		})
	}
}
