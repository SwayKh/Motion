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

	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/job"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/service"
	"github.com/SwayKh/Motion/internal/validation"
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
		user := getUser(r)
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
			if service.IsUploadClientError(err) {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			writeError(w, http.StatusInternalServerError, "failed to process upload")
			return
		}
		if user != nil {
			_, _ = database.Exec("UPDATE videos SET user_id = ? WHERE id = ?", user.ID, video.ID)
			video.UserID = &user.ID
		}

		writeJSON(w, http.StatusCreated, toVideoResponse(video))
	}
}

func ProcessHandler(database *sql.DB, manager *job.Manager) http.HandlerFunc {
	store := db.NewVideoStore(database)
	jobs := db.NewJobStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		id := r.PathValue("id")

		video, err := store.Get(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if user != nil {
			if video.UserID == nil || *video.UserID != user.ID {
				writeError(w, http.StatusNotFound, "video not found")
				return
			}
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
		user := getUser(r)
		var (
			jobs []models.Job
			err  error
		)

		if videoID := r.URL.Query().Get("videoId"); videoID != "" {
			if user != nil {
				// check video ownership
				var v models.Video
				vrow := database.QueryRow(`SELECT id, user_id FROM videos WHERE id = ?`, videoID)
				var uid sql.NullString
				if err := vrow.Scan(&v.ID, &uid); err != nil {
					writeError(w, http.StatusNotFound, "video not found")
					return
				}
				if !uid.Valid || uid.String != user.ID {
					writeError(w, http.StatusNotFound, "video not found")
					return
				}
			}
			jobs, err = store.ListForVideo(videoID)
		} else {
			if user != nil {
				rows, errq := database.Query(`SELECT id, video_id, user_id, COALESCE(video_name,''), source_path, operations, status, COALESCE(progress,0), COALESCE(output_path,''), COALESCE(error,''), created_at, started_at, finished_at, cancel_requested_at FROM jobs WHERE user_id = ? ORDER BY created_at DESC LIMIT 200`, user.ID)
				if errq != nil {
					logger.Error.Printf("listing jobs: %v", errq)
					writeError(w, http.StatusInternalServerError, "failed to load job history")
					return
				}
				for rows.Next() {
					var job models.Job
					var uid sql.NullString
					var startedAt, finishedAt, cancelRequestedAt sql.NullTime
					if err := rows.Scan(&job.ID, &job.VideoID, &uid, &job.VideoName, &job.SourcePath, &job.Operations, &job.Status, &job.Progress, &job.OutputPath, &job.Error, &job.CreatedAt, &startedAt, &finishedAt, &cancelRequestedAt); err != nil {
						continue
					}
					if startedAt.Valid {
						job.StartedAt = &startedAt.Time
					}
					if finishedAt.Valid {
						job.FinishedAt = &finishedAt.Time
					}
					if cancelRequestedAt.Valid {
						job.CancelRequestedAt = &cancelRequestedAt.Time
					}
					jobs = append(jobs, job)
				}
				rows.Close()
			} else {
				jobs, err = store.List(200)
			}
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
		user := getUser(r)
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
		if user != nil {
			var uid sql.NullString
			if err := database.QueryRow(`SELECT user_id FROM jobs WHERE id = ?`, job.ID).Scan(&uid); err == nil {
				if !uid.Valid || uid.String != user.ID {
					writeError(w, http.StatusNotFound, "job not found")
					return
				}
			} else {
				// fallback check via video
				var vuid sql.NullString
				if err := database.QueryRow(`SELECT user_id FROM videos WHERE id = ?`, job.VideoID).Scan(&vuid); err != nil || !vuid.Valid || vuid.String != user.ID {
					writeError(w, http.StatusNotFound, "job not found")
					return
				}
			}
		}

		writeJSON(w, http.StatusOK, toJobResponse(job))
	}
}

func CancelJobHandler(database *sql.DB, manager *job.Manager) http.HandlerFunc {
	store := db.NewJobStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		id := r.PathValue("id")
		if user != nil {
			// check ownership
			var owned bool
			_ = database.QueryRow(`SELECT EXISTS(SELECT 1 FROM jobs j LEFT JOIN videos v ON v.id=j.video_id WHERE j.id=? AND (j.user_id=? OR v.user_id=?))`, id, user.ID, user.ID).Scan(&owned)
			if !owned {
				writeError(w, http.StatusNotFound, "job not found")
				return
			}
		}

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
		user := getUser(r)
		job, err := store.Get(r.PathValue("id"))
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load job")
			return
		}
		if user != nil {
			var owned bool
			_ = database.QueryRow(`SELECT EXISTS(SELECT 1 FROM jobs j LEFT JOIN videos v ON v.id=j.video_id WHERE j.id=? AND (j.user_id=? OR v.user_id=?))`, job.ID, user.ID, user.ID).Scan(&owned)
			if !owned {
				writeError(w, http.StatusNotFound, "processed output is not available")
				return
			}
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
		user := getUser(r)
		job, err := store.Get(r.PathValue("id"))
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load job")
			return
		}
		if user != nil {
			var owned bool
			_ = database.QueryRow(`SELECT EXISTS(SELECT 1 FROM jobs j LEFT JOIN videos v ON v.id=j.video_id WHERE j.id=? AND (j.user_id=? OR v.user_id=?))`, job.ID, user.ID, user.ID).Scan(&owned)
			if !owned {
				writeError(w, http.StatusNotFound, "processed output is not available")
				return
			}
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
		user := getUser(r)
		id := r.PathValue("id")

		video, err := store.Get(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if user != nil {
			if video.UserID == nil || *video.UserID != user.ID {
				writeError(w, http.StatusNotFound, "video not found")
				return
			}
		}

		writeJSON(w, http.StatusOK, toVideoResponse(video))
	}
}

func ListVideosHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		var videos []models.Video
		var err error
		if user != nil {
			rows, errq := database.Query(`SELECT id, user_id, original_path, output_path, status, progress, operations, duration, width, height, error, created_at, original_name, file_size FROM videos WHERE user_id = ? ORDER BY created_at DESC`, user.ID)
			if errq != nil {
				logger.Error.Printf("listing videos: %v", errq)
				writeError(w, http.StatusInternalServerError, "failed to load video library")
				return
			}
			for rows.Next() {
				var v models.Video
				var userID sql.NullString
				var outputPath, operations, errMsg, originalName sql.NullString
				var progress, duration sql.NullFloat64
				var width, height sql.NullInt64
				var fileSize sql.NullInt64
				if err := rows.Scan(&v.ID, &userID, &v.OriginalPath, &outputPath, &v.Status, &progress, &operations, &duration, &width, &height, &errMsg, &v.CreatedAt, &originalName, &fileSize); err != nil {
					continue
				}
				if userID.Valid {
					u := userID.String
					v.UserID = &u
				}
				v.OutputPath = outputPath.String
				v.Operations = operations.String
				v.Error = errMsg.String
				v.OriginalName = originalName.String
				v.Progress = progress.Float64
				v.Duration = duration.Float64
				v.Width = int(width.Int64)
				v.Height = int(height.Int64)
				v.FileSize = fileSize.Int64
				videos = append(videos, v)
			}
			rows.Close()
		} else {
			videos, err = store.List()
			if err != nil {
				logger.Error.Printf("listing videos: %v", err)
				writeError(w, http.StatusInternalServerError, "failed to load video library")
				return
			}
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
		user := getUser(r)
		video, err := store.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if user != nil {
			if video.UserID == nil || *video.UserID != user.ID {
				writeError(w, http.StatusNotFound, "video not found")
				return
			}
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
		user := getUser(r)
		video, err := store.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if user != nil {
			if video.UserID == nil || *video.UserID != user.ID {
				writeError(w, http.StatusNotFound, "video not found")
				return
			}
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
		user := getUser(r)
		video, err := store.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if user != nil {
			if video.UserID == nil || *video.UserID != user.ID {
				writeError(w, http.StatusNotFound, "video not found")
				return
			}
		}

		if video.Status != models.StatusCompleted || video.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveFile(w, r, video.OutputPath, outputFilename(video), true)
	}
}

func serveFile(w http.ResponseWriter, r *http.Request, path, name string, download bool) {
	clean := filepath.Clean(path)
	// Ensure path is within allowed directories
	abs, err := filepath.Abs(clean)
	if err != nil {
		writeError(w, http.StatusNotFound, "file is no longer available")
		return
	}
	wd, _ := os.Getwd()
	uploadsAbs, _ := filepath.Abs(filepath.Join(wd, "uploads"))
	outputsAbs, _ := filepath.Abs(filepath.Join(wd, "outputs"))
	if !strings.HasPrefix(abs+string(filepath.Separator), uploadsAbs+string(filepath.Separator)) && abs != uploadsAbs &&
		!strings.HasPrefix(abs+string(filepath.Separator), outputsAbs+string(filepath.Separator)) && abs != outputsAbs {
		writeError(w, http.StatusNotFound, "file is no longer available")
		return
	}
	info, err := os.Stat(abs)
	if err != nil || info.IsDir() {
		writeError(w, http.StatusNotFound, "file is no longer available")
		return
	}

	if name == "" {
		name = filepath.Base(abs)
	}
	// Sanitize filename for header
	name = filepath.Base(name)
	disposition := "inline"
	if download {
		disposition = "attachment"
	}
	if value := mime.FormatMediaType(disposition, map[string]string{"filename": name}); value != "" {
		w.Header().Set("Content-Disposition", value)
	}
	http.ServeFile(w, r, abs)
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
