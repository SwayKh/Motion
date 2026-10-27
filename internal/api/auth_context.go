package api

import (
	"context"
	"net/http"

	"github.com/SwayKh/Motion/internal/models"
)

type contextKey string

const (
	userContextKey    contextKey = "user"
	sessionContextKey contextKey = "session"
)

func withUser(r *http.Request, u *models.User) *http.Request {
	ctx := context.WithValue(r.Context(), userContextKey, u)
	return r.WithContext(ctx)
}

func withSession(r *http.Request, s *models.Session) *http.Request {
	ctx := context.WithValue(r.Context(), sessionContextKey, s)
	return r.WithContext(ctx)
}

func getUser(r *http.Request) *models.User {
	if v := r.Context().Value(userContextKey); v != nil {
		if u, ok := v.(*models.User); ok {
			return u
		}
	}
	return nil
}

func getSession(r *http.Request) *models.Session {
	if v := r.Context().Value(sessionContextKey); v != nil {
		if s, ok := v.(*models.Session); ok {
			return s
		}
	}
	return nil
}
