package models

import (
	"time"

	"VideoProject/internal/ffmpeg"
)

const (
	StatusUploaded        = "uploaded"
	StatusQueued          = "queued"
	StatusProcessing      = "processing"
	StatusCancelRequested = "cancel_requested"
	StatusCancelled       = "cancelled"
	StatusCompleted       = "completed"
	StatusFailed          = "failed"
)

func IsActiveJobStatus(status string) bool {
	switch status {
	case StatusQueued, StatusProcessing, StatusCancelRequested:
		return true
	default:
		return false
	}
}

func IsTerminalJobStatus(status string) bool {
	switch status {
	case StatusCompleted, StatusFailed, StatusCancelled:
		return true
	default:
		return false
	}
}

type Video struct {
	ID     string
	UserID *string

	OriginalPath string
	OriginalName string
	OutputPath   string

	Status   string
	Progress float64

	FileSize int64

	Operations string

	Duration float64
	Width    int
	Height   int

	Error string

	CreatedAt time.Time
}

type Job struct {
	ID string

	VideoID   string
	VideoName string

	SourcePath string

	Operations string

	Status   string
	Progress float64

	OutputPath string
	OutputType string

	Error string

	CreatedAt time.Time

	StartedAt         *time.Time
	FinishedAt        *time.Time
	CancelRequestedAt *time.Time
}

type ProcessRequest struct {
	Operations []Operation `json:"operations"`
}

type Operation struct {
	Type string `json:"type"`

	Start string `json:"start,omitempty"`
	End   string `json:"end,omitempty"`

	Factor float64 `json:"factor,omitempty"`

	Direction ffmpeg.Orientation `json:"orientation,omitempty"`
}
