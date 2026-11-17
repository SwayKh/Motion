// Package timeline defines Motion's reusable, framework-independent timeline
// model plus its validation and render translation.
//
// The package deliberately does not import the FFmpeg layer: the model, the
// time math and the validation rules are pure data transformations so they can
// be unit tested without any media present. The render translation emits a
// models.ProcessRequest, which the existing job pipeline executes.
package timeline

import (
	"encoding/json"
	"time"
)

// CurrentSchemaVersion is the project JSON shape this build understands.
const CurrentSchemaVersion = 1

// Defaults applied to clips that omit a mixing/compositing value.
const (
	DefaultVolume  = 1.0
	DefaultOpacity = 1.0
	DefaultScale   = 1.0
)

// TrackType classifies a track. Video/image/text tracks are composited bottom
// to top; audio tracks are mixed together.
type TrackType string

const (
	TrackVideo TrackType = "video"
	TrackAudio TrackType = "audio"
	TrackImage TrackType = "image"
	TrackText  TrackType = "text"
)

// Valid reports whether the track type is one of the supported values.
func (t TrackType) Valid() bool {
	switch t {
	case TrackVideo, TrackAudio, TrackImage, TrackText:
		return true
	default:
		return false
	}
}

// ClipKind classifies a clip. It must be compatible with its track type.
type ClipKind string

const (
	KindVideo ClipKind = "video"
	KindAudio ClipKind = "audio"
	KindImage ClipKind = "image"
	KindText  ClipKind = "text"
)

// Valid reports whether the clip kind is one of the supported values.
func (k ClipKind) Valid() bool {
	switch k {
	case KindVideo, KindAudio, KindImage, KindText:
		return true
	default:
		return false
	}
}

// RenderSettings describe the output canvas and audio layout. A zero value means
// "derive from the primary clip".
type RenderSettings struct {
	Width      int `json:"width,omitempty"`
	Height     int `json:"height,omitempty"`
	FPS        int `json:"fps,omitempty"`
	SampleRate int `json:"sampleRate,omitempty"`
	Channels   int `json:"channels,omitempty"`
}

// Project is the persisted timeline. Server-managed fields (ID, UserID,
// Revision, timestamps) are ignored when a client sends a project and are filled
// in by the store.
type Project struct {
	ID            string         `json:"id,omitempty"`
	UserID        string         `json:"-"`
	Name          string         `json:"name"`
	SchemaVersion int            `json:"schemaVersion"`
	Tracks        []Track        `json:"tracks"`
	Render        RenderSettings `json:"render"`
	Revision      int            `json:"revision,omitempty"`
	CreatedAt     time.Time      `json:"createdAt,omitempty"`
	UpdatedAt     time.Time      `json:"updatedAt,omitempty"`
}

// Track groups clips that share a layer. Clips within a track must not overlap.
type Track struct {
	ID      string    `json:"id"`
	Type    TrackType `json:"type"`
	Name    string    `json:"name,omitempty"`
	Order   int       `json:"order"`
	Visible bool      `json:"visible"`
	Muted   bool      `json:"muted"`
	Clips   []Clip    `json:"clips"`
}

// UnmarshalJSON applies the default of a visible, unmuted track when the client
// omits the flags. An explicit false is preserved.
func (t *Track) UnmarshalJSON(data []byte) error {
	type alias struct {
		ID      string    `json:"id"`
		Type    TrackType `json:"type"`
		Name    string    `json:"name"`
		Order   int       `json:"order"`
		Visible *bool     `json:"visible"`
		Muted   bool      `json:"muted"`
		Clips   []Clip    `json:"clips"`
	}
	var a alias
	if err := json.Unmarshal(data, &a); err != nil {
		return err
	}
	t.ID = a.ID
	t.Type = a.Type
	t.Name = a.Name
	t.Order = a.Order
	t.Visible = true
	if a.Visible != nil {
		t.Visible = *a.Visible
	}
	t.Muted = a.Muted
	t.Clips = a.Clips
	return nil
}

// Clip places a portion of an asset on the timeline.
//
// Source time vs timeline time: the clip shows the asset range
// [SourceIn, SourceOut) starting at TimelineStart. Its duration is
// SourceOut-SourceIn. For text and image clips SourceIn is normally 0 and the
// pair still defines the clip's duration.
type Clip struct {
	ID   string   `json:"id"`
	Kind ClipKind `json:"kind"`

	// AssetID references an owned uploaded asset. Empty for text clips.
	AssetID string `json:"assetId,omitempty"`

	SourceIn      float64 `json:"sourceIn"`
	SourceOut     float64 `json:"sourceOut"`
	TimelineStart float64 `json:"timelineStart"`

	Volume  float64 `json:"volume"`
	Opacity float64 `json:"opacity"`

	// Overlay geometry. X/Y are pixels from the top-left; Scale is a multiplier.
	X     float64 `json:"x"`
	Y     float64 `json:"y"`
	Scale float64 `json:"scale"`

	Text string `json:"text,omitempty"`
}

// UnmarshalJSON applies the default volume/opacity/scale when the client omits
// them, so a missing field is never silently treated as muted or transparent.
func (c *Clip) UnmarshalJSON(data []byte) error {
	type alias struct {
		ID            string   `json:"id"`
		Kind          ClipKind `json:"kind"`
		AssetID       string   `json:"assetId"`
		SourceIn      float64  `json:"sourceIn"`
		SourceOut     float64  `json:"sourceOut"`
		TimelineStart float64  `json:"timelineStart"`
		Volume        *float64 `json:"volume"`
		Opacity       *float64 `json:"opacity"`
		X             float64  `json:"x"`
		Y             float64  `json:"y"`
		Scale         *float64 `json:"scale"`
		Text          string   `json:"text"`
	}
	var a alias
	if err := json.Unmarshal(data, &a); err != nil {
		return err
	}
	c.ID = a.ID
	c.Kind = a.Kind
	c.AssetID = a.AssetID
	c.SourceIn = a.SourceIn
	c.SourceOut = a.SourceOut
	c.TimelineStart = a.TimelineStart
	c.Volume = DefaultVolume
	if a.Volume != nil {
		c.Volume = *a.Volume
	}
	c.Opacity = DefaultOpacity
	if a.Opacity != nil {
		c.Opacity = *a.Opacity
	}
	c.X = a.X
	c.Y = a.Y
	c.Scale = DefaultScale
	if a.Scale != nil {
		c.Scale = *a.Scale
	}
	c.Text = a.Text
	return nil
}

// Normalize fills in defaults that cannot be expressed by a zero value and
// clamps compositing values into range. It is safe to call more than once.
func (p *Project) Normalize() {
	if p.SchemaVersion == 0 {
		p.SchemaVersion = CurrentSchemaVersion
	}
	for ti := range p.Tracks {
		track := &p.Tracks[ti]
		for ci := range track.Clips {
			clip := &track.Clips[ci]
			if clip.Scale <= 0 {
				clip.Scale = DefaultScale
			}
			if clip.Volume < 0 {
				clip.Volume = 0
			}
			if clip.Opacity < 0 {
				clip.Opacity = 0
			}
			if clip.Opacity > 1 {
				clip.Opacity = 1
			}
		}
	}
}

// Asset is the minimum information validation and rendering need about a
// referenced uploaded file.
type Asset struct {
	ID       string
	Duration float64
	HasVideo bool
	HasAudio bool
}

// AssetResolver resolves an asset id to its metadata. It must return false for
// assets that do not exist or are not owned by the requesting user.
type AssetResolver func(id string) (Asset, bool)
