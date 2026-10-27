package api

import (
	"net/http"
	"net/url"
	"strings"

	"VideoProject/internal/config"
)

func newCORSMiddleware(cfg *config.Config) func(http.Handler) http.Handler {
	trusted := map[string]bool{}
	for _, t := range cfg.TrustedOrigins {
		t = strings.TrimSpace(t)
		if t == "" {
			continue
		}
		// Normalize
		if !strings.Contains(t, "://") {
			// try to be flexible
			trusted[t] = true
			continue
		}
		if u, err := url.Parse(t); err == nil && u.Scheme != "" && u.Host != "" {
			trusted[u.Scheme+"://"+u.Host] = true
		} else {
			trusted[t] = true
		}
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origin == "" {
				// Same-origin or non-browser requests
				if r.Method == http.MethodOptions {
					w.WriteHeader(http.StatusOK)
					return
				}
				next.ServeHTTP(w, r)
				return
			}
			// Normalize origin
			norm := origin
			if u, err := url.Parse(origin); err == nil && u.Scheme != "" && u.Host != "" {
				norm = u.Scheme + "://" + u.Host
			}
			if trusted[norm] || trusted[origin] {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-CSRF-Token")
				w.Header().Set("Access-Control-Allow-Credentials", "true")
				if r.Method == http.MethodOptions {
					w.WriteHeader(http.StatusOK)
					return
				}
				next.ServeHTTP(w, r)
				return
			}
			// Untrusted origin: do not allow credentials or permissive headers
			if r.Method == http.MethodOptions {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
