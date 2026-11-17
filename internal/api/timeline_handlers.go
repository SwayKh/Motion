package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"strings"

	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/job"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/timeline"
)

// saveFatalCodes are the validation problems that prevent a project from being
// persisted at all. Softer problems (overlaps, empty ranges) are stored and
// reported back so an editor never loses in-progress work.
var saveFatalCodes = map[string]struct{}{
	"missing_name":       {},
	"name_too_long":      {},
	"unsupported_schema": {},
	"too_many_tracks":    {},
	"too_many_clips":     {},
}

type timelineValidationResponse struct {
	Error            string                    `json:"error"`
	ValidationErrors timeline.ValidationErrors `json:"validationErrors,omitempty"`
}

func writeTimelineValidationError(w http.ResponseWriter, status int, errs timeline.ValidationErrors) {
	writeJSON(w, status, timelineValidationResponse{
		Error:            errs.Error(),
		ValidationErrors: errs,
	})
}

// timelineAssetResolver returns a resolver that only exposes assets owned by the
// requesting user, so a crafted project can never reach another account's media.
func (d *Deps) timelineAssetResolver(user *models.User) timeline.AssetResolver {
	return func(id string) (timeline.Asset, bool) {
		if strings.TrimSpace(id) == "" {
			return timeline.Asset{}, false
		}
		video, err := d.Videos.Get(id)
		if err != nil || !videoAccessible(video, user) {
			return timeline.Asset{}, false
		}
		return timeline.Asset{
			ID:       video.ID,
			Duration: video.Duration,
			HasVideo: video.OriginalPath != "",
			// Audio stream presence is verified by the renderer, which is the
			// only place that can probe the file cheaply.
			HasAudio: true,
		}, true
	}
}

// decodeTimelineProject decodes and normalises a project body, writing the
// error response itself on failure.
func decodeTimelineProject(w http.ResponseWriter, r *http.Request) (*timeline.Project, bool) {
	var project timeline.Project
	if err := decodeJSONBody(w, r, &project); err != nil {
		return nil, false
	}
	project.Normalize()
	return &project, true
}

func CreateTimelineProjectHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		project, ok := decodeTimelineProject(w, r)
		if !ok {
			return
		}

		if errs := fatalErrors(timeline.ValidateStructure(project)); errs != nil {
			writeTimelineValidationError(w, http.StatusBadRequest, errs)
			return
		}

		project.UserID = user.ID
		if err := d.Timeline.Create(project); err != nil {
			logger.Error.Printf("creating timeline project: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to save timeline")
			return
		}

		writeJSON(w, http.StatusCreated, project)
	}
}

func ListTimelineProjectsHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		projects, err := d.Timeline.List(user.ID)
		if err != nil {
			logger.Error.Printf("listing timeline projects: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to load timelines")
			return
		}
		writeJSON(w, http.StatusOK, projects)
	}
}

func GetTimelineProjectHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		project, err := d.Timeline.Get(r.PathValue("id"), user.ID)
		if errors.Is(err, db.ErrTimelineProjectNotFound) {
			writeError(w, http.StatusNotFound, "timeline not found")
			return
		}
		if err != nil {
			logger.Error.Printf("loading timeline project: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to load timeline")
			return
		}
		writeJSON(w, http.StatusOK, project)
	}
}

func UpdateTimelineProjectHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		project, ok := decodeTimelineProject(w, r)
		if !ok {
			return
		}

		if errs := fatalErrors(timeline.ValidateStructure(project)); errs != nil {
			writeTimelineValidationError(w, http.StatusBadRequest, errs)
			return
		}

		project.ID = r.PathValue("id")
		project.UserID = user.ID
		if err := d.Timeline.Update(project); err != nil {
			switch {
			case errors.Is(err, db.ErrTimelineProjectNotFound):
				writeError(w, http.StatusNotFound, "timeline not found")
			case errors.Is(err, db.ErrTimelineRevisionConflict):
				writeError(w, http.StatusConflict, "this timeline was changed in another session; reload it before saving")
			default:
				logger.Error.Printf("updating timeline project: %v", err)
				writeError(w, http.StatusInternalServerError, "failed to save timeline")
			}
			return
		}

		writeJSON(w, http.StatusOK, project)
	}
}

func DeleteTimelineProjectHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		err := d.Timeline.Delete(r.PathValue("id"), user.ID)
		if errors.Is(err, db.ErrTimelineProjectNotFound) {
			writeError(w, http.StatusNotFound, "timeline not found")
			return
		}
		if err != nil {
			logger.Error.Printf("deleting timeline project: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to delete timeline")
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

type renderTimelineRequest struct {
	// ProjectID renders a saved timeline; Project renders an unsaved one.
	ProjectID string            `json:"projectId,omitempty"`
	Project   *timeline.Project `json:"project,omitempty"`
}

// RenderTimelineHandler validates a timeline and queues a normal render job, so
// progress, cancellation and history reuse the existing job pipeline unchanged.
func RenderTimelineHandler(d *Deps, manager *job.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		var req renderTimelineRequest
		if err := decodeJSONBody(w, r, &req); err != nil {
			return
		}

		var project *timeline.Project
		if strings.TrimSpace(req.ProjectID) != "" {
			loaded, err := d.Timeline.Get(req.ProjectID, user.ID)
			if errors.Is(err, db.ErrTimelineProjectNotFound) {
				writeError(w, http.StatusNotFound, "timeline not found")
				return
			}
			if err != nil {
				logger.Error.Printf("loading timeline project for render: %v", err)
				writeError(w, http.StatusInternalServerError, "failed to load timeline")
				return
			}
			project = loaded
		} else if req.Project != nil {
			project = req.Project
		} else {
			writeError(w, http.StatusBadRequest, "a project or projectId is required")
			return
		}

		project.Normalize()
		resolve := d.timelineAssetResolver(user)

		if errs := timeline.Validate(project, resolve); len(errs) > 0 {
			writeTimelineValidationError(w, http.StatusBadRequest, errs)
			return
		}

		plan, err := timeline.BuildRenderPlan(project, resolve)
		if err != nil {
			writeError(w, http.StatusUnprocessableEntity, err.Error())
			return
		}

		primary, err := d.Videos.Get(plan.PrimaryVideoID)
		if err != nil || !videoAccessible(primary, user) {
			writeError(w, http.StatusBadRequest, "the primary video is not available")
			return
		}
		if _, err := os.Stat(primary.OriginalPath); err != nil {
			writeError(w, http.StatusConflict, "the original file is no longer available")
			return
		}

		opsJSON, err := json.Marshal(plan.Request)
		if err != nil {
			logger.Error.Printf("marshaling timeline render plan: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to save render request")
			return
		}

		created, err := d.Jobs.CreateQueuedLimited(
			plan.PrimaryVideoID,
			string(opsJSON),
			d.Cfg.MaxConcurrentJobsPerUser,
			d.Cfg.MaxGlobalConcurrentJobs,
		)
		if errors.Is(err, db.ErrActiveJob) {
			writeError(w, http.StatusConflict, "the source video already has an active job")
			return
		}
		if errors.Is(err, db.ErrQuotaExceeded) {
			writeError(w, http.StatusTooManyRequests, "too many active jobs; try again later")
			return
		}
		if err != nil {
			logger.Error.Printf("queueing timeline render: %v", err)
			writeError(w, http.StatusInternalServerError, "failed to queue render")
			return
		}

		if manager != nil {
			manager.Notify()
		}

		writeJSON(w, http.StatusAccepted, toJobResponse(created))
	}
}

// fatalErrors filters validation errors down to the ones that block persistence.
// It returns nil when the project may be stored.
func fatalErrors(errs timeline.ValidationErrors) timeline.ValidationErrors {
	var fatal timeline.ValidationErrors
	for _, e := range errs {
		if _, ok := saveFatalCodes[e.Code]; ok {
			fatal = append(fatal, e)
		}
	}
	return fatal
}
