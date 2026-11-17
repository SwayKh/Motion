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
	"time"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/job"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/preview"
	"github.com/SwayKh/Motion/internal/quota"
	"github.com/SwayKh/Motion/internal/service"
	"github.com/SwayKh/Motion/internal/storage"
	"github.com/SwayKh/Motion/internal/validation"
)

const (
	maxUploadSize      = 150 << 20
	maxProcessBodySize = 1 << 20
)

// Deps bundles the shared dependencies of the HTTP handlers. Building it once
// per router keeps the individual handlers small and makes the storage and
// quota configuration explicit.
type Deps struct {
	DB       *sql.DB
	Storage  *storage.Manager
	Cfg      *config.Config
	Videos   *db.VideoStore
	Jobs     *db.JobStore
	Projects *db.EditProjectStore
	Timeline *db.TimelineProjectStore
	Previews *preview.Manager
	Service  *service.VideoService
}

// NewDeps builds the handler dependencies. A nil storage or config falls back to
// the development defaults so tests stay concise.
func NewDeps(database *sql.DB, st *storage.Manager, cfg *config.Config) *Deps {
	if st == nil {
		st = storage.Default()
	}
	if cfg == nil {
		cfg = config.Load()
	}
	videos := db.NewVideoStore(database)
	return &Deps{
		DB:       database,
		Storage:  st,
		Cfg:      cfg,
		Videos:   videos,
		Jobs:     db.NewJobStore(database),
		Projects: db.NewEditProjectStore(database),
		Timeline: db.NewTimelineProjectStore(database),
		Previews: preview.New(cfg.PreviewDir),
		Service:  service.NewVideoService(videos, st, quota.New(database, cfg), cfg),
	}
}

func HealthHandler(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"status": "ok",
	})
}

func UploadHandler(d *Deps) http.HandlerFunc {
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

		userID := ""
		if user != nil {
			userID = user.ID
		}

		video, err := d.Service.Upload(file, header, userID)
		if err != nil {
			if service.IsUploadClientError(err) {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			logger.Error.Printf("upload failed: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to process upload")
			return
		}

		writeJSON(w, http.StatusCreated, toVideoResponse(video))
	}
}

func ProcessHandler(d *Deps, manager *job.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		id := r.PathValue("id")

		video, err := d.Videos.Get(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if !videoAccessible(video, user) {
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

		// Referenced clips and audio tracks must belong to the same account.
		for _, refID := range validation.ReferencedVideoIDs(id, req) {
			ref, err := d.Videos.Get(refID)
			if err != nil {
				writeError(w, http.StatusBadRequest, "a referenced video is not available")
				return
			}
			if !videoAccessible(ref, user) {
				writeError(w, http.StatusBadRequest, "a referenced video is not available")
				return
			}
		}

		opsJSON, err := json.Marshal(req)
		if err != nil {
			logger.Error.Printf("marshaling operations for video=%s: %v", id, err)
			writeError(w, http.StatusInternalServerError, "failed to save processing request")
			return
		}

		created, err := d.Jobs.CreateQueuedLimited(
			id,
			string(opsJSON),
			d.Cfg.MaxConcurrentJobsPerUser,
			d.Cfg.MaxGlobalConcurrentJobs,
		)
		if errors.Is(err, db.ErrActiveJob) {
			writeError(w, http.StatusConflict, "video already has an active job")
			return
		}
		if errors.Is(err, db.ErrQuotaExceeded) {
			writeError(w, http.StatusTooManyRequests, "too many active jobs; try again later")
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

func ListJobsHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		var (
			jobs []models.Job
			err  error
		)

		if videoID := r.URL.Query().Get("videoId"); videoID != "" {
			if user != nil {
				video, verr := d.Videos.Get(videoID)
				if verr != nil || !videoAccessible(video, user) {
					writeError(w, http.StatusNotFound, "video not found")
					return
				}
			}
			jobs, err = d.Jobs.ListForVideo(videoID)
		} else if user != nil {
			jobs, err = d.Jobs.ListByUser(user.ID)
		} else {
			jobs, err = d.Jobs.List(200)
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

func GetJobHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		job, err := d.Jobs.Get(r.PathValue("id"))
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			logger.Error.Printf("loading job: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to load job")
			return
		}
		if !d.jobAccessible(job.ID, user) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}

		writeJSON(w, http.StatusOK, toJobResponse(job))
	}
}

func CancelJobHandler(d *Deps, manager *job.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		id := r.PathValue("id")
		if !d.jobAccessible(id, user) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}

		var (
			job *models.Job
			err error
		)
		if manager != nil {
			job, err = manager.Cancel(id)
		} else {
			job, _, err = d.Jobs.RequestCancel(id)
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

func JobOutputHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		job, err := d.Jobs.Get(r.PathValue("id"))
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load job")
			return
		}
		if !d.jobAccessible(job.ID, user) {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		if job.Status != models.StatusCompleted || job.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveStoredFile(w, r, d, job.OutputPath, jobOutputFilename(job), job.Encrypted, job.EncKey, false)
	}
}

func JobDownloadHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		job, err := d.Jobs.Get(r.PathValue("id"))
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load job")
			return
		}
		if !d.jobAccessible(job.ID, user) {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		if job.Status != models.StatusCompleted || job.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveStoredFile(w, r, d, job.OutputPath, jobOutputFilename(job), job.Encrypted, job.EncKey, true)
	}
}

func GetVideoHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		id := r.PathValue("id")

		video, err := d.Videos.Get(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if !videoAccessible(video, user) {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		writeJSON(w, http.StatusOK, toVideoResponse(video))
	}
}

func ListVideosHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		var (
			videos []models.Video
			err    error
		)
		if user != nil {
			videos, err = d.Videos.ListByUser(user.ID)
		} else {
			videos, err = d.Videos.List()
		}
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

func OriginalHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		video, err := d.Videos.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if !videoAccessible(video, user) {
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

		serveStoredFile(w, r, d, video.OriginalPath, name, video.Encrypted, video.EncKey, false)
	}
}

func OutputHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		video, err := d.Videos.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if !videoAccessible(video, user) {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		if video.Status != models.StatusCompleted || video.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveStoredFile(w, r, d, video.OutputPath, outputFilename(video), video.OutputEncrypted, video.OutputEncKey, false)
	}
}

func DownloadHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		video, err := d.Videos.Get(r.PathValue("id"))
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if !videoAccessible(video, user) {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		if video.Status != models.StatusCompleted || video.OutputPath == "" {
			writeError(w, http.StatusNotFound, "processed output is not available")
			return
		}

		serveStoredFile(w, r, d, video.OutputPath, outputFilename(video), video.OutputEncrypted, video.OutputEncKey, true)
	}
}

// DeleteVideoHandler removes a video, its stored files and its job history. It
// refuses while a job is still active so it can never race a running FFmpeg
// process.
func DeleteVideoHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		id := r.PathValue("id")

		video, err := d.Videos.Get(id)
		if err != nil {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if !videoAccessible(video, user) {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}

		jobs, err := d.Jobs.ListForVideo(id)
		if err != nil {
			logger.Error.Printf("loading jobs for deletion video=%s: %v", id, err)
			writeError(w, http.StatusInternalServerError, "failed to delete video")
			return
		}
		for i := range jobs {
			if models.IsActiveJobStatus(jobs[i].Status) {
				writeError(w, http.StatusConflict, "cancel the active job before deleting this video")
				return
			}
		}

		for i := range jobs {
			job.RemoveArtifacts(&jobs[i], d.Storage)
		}
		_ = d.Storage.Remove(video.OriginalPath)
		_ = d.Storage.Remove(video.OutputPath)
		if d.Previews != nil {
			d.Previews.RemoveForVideo(id)
		}

		if err := d.Jobs.DeleteForVideo(id); err != nil {
			logger.Error.Printf("deleting jobs for video=%s: %v", id, err)
			writeError(w, http.StatusInternalServerError, "failed to delete video")
			return
		}
		if err := d.Videos.Delete(id); err != nil {
			logger.Error.Printf("deleting video=%s: %v", id, err)
			writeError(w, http.StatusInternalServerError, "failed to delete video")
			return
		}

		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

// DeleteJobHandler removes a finished job and its output file.
func DeleteJobHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		id := r.PathValue("id")

		jobRecord, err := d.Jobs.Get(id)
		if errors.Is(err, db.ErrJobNotFound) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if err != nil {
			logger.Error.Printf("loading job for deletion: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to delete job")
			return
		}
		if !d.jobAccessible(jobRecord.ID, user) {
			writeError(w, http.StatusNotFound, "job not found")
			return
		}
		if models.IsActiveJobStatus(jobRecord.Status) {
			writeError(w, http.StatusConflict, "cancel the job before deleting it")
			return
		}

		job.RemoveArtifacts(jobRecord, d.Storage)
		if err := d.Jobs.DeleteAndResetVideo(id); err != nil {
			logger.Error.Printf("deleting job=%s: %v", id, err)
			writeError(w, http.StatusInternalServerError, "failed to delete job")
			return
		}

		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

// serveStoredFile streams a stored file, transparently decrypting it when
// needed. It validates the path against the managed storage directories and
// uses http.ServeContent so range requests (video seeking) keep working.
func serveStoredFile(w http.ResponseWriter, r *http.Request, d *Deps, path, name string, encrypted bool, key string, download bool) {
	if path == "" || !d.Storage.Contains(path) {
		writeError(w, http.StatusNotFound, "file is no longer available")
		return
	}

	reader, err := d.Storage.OpenReader(path, key, encrypted)
	if err != nil {
		if os.IsNotExist(err) {
			writeError(w, http.StatusNotFound, "file is no longer available")
			return
		}
		logger.Error.Printf("opening stored file: %v", err)
		writeError(w, http.StatusInternalServerError, "failed to read file")
		return
	}
	defer reader.Close()

	if name == "" {
		name = filepath.Base(path)
	}
	name = filepath.Base(name)
	disposition := "inline"
	if download {
		disposition = "attachment"
	}
	if value := mime.FormatMediaType(disposition, map[string]string{"filename": name}); value != "" {
		w.Header().Set("Content-Disposition", value)
	}

	http.ServeContent(w, r, name, time.Time{}, reader)
}

// videoAccessible reports whether a user may access a video. A nil user means
// anonymous access is allowed (only reachable when auth is disabled).
func videoAccessible(video *models.Video, user *models.User) bool {
	if user == nil {
		return true
	}
	return video != nil && video.UserID != nil && *video.UserID == user.ID
}

func (d *Deps) jobAccessible(jobID string, user *models.User) bool {
	if user == nil {
		return true
	}
	var owned bool
	_ = d.DB.QueryRow(`
		SELECT EXISTS(
			SELECT 1 FROM jobs j
			LEFT JOIN videos v ON v.id = j.video_id
			WHERE j.id = ? AND (j.user_id = ? OR v.user_id = ?)
		)
	`, jobID, user.ID, user.ID).Scan(&owned)
	return owned
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
