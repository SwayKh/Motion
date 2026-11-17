package models

import (
	"time"

	"github.com/SwayKh/Motion/internal/ffmpeg"
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

	// Encryption at rest. Encrypted/EncKey describe the original upload;
	// OutputEncrypted/OutputEncKey describe the current processed output.
	Encrypted       bool
	EncKey          string
	OutputEncrypted bool
	OutputEncKey    string

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

	// Encryption at rest for the job output.
	Encrypted bool
	EncKey    string

	Error string

	CreatedAt time.Time

	StartedAt         *time.Time
	FinishedAt        *time.Time
	CancelRequestedAt *time.Time
}

type ProcessRequest struct {
	Operations []Operation `json:"operations"`
}

// Clip references a portion of a source video used by the concat operation.
// An empty VideoID means the job's own source.
type Clip struct {
	VideoID string `json:"videoId,omitempty"`
	Start   string `json:"start,omitempty"`
	End     string `json:"end,omitempty"`
}

type Operation struct {
	Type string `json:"type"`

	Start string `json:"start,omitempty"`
	End   string `json:"end,omitempty"`

	Factor float64 `json:"factor,omitempty"`

	Direction ffmpeg.Orientation `json:"orientation,omitempty"`

	// Editing extensions.

	// AudioSourceID references another owned video used by add_audio /
	// replace_audio operations.
	AudioSourceID string  `json:"audioSourceId,omitempty"`
	AudioVolume   float64 `json:"audioVolume,omitempty"`
	// Offset delays the added audio track by the given timestamp.
	Offset string `json:"offset,omitempty"`
	// AudioStart/AudioEnd trim the added audio track.
	AudioStart string `json:"audioStart,omitempty"`
	AudioEnd   string `json:"audioEnd,omitempty"`

	// Clips lists the segments combined by a concat operation.
	Clips []Clip `json:"clips,omitempty"`

	// Compose carries the full layer description for a multitrack render. When
	// set it is the only operation and the worker builds an FFmpeg
	// filter_complex graph instead of the single-source pipeline.
	Compose *ComposePlan `json:"compose,omitempty"`
}

// ComposePlan describes a filter_complex composition: a stack of visual layers
// composited bottom to top over a black canvas plus an optional audio mix.
type ComposePlan struct {
	Width    int            `json:"width,omitempty"`
	Height   int            `json:"height,omitempty"`
	FPS      int            `json:"fps,omitempty"`
	Duration string         `json:"duration,omitempty"`
	Layers   []ComposeLayer `json:"layers,omitempty"`
	Audio    []ComposeAudio `json:"audio,omitempty"`
	HasAudio bool           `json:"hasAudio,omitempty"`
}

// ComposeLayer is one visual layer. Base marks the full-canvas background clip;
// every other layer is an overlay positioned by X/Y and Scale.
type ComposeLayer struct {
	Kind          string  `json:"kind"`
	VideoID       string  `json:"videoId,omitempty"`
	Text          string  `json:"text,omitempty"`
	SourceIn      string  `json:"sourceIn,omitempty"`
	SourceOut     string  `json:"sourceOut,omitempty"`
	TimelineStart string  `json:"timelineStart,omitempty"`
	TimelineEnd   string  `json:"timelineEnd,omitempty"`
	X             float64 `json:"x,omitempty"`
	Y             float64 `json:"y,omitempty"`
	Scale         float64 `json:"scale,omitempty"`
	Opacity       float64 `json:"opacity,omitempty"`
	Base          bool    `json:"base,omitempty"`
}

// ComposeAudio is one audio segment placed at TimelineStart and mixed into the
// output.
type ComposeAudio struct {
	VideoID       string  `json:"videoId,omitempty"`
	SourceIn      string  `json:"sourceIn,omitempty"`
	SourceOut     string  `json:"sourceOut,omitempty"`
	TimelineStart string  `json:"timelineStart,omitempty"`
	Volume        float64 `json:"volume,omitempty"`
}

// EditProject is a reusable, persisted editing session. It stores the source
// video and the operations that can be re-applied later.
type EditProject struct {
	ID        string
	UserID    string
	VideoID   string
	Name      string
	Request   ProcessRequest
	CreatedAt time.Time
	UpdatedAt time.Time
}
