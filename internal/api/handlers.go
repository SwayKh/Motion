package api

import (
	"database/sql"
	"encoding/json"
	"errors"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"VideoProject/internal/db"
	"VideoProject/internal/job"
	"VideoProject/internal/logger"
	"VideoProject/internal/models"
	"VideoProject/internal/service"
	"VideoProject/internal/validation"
)

const (
	maxUploadSize      = 150 << 20
	maxProcessBodySize = 1 << 20
)

func HealthHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"status": "ok",
	})
}

func UploadHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)
	videoService := service.NewVideoService(store)

	return func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, maxUploadSize)

		if err := r.ParseMultipartForm(32 << 20); err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) {
				writeError(w, http.StatusRequestEntityTooLarge, "file exceeds the 150 MB limit")
				return
			}
			writeError(w, http.StatusBadRequest, "invalid multipart upload")
			return
		}
		if r.MultipartForm != nil {
			defer r.MultipartForm.RemoveAll()
		}

		file, header, err := r.FormFile("video")
		if err != nil {
			writeError(w, http.StatusBadRequest, "missing or invalid 'video' file field")
			return
		}

		video, err := videoService.Upload(file, header)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}

		writeJSON(w, http.StatusCreated, toVideoResponse(video))
	}
}

func ProcessHandler(database *sql.DB, manager *job.Manager) http.HandlerFunc {
	store := db.NewVideoStore(database)
	jobs := db.NewJobStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")

		video, err := store.Get(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		if _, err := os.Stat(video.OriginalPath); err != nil {
			writeError(w, http.StatusConflict, "the original file is no longer available")
			return
		}

		r.Body = http.MaxBytesReader(w, r.Body, maxProcessBodySize)

		var req models.ProcessRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}

		if err := validation.ValidateProcessRequest(video, req); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}

		opsJSON, err := json.Marshal(req)
		if err != nil {
			logger.Error.Printf("marshaling operations for video=%s: %v", id, err)
			writeError(w, http.StatusInternalServerError, "failed to save processing request")
			return
		}

		created, err := jobs.CreateQueued(id, string(opsJSON))
		if errors.Is(err, db.ErrActiveJob) {
			writeError(w, http.StatusConflict, "video already has an active job")
			return
		}
		if err != nil {
			logger.Error.Printf("creating job for video=%s: %v", id, err)
			writeError(w, http.StatusInternalServerError, "failed to queue video")
			return
		}

		if manager != nil {
			manager.Notify()
		}

		writeJSON(w, http.StatusAccepted, toJobResponse(created))
	}
}

func ListJobsHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewJobStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		var (
			jobs []models.Job
			err  error
		)

		if videoID := r.URL.Query().Get("videoId"); videoID != "" {
			jobs, err = store.ListForVideo(videoID)
		} else {
			jobs, err = store.List(200)
		}
		if err != nil {
			logger.Error.Printf("listing jobs: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to load job history")
			return
		}

		response := make([]jobResponse, 0, len(jobs))
		for i := range jobs {
			response = append(response, toJobResponse(&jobs[i]))
		}
		writeJSON(w, http.StatusOK, response)
	}
}

func GetJobHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewJobStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		job, err := store.Get(r.PathValue("id"))
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			logger.Error.Printf("loading job: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to load job")
			return
		}

		writeJSON(w, http.StatusOK, toJobResponse(job))
	}
}

func CancelJobHandler(database *sql.DB, manager *job.Manager) http.HandlerFunc {
	store := db.NewJobStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")

		var (
			job *models.Job
			err error
		)
		if manager != nil {
			job, err = manager.Cancel(id)
		} else {
			job, _, err = store.RequestCancel(id)
		}

		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if errors.Is(err, db.ErrJobNotCancellable) {
			writeError(w, http.StatusConflict, "job has already finished")
			return
		}
		if err != nil {
			logger.Error.Printf("cancelling job=%s: %v", id, err)
			writeError(w, http.StatusInternalServerError, "failed to cancel job")
			return
		}

		writeJSON(w, http.StatusOK, toJobResponse(job))
	}
}

func JobOutputHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewJobStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		job, err := store.Get(r.PathValue("id"))
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load job")
			return
		}

		if job.Status != models.StatusCompleted || job.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveFile(w, r, job.OutputPath, jobOutputFilename(job), false)
	}
}

func JobDownloadHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewJobStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		job, err := store.Get(r.PathValue("id"))
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load job")
			return
		}

		if job.Status != models.StatusCompleted || job.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveFile(w, r, job.OutputPath, jobOutputFilename(job), true)
	}
}

func GetVideoHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")

		video, err := store.Get(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		writeJSON(w, http.StatusOK, toVideoResponse(video))
	}
}

func ListVideosHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		videos, err := store.List()
		if err != nil {
			logger.Error.Printf("listing videos: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to load video library")
			return
		}

		response := make([]videoResponse, 0, len(videos))
		for i := range videos {
			response = append(response, toVideoResponse(&videos[i]))
		}

		writeJSON(w, http.StatusOK, response)
	}
}

func OriginalHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		video, err := store.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		if video.OriginalPath == "" {
			writeError(w, http.StatusNotFound, "original file is not available")
			return
		}

		name := video.OriginalName
		if name == "" {
			name = filepath.Base(video.OriginalPath)
		}

		serveFile(w, r, video.OriginalPath, name, false)
	}
}

func OutputHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		video, err := store.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		if video.Status != models.StatusCompleted || video.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveFile(w, r, video.OutputPath, outputFilename(video), false)
	}
}

func DownloadHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		video, err := store.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		if video.Status != models.StatusCompleted || video.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveFile(w, r, video.OutputPath, outputFilename(video), true)
	}
}

func serveFile(w http.ResponseWriter, r *http.Request, path, name string, download bool) {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		writeError(w, http.StatusNotFound, "file is no longer available")
		return
	}

	if name == "" {
		name = filepath.Base(path)
	}
	disposition := "inline"
	if download {
		disposition = "attachment"
	}
	if value := mime.FormatMediaType(disposition, map[string]string{"filename": name}); value != "" {
		w.Header().Set("Content-Disposition", value)
	}
	http.ServeFile(w, r, path)
}

func outputFilename(video *models.Video) string {
	base := strings.TrimSuffix(video.OriginalName, filepath.Ext(video.OriginalName))
	if base == "" || base == "." {
		base = video.ID
	}

	return base + "-edited" + filepath.Ext(video.OutputPath)
}

func jobOutputFilename(job *models.Job) string {
	base := strings.TrimSuffix(job.VideoName, filepath.Ext(job.VideoName))
	if base == "" || base == "." {
		base = job.ID
	}

	return base + "-edited-" + job.ID[:min(8, len(job.ID))] + filepath.Ext(job.OutputPath)
}
