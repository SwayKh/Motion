package api

import (
	"database/sql"
	"net/http"
	"path/filepath"
	"time"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/email"
	"github.com/SwayKh/Motion/internal/job"
	"github.com/SwayKh/Motion/internal/rate"
	"github.com/SwayKh/Motion/internal/storage"
)

// NewRouter registers the API and optionally serves the static frontend from
// staticDir. Tests can pass an empty staticDir to exercise only the API.
func NewRouter(database *sql.DB, manager *job.Manager, staticDir string) http.Handler {
	return NewRouterWithConfig(database, manager, staticDir, config.Load(), nil)
}

func NewRouterWithConfig(database *sql.DB, manager *job.Manager, staticDir string, cfg *config.Config, st *storage.Manager) http.Handler {
	if cfg == nil {
		cfg = config.Load()
	}
	deps := NewDeps(database, st, cfg)

	mux := http.NewServeMux()

	// Rate limiters
	loginLimiter := rate.New(cfg.RateLimitLogin, time.Minute)
	regLimiter := rate.New(cfg.RateLimitRegister, time.Minute)
	resetLimiter := rate.New(cfg.RateLimitReset, time.Hour)
	verifyLimiter := rate.New(cfg.RateLimitVerify, time.Hour)

	var sender email.Sender = &email.DevNull{}
	switch {
	case cfg.SMTPHost != "":
		sender = &email.SMTP{
			Host: cfg.SMTPHost,
			Port: cfg.SMTPPort,
			User: cfg.SMTPUser,
			Pass: cfg.SMTPPass,
			From: cfg.SMTPFrom,
		}
	case cfg.DevEmailOutbox:
		sender = email.NewDevOutbox(filepath.Join(cfg.DataDir, "outbox.log"))
	}

	authMw := AuthMiddleware(database, cfg)
	optAuthMw := OptionalAuthMiddleware(database, cfg)
	verified := RequireVerified(cfg)

	// Public/auth endpoints
	mux.HandleFunc("GET /health", HealthHandler)
	mux.HandleFunc("GET /api/config", ConfigHandler(deps))
	mux.HandleFunc("POST /api/auth/register", RegisterHandler(database, cfg, regLimiter, sender))
	mux.HandleFunc("POST /api/auth/login", LoginHandler(database, cfg, loginLimiter))
	mux.HandleFunc("POST /api/auth/logout", LogoutHandler(database, cfg))
	mux.HandleFunc("POST /api/auth/password/reset/request", RequestPasswordResetHandler(database, cfg, resetLimiter, sender))
	mux.HandleFunc("POST /api/auth/password/reset", ResetPasswordHandler(database, cfg))
	mux.HandleFunc("POST /api/auth/verify", VerifyEmailHandler(database))
	mux.HandleFunc("GET /api/auth/csrf", func(w http.ResponseWriter, r *http.Request) {
		ensureCSRFToken(w, cfg.SecureCookies)
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	// Protected auth endpoints
	mux.Handle("GET /api/auth/me", authMw(http.HandlerFunc(MeHandler(database))))
	mux.Handle("POST /api/auth/password/change", authMw(http.HandlerFunc(ChangePasswordHandler(database, cfg))))
	mux.Handle("POST /api/auth/verify/resend", authMw(http.HandlerFunc(ResendVerificationHandler(database, cfg, sender, verifyLimiter))))

	// Protected video/job endpoints. RequireVerified is a no-op unless email
	// verification is enabled.
	mux.Handle("GET /videos", authMw(verified(http.HandlerFunc(ListVideosHandler(deps)))))
	mux.Handle("POST /upload", authMw(verified(http.HandlerFunc(UploadHandler(deps)))))
	mux.Handle("GET /videos/{id}", authMw(verified(http.HandlerFunc(GetVideoHandler(deps)))))
	mux.Handle("GET /videos/{id}/original", authMw(verified(http.HandlerFunc(OriginalHandler(deps)))))
	mux.Handle("GET /videos/{id}/output", authMw(verified(http.HandlerFunc(OutputHandler(deps)))))
	mux.Handle("GET /videos/{id}/download", authMw(verified(http.HandlerFunc(DownloadHandler(deps)))))
	mux.Handle("POST /videos/{id}/process", authMw(verified(http.HandlerFunc(ProcessHandler(deps, manager)))))
	mux.Handle("DELETE /videos/{id}", authMw(verified(http.HandlerFunc(DeleteVideoHandler(deps)))))

	mux.Handle("GET /jobs", authMw(verified(http.HandlerFunc(ListJobsHandler(deps)))))
	mux.Handle("GET /jobs/{id}", authMw(verified(http.HandlerFunc(GetJobHandler(deps)))))
	mux.Handle("POST /jobs/{id}/cancel", authMw(verified(http.HandlerFunc(CancelJobHandler(deps, manager)))))
	mux.Handle("DELETE /jobs/{id}", authMw(verified(http.HandlerFunc(DeleteJobHandler(deps)))))
	mux.Handle("GET /jobs/{id}/output", authMw(verified(http.HandlerFunc(JobOutputHandler(deps)))))
	mux.Handle("GET /jobs/{id}/download", authMw(verified(http.HandlerFunc(JobDownloadHandler(deps)))))

	// Also support API prefixed routes for the new frontend.
	mux.Handle("GET /api/videos", authMw(verified(http.HandlerFunc(ListVideosHandler(deps)))))
	mux.Handle("POST /api/upload", authMw(verified(http.HandlerFunc(UploadHandler(deps)))))
	mux.Handle("GET /api/videos/{id}", authMw(verified(http.HandlerFunc(GetVideoHandler(deps)))))
	mux.Handle("GET /api/videos/{id}/original", authMw(verified(http.HandlerFunc(OriginalHandler(deps)))))
	mux.Handle("GET /api/videos/{id}/output", authMw(verified(http.HandlerFunc(OutputHandler(deps)))))
	mux.Handle("GET /api/videos/{id}/download", authMw(verified(http.HandlerFunc(DownloadHandler(deps)))))
	mux.Handle("POST /api/videos/{id}/process", authMw(verified(http.HandlerFunc(ProcessHandler(deps, manager)))))
	mux.Handle("DELETE /api/videos/{id}", authMw(verified(http.HandlerFunc(DeleteVideoHandler(deps)))))

	// Derived preview assets (thumbnail filmstrips and waveform peaks).
	mux.Handle("GET /api/videos/{id}/thumbnails", authMw(verified(http.HandlerFunc(ThumbnailsHandler(deps)))))
	mux.Handle("GET /api/videos/{id}/thumbnails/sprite", authMw(verified(http.HandlerFunc(ThumbnailSpriteHandler(deps)))))
	mux.Handle("GET /api/videos/{id}/waveform", authMw(verified(http.HandlerFunc(WaveformHandler(deps)))))
	mux.Handle("GET /videos/{id}/thumbnails", authMw(verified(http.HandlerFunc(ThumbnailsHandler(deps)))))
	mux.Handle("GET /videos/{id}/thumbnails/sprite", authMw(verified(http.HandlerFunc(ThumbnailSpriteHandler(deps)))))
	mux.Handle("GET /videos/{id}/waveform", authMw(verified(http.HandlerFunc(WaveformHandler(deps)))))
	mux.Handle("GET /api/jobs", authMw(verified(http.HandlerFunc(ListJobsHandler(deps)))))
	mux.Handle("GET /api/jobs/{id}", authMw(verified(http.HandlerFunc(GetJobHandler(deps)))))
	mux.Handle("POST /api/jobs/{id}/cancel", authMw(verified(http.HandlerFunc(CancelJobHandler(deps, manager)))))
	mux.Handle("DELETE /api/jobs/{id}", authMw(verified(http.HandlerFunc(DeleteJobHandler(deps)))))
	mux.Handle("GET /api/jobs/{id}/output", authMw(verified(http.HandlerFunc(JobOutputHandler(deps)))))
	mux.Handle("GET /api/jobs/{id}/download", authMw(verified(http.HandlerFunc(JobDownloadHandler(deps)))))

	// Reusable edit projects.
	mux.Handle("GET /api/edit-projects", authMw(verified(http.HandlerFunc(ListEditProjectsHandler(deps)))))
	mux.Handle("POST /api/edit-projects", authMw(verified(http.HandlerFunc(CreateEditProjectHandler(deps)))))
	mux.Handle("GET /api/edit-projects/{id}", authMw(verified(http.HandlerFunc(GetEditProjectHandler(deps)))))
	mux.Handle("PUT /api/edit-projects/{id}", authMw(verified(http.HandlerFunc(UpdateEditProjectHandler(deps)))))
	mux.Handle("DELETE /api/edit-projects/{id}", authMw(verified(http.HandlerFunc(DeleteEditProjectHandler(deps)))))

	// Multitrack timeline projects and rendering.
	mux.Handle("GET /api/timeline-projects", authMw(verified(http.HandlerFunc(ListTimelineProjectsHandler(deps)))))
	mux.Handle("POST /api/timeline-projects", authMw(verified(http.HandlerFunc(CreateTimelineProjectHandler(deps)))))
	mux.Handle("GET /api/timeline-projects/{id}", authMw(verified(http.HandlerFunc(GetTimelineProjectHandler(deps)))))
	mux.Handle("PUT /api/timeline-projects/{id}", authMw(verified(http.HandlerFunc(UpdateTimelineProjectHandler(deps)))))
	mux.Handle("DELETE /api/timeline-projects/{id}", authMw(verified(http.HandlerFunc(DeleteTimelineProjectHandler(deps)))))
	mux.Handle("POST /api/timeline/render", authMw(verified(http.HandlerFunc(RenderTimelineHandler(deps, manager)))))

	var handler http.Handler = mux
	handler = CSRFMiddleware(cfg.SecureCookies)(handler)
	handler = optAuthMw(handler) // make user available if logged in
	handler = SecurityHeaders(handler)
	handler = newCORSMiddleware(cfg)(handler)

	if staticDir != "" {
		// Serve static files; the frontend handles client-side routing.
		fileServer := http.FileServer(http.Dir(staticDir))
		mux.Handle("/", fileServer)
	}

	return handler
}
