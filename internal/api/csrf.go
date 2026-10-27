package api

import (
	"crypto/rand"
	"encoding/base64"
	"net/http"
)

const csrfCookieName = "cinecut_csrf"
const csrfHeaderName = "X-CSRF-Token"

func ensureCSRFToken(w http.ResponseWriter, secure bool) string {
	token := make([]byte, 32)
	rand.Read(token)
	t := base64.RawURLEncoding.EncodeToString(token)
	http.SetCookie(w, &http.Cookie{
		Name:     csrfCookieName,
		Value:    t,
		Path:     "/",
		HttpOnly: false,
		SameSite: http.SameSiteLaxMode,
		Secure:   secure,
	})
	return t
}

func getCSRFCookie(r *http.Request) string {
	c, err := r.Cookie(csrfCookieName)
	if err != nil {
		return ""
	}
	return c.Value
}

func validateCSRF(r *http.Request) bool {
	if r.Method == http.MethodGet || r.Method == http.MethodHead || r.Method == http.MethodOptions {
		return true
	}
	cookie := getCSRFCookie(r)
	if cookie == "" {
		return false
	}
	header := r.Header.Get(csrfHeaderName)
	if header == "" {
		// also allow form field
		header = r.FormValue("_csrf")
	}
	return header != "" && header == cookie
}

func CSRFMiddleware(secure bool) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method == http.MethodGet || r.Method == http.MethodHead || r.Method == http.MethodOptions {
				// Ensure CSRF cookie exists
				if _, err := r.Cookie(csrfCookieName); err != nil {
					ensureCSRFToken(w, secure)
				}
				next.ServeHTTP(w, r)
				return
			}
			// For state changing requests that use cookie auth, require CSRF
			if _, err := r.Cookie(sessionCookieName); err == nil {
				if !validateCSRF(r) {
					writeError(w, http.StatusForbidden, "invalid csrf token")
					return
				}
			}
			next.ServeHTTP(w, r)
		})
	}
}
