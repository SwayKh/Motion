package api

import (
	"database/sql"
	"net/http"

	"VideoProject/internal/job"
)

// NewRouter registers the API and optionally serves the static frontend from
// staticDir. Tests can pass an empty staticDir to exercise only the API.
func NewRouter(database *sql.DB, manager *job.Manager, staticDir string) http.Handler {
	mux := http.NewServeMux()

	if staticDir != "" {
		mux.Handle("/", http.FileServer(http.Dir(staticDir)))
	}

	mux.HandleFunc("GET /health", HealthHandler)
	mux.HandleFunc("GET /videos", ListVideosHandler(database))
	mux.HandleFunc("POST /upload", UploadHandler(database))
	mux.HandleFunc("GET /videos/{id}", GetVideoHandler(database))
	mux.HandleFunc("GET /videos/{id}/original", OriginalHandler(database))
	mux.HandleFunc("GET /videos/{id}/output", OutputHandler(database))
	mux.HandleFunc("GET /videos/{id}/download", DownloadHandler(database))
	mux.HandleFunc("POST /videos/{id}/process", ProcessHandler(database, manager))

	mux.HandleFunc("GET /jobs", ListJobsHandler(database))
	mux.HandleFunc("GET /jobs/{id}", GetJobHandler(database))
	mux.HandleFunc("POST /jobs/{id}/cancel", CancelJobHandler(database, manager))
	mux.HandleFunc("GET /jobs/{id}/output", JobOutputHandler(database))
	mux.HandleFunc("GET /jobs/{id}/download", JobDownloadHandler(database))

	return CORS(mux)
}
