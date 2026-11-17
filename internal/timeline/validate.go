package timeline

import (
	"fmt"
	"math"
	"strings"
)

const (
	// MaxNameLength bounds a project name.
	MaxNameLength = 200
	// MaxTracks bounds the number of tracks in a project.
	MaxTracks = 32
	// MaxClips bounds the total number of clips in a project.
	MaxClips = 500

	// epsilon absorbs floating point noise in time comparisons.
	epsilon = 1e-6
)

// ValidationError describes one problem found in a project. The identifiers let
// the UI highlight the offending track or clip.
type ValidationError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	TrackID string `json:"trackId,omitempty"`
	ClipID  string `json:"clipId,omitempty"`
}

func (e ValidationError) Error() string { return e.Message }

// ValidationErrors is a list of problems. It implements error so callers can
// return it directly.
type ValidationErrors []ValidationError

func (errs ValidationErrors) Error() string {
	if len(errs) == 0 {
		return ""
	}
	parts := make([]string, len(errs))
	for i, e := range errs {
		parts[i] = e.Message
	}
	return strings.Join(parts, "; ")
}

// ValidateStructure checks everything that does not require the referenced
// assets: track/clip types, timestamp sanity, overlaps and render settings.
// It is used when saving a draft so a partially built timeline can be stored.
func ValidateStructure(p *Project) ValidationErrors {
	var errs ValidationErrors

	if p == nil {
		return ValidationErrors{{Code: "empty_project", Message: "the project is empty"}}
	}
	if p.SchemaVersion != 0 && p.SchemaVersion != CurrentSchemaVersion {
		errs = append(errs, ValidationError{
			Code:    "unsupported_schema",
			Message: fmt.Sprintf("unsupported project schema version %d", p.SchemaVersion),
		})
	}

	name := strings.TrimSpace(p.Name)
	if name == "" {
		errs = append(errs, ValidationError{Code: "missing_name", Message: "a project name is required"})
	} else if len(name) > MaxNameLength {
		errs = append(errs, ValidationError{Code: "name_too_long", Message: "the project name is too long"})
	}

	if len(p.Tracks) == 0 {
		errs = append(errs, ValidationError{Code: "no_tracks", Message: "the project has no tracks"})
	}
	if len(p.Tracks) > MaxTracks {
		errs = append(errs, ValidationError{
			Code:    "too_many_tracks",
			Message: fmt.Sprintf("the project has more than %d tracks", MaxTracks),
		})
	}

	errs = append(errs, validateRenderSettings(p.Render)...)

	seenOrder := make(map[int]string)
	clipCount := 0
	for ti := range p.Tracks {
		track := &p.Tracks[ti]
		where := ValidationError{TrackID: track.ID}

		if !track.Type.Valid() {
			e := where
			e.Code = "invalid_track_type"
			e.Message = fmt.Sprintf("track %q has an unsupported type %q", trackLabel(track), track.Type)
			errs = append(errs, e)
			continue
		}
		if track.Order < 0 {
			e := where
			e.Code = "invalid_track_order"
			e.Message = fmt.Sprintf("track %q has a negative order", trackLabel(track))
			errs = append(errs, e)
		}
		if other, ok := seenOrder[track.Order]; ok {
			e := where
			e.Code = "duplicate_track_order"
			e.Message = fmt.Sprintf("tracks %q and %q share order %d", other, trackLabel(track), track.Order)
			errs = append(errs, e)
		}
		seenOrder[track.Order] = trackLabel(track)

		errs = append(errs, validateTrackClips(track)...)
		clipCount += len(track.Clips)
	}

	if clipCount == 0 {
		errs = append(errs, ValidationError{Code: "no_clips", Message: "the project has no clips"})
	}
	if clipCount > MaxClips {
		errs = append(errs, ValidationError{
			Code:    "too_many_clips",
			Message: fmt.Sprintf("the project has more than %d clips", MaxClips),
		})
	}

	return errs
}

func validateTrackClips(track *Track) ValidationErrors {
	var errs ValidationErrors

	ordered := SortedClips(*track)
	for i := range ordered {
		clip := ordered[i]
		where := ValidationError{TrackID: track.ID, ClipID: clip.ID}
		errs = append(errs, validateClip(track, clip, where)...)
	}

	// Clips on one track must not overlap. ordered is sorted by start.
	for i := 1; i < len(ordered); i++ {
		prev, next := ordered[i-1], ordered[i]
		if next.TimelineStart < prev.TimelineEnd()-epsilon {
			errs = append(errs, ValidationError{
				Code:    "overlapping_clips",
				Message: fmt.Sprintf("clips on track %q overlap", trackLabel(track)),
				TrackID: track.ID,
				ClipID:  next.ID,
			})
		}
	}
	return errs
}

