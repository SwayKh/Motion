package models

import (
	"time"

	"VideoProject/internal/ffmpeg"
)

const (
	StatusUploaded   = "uploaded"
	StatusQueued     = "queued"
	StatusProcessing = "processing"
	StatusCompleted  = "completed"
	StatusFailed     = "failed"
)

type Video struct {
	ID string

	OriginalPath string
	OutputPath   string

	Status   string
	Progress float64

	Operations string

	Duration float64
	Width    int
	Height   int

	Error string

	CreatedAt time.Time
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
