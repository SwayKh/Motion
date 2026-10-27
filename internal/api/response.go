package api

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
)

// videoResponse is the public representation of a video. Filesystem paths are
// deliberately omitted from this type.
type videoResponse struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Status      string    `json:"status"`
	Progress    float64   `json:"progress"`
	Duration    float64   `json:"duration"`
	Width       int       `json:"width"`
	Height      int       `json:"height"`
	FileSize    int64     `json:"fileSize"`
	Error       string    `json:"error,omitempty"`
	CreatedAt   time.Time `json:"createdAt"`
	HasOriginal bool      `json:"hasOriginal"`
	HasOutput   bool      `json:"hasOutput"`
	OutputType  string    `json:"outputType,omitempty"`
}

func toVideoResponse(video *models.Video) videoResponse {
	name := video.OriginalName
	if name == "" {
		name = filepath.Base(video.OriginalPath)
	}

	progress := video.Progress
	if progress < 0 {
		progress = 0
	}
	if progress > 100 {
		progress = 100
	}

	outputType := ""
	if video.OutputPath != "" {
		if strings.EqualFold(filepath.Ext(video.OutputPath), ".mp3") {
			outputType = "audio"
		} else {
			outputType = "video"
		}
	}

	return videoResponse{
		ID:          video.ID,
		Name:        name,
		Status:      video.Status,
		Progress:    progress,
		Duration:    video.Duration,
		Width:       video.Width,
		Height:      video.Height,
		FileSize:    video.FileSize,
		Error:       video.Error,
		CreatedAt:   video.CreatedAt,
		HasOriginal: video.OriginalPath != "",
		HasOutput:   video.Status == models.StatusCompleted && video.OutputPath != "",
		OutputType:  outputType,
	}
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)

	if err := json.NewEncoder(w).Encode(payload); err != nil {
		logger.Error.Printf("writing json response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{
		"error": message,
	})
}

type jobResponse struct {
	ID                string             `json:"id"`
	VideoID           string             `json:"videoId"`
	VideoName         string             `json:"videoName"`
	Status            string             `json:"status"`
	Progress          float64            `json:"progress"`
	Error             string             `json:"error,omitempty"`
	Operations        []models.Operation `json:"operations"`
	CreatedAt         time.Time          `json:"createdAt"`
	StartedAt         *time.Time         `json:"startedAt,omitempty"`
	FinishedAt        *time.Time         `json:"finishedAt,omitempty"`
	CancelRequestedAt *time.Time         `json:"cancelRequestedAt,omitempty"`
	HasSource         bool               `json:"hasSource"`
	HasOutput         bool               `json:"hasOutput"`
	OutputType        string             `json:"outputType,omitempty"`
}

func toJobResponse(job *models.Job) jobResponse {
	progress := job.Progress
	if progress < 0 {
		progress = 0
	}
	if progress > 100 {
		progress = 100
	}

	operations := make([]models.Operation, 0)
	var request models.ProcessRequest
	if err := json.Unmarshal([]byte(job.Operations), &request); err == nil {
		operations = request.Operations
	}

	outputType := ""
	if job.OutputPath != "" {
		if strings.EqualFold(filepath.Ext(job.OutputPath), ".mp3") {
			outputType = "audio"
		} else {
			outputType = "video"
		}
	}

	hasSource := false
	if job.SourcePath != "" {
		if _, err := os.Stat(job.SourcePath); err == nil {
			hasSource = true
		}
	}

	return jobResponse{
		ID:                job.ID,
		VideoID:           job.VideoID,
		VideoName:         job.VideoName,
		Status:            job.Status,
		Progress:          progress,
		Error:             job.Error,
		Operations:        operations,
		CreatedAt:         job.CreatedAt,
		StartedAt:         job.StartedAt,
		FinishedAt:        job.FinishedAt,
		CancelRequestedAt: job.CancelRequestedAt,
		HasSource:         hasSource,
		HasOutput:         job.Status == models.StatusCompleted && job.OutputPath != "",
		OutputType:        outputType,
	}
}
