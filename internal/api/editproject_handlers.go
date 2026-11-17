package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/validation"
)

const maxEditProjectName = 200

type editProjectRequest struct {
	VideoID string                `json:"videoId"`
	Name    string                `json:"name"`
	Request models.ProcessRequest `json:"request"`
}

type editProjectResponse struct {
	ID        string                `json:"id"`
	VideoID   string                `json:"videoId"`
	Name      string                `json:"name"`
	Request   models.ProcessRequest `json:"request"`
	CreatedAt time.Time             `json:"createdAt"`
	UpdatedAt time.Time             `json:"updatedAt"`
}

func toEditProjectResponse(project *models.EditProject) editProjectResponse {
	operations := project.Request.Operations
	if operations == nil {
		operations = []models.Operation{}
	}
	return editProjectResponse{
		ID:        project.ID,
		VideoID:   project.VideoID,
		Name:      project.Name,
		Request:   models.ProcessRequest{Operations: operations},
		CreatedAt: project.CreatedAt,
		UpdatedAt: project.UpdatedAt,
	}
}

func CreateEditProjectHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		var req editProjectRequest
		if err := decodeJSONBody(w, r, &req); err != nil {
			return
		}

		name := strings.TrimSpace(req.Name)
		if name == "" || len(name) > maxEditProjectName {
			writeError(w, http.StatusBadRequest, "project name is required")
			return
		}

		video, err := d.Videos.Get(req.VideoID)
		if err != nil || !videoAccessible(video, user) {
			writeError(w, http.StatusNotFound, "video not found")
			return
		}
		if err := validation.ValidateProcessRequest(video, req.Request); err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}

		project := &models.EditProject{
			UserID:  user.ID,
			VideoID: req.VideoID,
			Name:    name,
			Request: req.Request,
		}
		if err := d.Projects.Create(project); err != nil {
			writeError(w, http.StatusInternalServerError, "failed to save edit project")
			return
		}

		writeJSON(w, http.StatusCreated, toEditProjectResponse(project))
	}
}

func ListEditProjectsHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		projects, err := d.Projects.List(user.ID)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load edit projects")
			return
		}

		response := make([]editProjectResponse, 0, len(projects))
		for i := range projects {
			response = append(response, toEditProjectResponse(&projects[i]))
		}
		writeJSON(w, http.StatusOK, response)
	}
}

func GetEditProjectHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		project, err := d.Projects.Get(r.PathValue("id"), user.ID)
		if errors.Is(err, db.ErrEditProjectNotFound) {
			writeError(w, http.StatusNotFound, "edit project not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load edit project")
			return
		}

		writeJSON(w, http.StatusOK, toEditProjectResponse(project))
	}
}

func UpdateEditProjectHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		project, err := d.Projects.Get(r.PathValue("id"), user.ID)
		if errors.Is(err, db.ErrEditProjectNotFound) {
			writeError(w, http.StatusNotFound, "edit project not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to load edit project")
			return
		}

		var req editProjectRequest
		if err := decodeJSONBody(w, r, &req); err != nil {
			return
		}

		if name := strings.TrimSpace(req.Name); name != "" {
			if len(name) > maxEditProjectName {
				writeError(w, http.StatusBadRequest, "project name is too long")
				return
			}
			project.Name = name
		}

		if len(req.Request.Operations) > 0 {
			video, err := d.Videos.Get(project.VideoID)
			if err != nil {
				writeError(w, http.StatusConflict, "the source video is no longer available")
				return
			}
			if err := validation.ValidateProcessRequest(video, req.Request); err != nil {
				writeError(w, http.StatusBadRequest, err.Error())
				return
			}
			project.Request = req.Request
		}

		if err := d.Projects.Update(project); err != nil {
			if errors.Is(err, db.ErrEditProjectNotFound) {
				writeError(w, http.StatusNotFound, "edit project not found")
				return
			}
			writeError(w, http.StatusInternalServerError, "failed to update edit project")
			return
		}

		writeJSON(w, http.StatusOK, toEditProjectResponse(project))
	}
}

func DeleteEditProjectHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		user := getUser(r)
		if user == nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}

		err := d.Projects.Delete(r.PathValue("id"), user.ID)
		if errors.Is(err, db.ErrEditProjectNotFound) {
			writeError(w, http.StatusNotFound, "edit project not found")
			return
		}
		if err != nil {
			writeError(w, http.StatusInternalServerError, "failed to delete edit project")
			return
		}

		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	}
}

// decodeJSONBody decodes a size-limited JSON request body, writing the error
// response itself and returning a non-nil error on failure.
func decodeJSONBody(w http.ResponseWriter, r *http.Request, dst any) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxProcessBodySize)
	if err := json.NewDecoder(r.Body).Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return err
	}
	return nil
}
