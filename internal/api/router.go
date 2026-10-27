package api

import (
	"database/sql"
	"net/http"
	"time"

	"VideoProject/internal/config"
	"VideoProject/internal/email"
	"VideoProject/internal/job"
	"VideoProject/internal/rate"
)

// NewRouter registers the API and optionally serves the static frontend from
// staticDir. Tests can pass an empty staticDir to exercise only the API.
func NewRouter(database *sql.DB, manager *job.Manager, staticDir string) http.Handler {
	return NewRouterWithConfig(database, manager, staticDir, config.Load())
}

func NewRouterWithConfig(database *sql.DB, manager *job.Manager, staticDir string, cfg *config.Config) http.Handler {
	mux := http.NewServeMux()

	// Rate limiters
	loginLimiter := rate.New(cfg.RateLimitLogin, time.Minute)
	regLimiter := rate.New(cfg.RateLimitRegister, time.Minute)
	resetLimiter := rate.New(cfg.RateLimitReset, time.Hour)

	var sender email.Sender = &email.DevNull{}
	if cfg.SMTPHost != "" {
		sender = &email.SMTP{
			Host: cfg.SMTPHost,
			Port: cfg.SMTPPort,
			User: cfg.SMTPUser,
			Pass: cfg.SMTPPass,
			From: cfg.SMTPFrom,
		}
	}

	authMw := AuthMiddleware(database)
	optAuthMw := OptionalAuthMiddleware(database)

	// Public/auth endpoints
	mux.HandleFunc("GET /health", HealthHandler)
	mux.HandleFunc("POST /api/auth/register", RegisterHandler(database, cfg, regLimiter))
	mux.HandleFunc("POST /api/auth/login", LoginHandler(database, cfg, loginLimiter))
	mux.HandleFunc("POST /api/auth/logout", LogoutHandler(database, cfg))
	mux.HandleFunc("POST /api/auth/password/reset/request", RequestPasswordResetHandler(database, cfg, resetLimiter, sender))
	mux.HandleFunc("POST /api/auth/password/reset", ResetPasswordHandler(database, cfg))
	mux.HandleFunc("GET /api/auth/csrf", func(w http.ResponseWriter, r *http.Request) {
		if _, err := r.Cookie(csrfCookieName); err != nil {
			ensureCSRFToken(w, cfg.SecureCookies)
		} else {
			// Refresh or ensure
			ensureCSRFToken(w, cfg.SecureCookies)
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	// Protected auth endpoints
	mux.Handle("GET /api/auth/me", authMw(http.HandlerFunc(MeHandler(database))))
	mux.Handle("POST /api/auth/password/change", authMw(http.HandlerFunc(ChangePasswordHandler(database, cfg))))

	// Protected video/job endpoints
	mux.Handle("GET /videos", authMw(http.HandlerFunc(ListVideosHandler(database))))
	mux.Handle("POST /upload", authMw(http.HandlerFunc(UploadHandler(database))))
	mux.Handle("GET /videos/{id}", authMw(http.HandlerFunc(GetVideoHandler(database))))
	mux.Handle("GET /videos/{id}/original", authMw(http.HandlerFunc(OriginalHandler(database))))
	mux.Handle("GET /videos/{id}/output", authMw(http.HandlerFunc(OutputHandler(database))))
	mux.Handle("GET /videos/{id}/download", authMw(http.HandlerFunc(DownloadHandler(database))))
	mux.Handle("POST /videos/{id}/process", authMw(http.HandlerFunc(ProcessHandler(database, manager))))

	mux.Handle("GET /jobs", authMw(http.HandlerFunc(ListJobsHandler(database))))
	mux.Handle("GET /jobs/{id}", authMw(http.HandlerFunc(GetJobHandler(database))))
	mux.Handle("POST /jobs/{id}/cancel", authMw(http.HandlerFunc(CancelJobHandler(database, manager))))
	mux.Handle("GET /jobs/{id}/output", authMw(http.HandlerFunc(JobOutputHandler(database))))
	mux.Handle("GET /jobs/{id}/download", authMw(http.HandlerFunc(JobDownloadHandler(database))))

	// Also support API prefixed routes for new frontend
	mux.Handle("GET /api/videos", authMw(http.HandlerFunc(ListVideosHandler(database))))
	mux.Handle("POST /api/upload", authMw(http.HandlerFunc(UploadHandler(database))))
	mux.Handle("GET /api/videos/{id}", authMw(http.HandlerFunc(GetVideoHandler(database))))
	mux.Handle("GET /api/videos/{id}/original", authMw(http.HandlerFunc(OriginalHandler(database))))
	mux.Handle("GET /api/videos/{id}/output", authMw(http.HandlerFunc(OutputHandler(database))))
	mux.Handle("GET /api/videos/{id}/download", authMw(http.HandlerFunc(DownloadHandler(database))))
	mux.Handle("POST /api/videos/{id}/process", authMw(http.HandlerFunc(ProcessHandler(database, manager))))
	mux.Handle("GET /api/jobs", authMw(http.HandlerFunc(ListJobsHandler(database))))
	mux.Handle("GET /api/jobs/{id}", authMw(http.HandlerFunc(GetJobHandler(database))))
	mux.Handle("POST /api/jobs/{id}/cancel", authMw(http.HandlerFunc(CancelJobHandler(database, manager))))
	mux.Handle("GET /api/jobs/{id}/output", authMw(http.HandlerFunc(JobOutputHandler(database))))
	mux.Handle("GET /api/jobs/{id}/download", authMw(http.HandlerFunc(JobDownloadHandler(database))))

	var handler http.Handler = mux
	handler = CSRFMiddleware(cfg.SecureCookies)(handler)
	handler = optAuthMw(handler) // make user available if logged in
	handler = SecurityHeaders(handler)
	handler = CORS(handler)

	if staticDir != "" {
		// Serve landing page at root for unauthenticated? Or serve app - requirement says public landing page
		// We'll serve static files; frontend will handle routing
		fileServer := http.FileServer(http.Dir(staticDir))
		mux.Handle("/", fileServer)
	}

	return handler
}