func validateClip(track *Track, clip Clip, where ValidationError) ValidationErrors {
	var errs ValidationErrors
	add := func(code, message string) {
		e := where
		e.Code = code
		e.Message = message
		errs = append(errs, e)
	}

	if !clip.Kind.Valid() {
		add("invalid_clip_kind", fmt.Sprintf("clip %q has an unsupported kind %q", clip.ID, clip.Kind))
		return errs
	}
	if !kindAllowedOnTrack(clip.Kind, track.Type) {
		add("clip_track_mismatch", fmt.Sprintf(
			"a %s clip is not allowed on a %s track", clip.Kind, track.Type,
		))
	}

	if clip.TimelineStart < -epsilon {
		add("negative_start", fmt.Sprintf("clip %q starts before the timeline origin", clip.ID))
	}
	if clip.SourceOut <= clip.SourceIn+epsilon {
		add("invalid_source_range", fmt.Sprintf("clip %q has an empty or inverted source range", clip.ID))
	}
	if clip.Volume < -epsilon {
		add("invalid_volume", fmt.Sprintf("clip %q has a negative volume", clip.ID))
	}
	if clip.Opacity < -epsilon || clip.Opacity > 1+epsilon {
		add("invalid_opacity", fmt.Sprintf("clip %q opacity must be between 0 and 1", clip.ID))
	}
	if clip.Scale <= 0 {
		add("invalid_scale", fmt.Sprintf("clip %q scale must be greater than 0", clip.ID))
	}

	switch clip.Kind {
	case KindText:
		if strings.TrimSpace(clip.Text) == "" {
			add("empty_text", fmt.Sprintf("text clip %q has no text", clip.ID))
		}
		if clip.AssetID != "" {
			add("text_with_asset", fmt.Sprintf("text clip %q must not reference an asset", clip.ID))
		}
	case KindVideo, KindAudio, KindImage:
		if strings.TrimSpace(clip.AssetID) == "" {
			add("missing_asset", fmt.Sprintf("clip %q does not reference an asset", clip.ID))
		}
	}

	return errs
}

func kindAllowedOnTrack(kind ClipKind, track TrackType) bool {
	switch track {
	case TrackVideo:
		return kind == KindVideo
	case TrackAudio:
		return kind == KindAudio
	case TrackImage:
		return kind == KindImage
	case TrackText:
		return kind == KindText
	default:
		return false
	}
}

func validateRenderSettings(r RenderSettings) ValidationErrors {
	var errs ValidationErrors
	add := func(code, message string) {
		errs = append(errs, ValidationError{Code: code, Message: message})
	}

	if r.Width != 0 {
		if r.Width < 16 || r.Width > 7680 {
			add("invalid_render_width", "render width must be between 16 and 7680")
		} else if r.Width%2 != 0 {
			add("invalid_render_width", "render width must be even")
		}
	}
	if r.Height != 0 {
		if r.Height < 16 || r.Height > 4320 {
			add("invalid_render_height", "render height must be between 16 and 4320")
		} else if r.Height%2 != 0 {
			add("invalid_render_height", "render height must be even")
		}
	}
	if r.FPS != 0 && (r.FPS < 1 || r.FPS > 120) {
		add("invalid_render_fps", "render frame rate must be between 1 and 120")
	}
	if r.SampleRate != 0 && (r.SampleRate < 8000 || r.SampleRate > 192000) {
		add("invalid_render_sample_rate", "render sample rate must be between 8000 and 192000")
	}
	if r.Channels != 0 && (r.Channels < 1 || r.Channels > 8) {
		add("invalid_render_channels", "render channel count must be between 1 and 8")
	}
	return errs
}

// Validate checks the structure and every asset reference. The resolver must
// return false for assets the user does not own, so a crafted project can never
// read another account's media.
func Validate(p *Project, resolve AssetResolver) ValidationErrors {
	errs := ValidateStructure(p)
	if p == nil {
		return errs
	}

	for ti := range p.Tracks {
		track := &p.Tracks[ti]
		for ci := range track.Clips {
			clip := track.Clips[ci]
			if clip.Kind == KindText || strings.TrimSpace(clip.AssetID) == "" {
				continue
			}
			if resolve == nil {
				continue
			}
			asset, ok := resolve(clip.AssetID)
			if !ok {
				errs = append(errs, ValidationError{
					Code:    "asset_unavailable",
					Message: fmt.Sprintf("clip %q references an asset that is missing or not accessible", clip.ID),
					TrackID: track.ID,
					ClipID:  clip.ID,
				})
				continue
			}
			if clip.Kind == KindVideo && !asset.HasVideo {
				errs = append(errs, ValidationError{
					Code:    "asset_has_no_video",
					Message: fmt.Sprintf("clip %q references an asset with no video stream", clip.ID),
					TrackID: track.ID,
					ClipID:  clip.ID,
				})
			}
			if asset.Duration > 0 && clip.SourceOut > asset.Duration+0.001 {
				errs = append(errs, ValidationError{
					Code:    "source_out_of_range",
					Message: fmt.Sprintf("clip %q ends past the end of its source", clip.ID),
					TrackID: track.ID,
					ClipID:  clip.ID,
				})
			}
		}
	}

	return errs
}

// trackLabel gives a track a human readable name for error messages.
func trackLabel(track *Track) string {
	if track.Name != "" {
		return track.Name
	}
	if track.ID != "" {
		return track.ID
	}
	return string(track.Type)
}

// nearlyEqual reports whether two times are equal within epsilon.
func nearlyEqual(a, b float64) bool {
	return math.Abs(a-b) <= epsilon
}
