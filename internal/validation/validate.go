package validation

import (
	"fmt"
	"math"
	"time"

	"github.com/SwayKh/Motion/internal/ffmpeg"
	"github.com/SwayKh/Motion/internal/models"
)

const (
	MaxOperations = 20
	MinSpeed      = 0.5
	MaxSpeed      = 2.0
)

func ValidateProcessRequest(video *models.Video, req models.ProcessRequest) error {
	if len(req.Operations) == 0 {
		return fmt.Errorf("operations cannot be empty")
	}

	if len(req.Operations) > MaxOperations {
		return fmt.Errorf(
			"too many operations max=%d",
			MaxOperations,
		)
	}

	toAudioIndex := -1
	concatSeen := false

	for i, op := range req.Operations {
		switch op.Type {

		case "flip":
			err := validateFlip(op)
			if err != nil {
				return fmt.Errorf(
					"operation %d flip invalid: %w",
					i,
					err,
				)
			}

		case "trim":
			err := validateTrim(video, op)
			if err != nil {
				return fmt.Errorf(
					"operation %d trim invalid: %w",
					i,
					err,
				)
			}

		case "slice":
			err := validateTrim(video, op)
			if err != nil {
				return fmt.Errorf(
					"operation %d slice invalid: %w",
					i,
					err,
				)
			}

		case "remove_section":
			err := validateTrim(video, op)
			if err != nil {
				return fmt.Errorf(
					"operation %d remove_section invalid: %w",
					i,
					err,
				)
			}

		case "speed":
			err := validateSpeed(op)
			if err != nil {
				return fmt.Errorf(
					"operation %d speed invalid: %w",
					i,
					err,
				)
			}

		case "volume":
			err := validateVolume(op)
			if err != nil {
				return fmt.Errorf(
					"operation %d volume invalid: %w",
					i,
					err,
				)
			}

		case "add_audio", "replace_audio":
			if err := validateAudio(op); err != nil {
				return fmt.Errorf(
					"operation %d %s invalid: %w",
					i,
					op.Type,
					err,
				)
			}

		case "concat":
			if concatSeen {
				return fmt.Errorf(
					"operation %d concat invalid: only one concat operation is allowed",
					i,
				)
			}
			concatSeen = true
			if err := validateConcat(op); err != nil {
				return fmt.Errorf(
					"operation %d concat invalid: %w",
					i,
					err,
				)
			}

		case "to_audio":
			if toAudioIndex != -1 {
				return fmt.Errorf(
					"operation %d to_audio invalid: only one extract audio operation is allowed",
					i,
				)
			}
			toAudioIndex = i

			err := validateToAudio(op)
			if err != nil {
				return fmt.Errorf(
					"operation %d to_audio invalid: %w",
					i,
					err,
				)
			}

		default:
			return fmt.Errorf(
				"operation %d unknown type=%s",
				i,
				op.Type,
			)
		}
	}

	// Extract audio disables the video stream, so anything after it would be
	// silently ignored by the dispatcher. Reject it instead of surprising the
	// user with a partial result.
	if toAudioIndex != -1 && toAudioIndex != len(req.Operations)-1 {
		return fmt.Errorf(
			"operation %d to_audio invalid: extract audio must be the last operation",
			toAudioIndex,
		)
	}

	// concat rewrites the whole timeline by normalising and joining clips. Other
	// operations would be silently ignored by the dispatcher, so reject them
	// instead of returning a surprising result.
	if concatSeen && len(req.Operations) != 1 {
		return fmt.Errorf("concat must be the only operation")
	}

	return nil
}

func validateAudio(op models.Operation) error {
	if op.AudioSourceID == "" {
		return fmt.Errorf("an audio source video is required")
	}
	if op.AudioVolume < 0 {
		return fmt.Errorf("audio volume must not be negative")
	}
	if op.AudioVolume > 10 {
		return fmt.Errorf("audio volume is too large")
	}
	if op.Offset != "" {
		if _, err := parseTimestamp(op.Offset); err != nil {
			return fmt.Errorf("invalid offset")
		}
	}
	if op.AudioStart != "" {
		if _, err := parseTimestamp(op.AudioStart); err != nil {
			return fmt.Errorf("invalid audio start")
		}
	}
	if op.AudioEnd != "" {
		if _, err := parseTimestamp(op.AudioEnd); err != nil {
			return fmt.Errorf("invalid audio end")
		}
	}
	if op.AudioStart != "" && op.AudioEnd != "" {
		start, _ := parseTimestamp(op.AudioStart)
		end, _ := parseTimestamp(op.AudioEnd)
		if start >= end {
			return fmt.Errorf("audio start must be before audio end")
		}
	}
	return nil
}

func validateConcat(op models.Operation) error {
	if len(op.Clips) < 2 {
		return fmt.Errorf("concat requires at least two clips")
	}
	if len(op.Clips) > 20 {
		return fmt.Errorf("concat supports at most 20 clips")
	}
	for i, clip := range op.Clips {
		if clip.Start == "" && clip.End == "" {
			continue
		}
		if clip.Start != "" {
			if _, err := parseTimestamp(clip.Start); err != nil {
				return fmt.Errorf("clip %d has an invalid start", i)
			}
		}
		if clip.End != "" {
			if _, err := parseTimestamp(clip.End); err != nil {
				return fmt.Errorf("clip %d has an invalid end", i)
			}
		}
		if clip.Start != "" && clip.End != "" {
			start, _ := parseTimestamp(clip.Start)
			end, _ := parseTimestamp(clip.End)
			if start >= end {
				return fmt.Errorf("clip %d start must be before end", i)
			}
		}
	}
	return nil
}

// ReferencedVideoIDs returns the IDs of videos other than the primary source
// that an operation depends on. The API layer uses it to enforce ownership
// before a job is queued.
func ReferencedVideoIDs(videoID string, req models.ProcessRequest) []string {
	seen := make(map[string]struct{})
	var ids []string
	add := func(id string) {
		if id == "" || id == videoID {
			return
		}
		if _, ok := seen[id]; ok {
			return
		}
		seen[id] = struct{}{}
		ids = append(ids, id)
	}
	for _, op := range req.Operations {
		add(op.AudioSourceID)
		for _, clip := range op.Clips {
			add(clip.VideoID)
		}
	}
	return ids
}

func validateFlip(op models.Operation) error {
	if op.Direction != ffmpeg.Vertical &&
		op.Direction != ffmpeg.Horizontal {

		return fmt.Errorf(
			"direction must be vertical or horizontal",
		)
	}

	return nil
}

func validateSpeed(op models.Operation) error {
	if math.IsNaN(op.Factor) || math.IsInf(op.Factor, 0) ||
		op.Factor < MinSpeed ||
		op.Factor > MaxSpeed {

		return fmt.Errorf(
			"speed factor must be between %.2f and %.2f",
			MinSpeed,
			MaxSpeed,
		)
	}

	return nil
}

func validateToAudio(op models.Operation) error {
	return nil
}

func validateVolume(op models.Operation) error {
	return ValidateVolumeFactor(op.Factor)
}

func ValidateVolumeFactor(factor float64) error {
	if math.IsNaN(factor) || math.IsInf(factor, 0) || factor <= 0 {
		return fmt.Errorf("volume factor must be greater than 0")
	}

	return nil
}

func validateTrim(video *models.Video, op models.Operation) error {
	start, err := parseTimestamp(op.Start)
	if err != nil {
		return fmt.Errorf("invalid start timestamp")
	}

	end, err := parseTimestamp(op.End)
	if err != nil {
		return fmt.Errorf("invalid end timestamp")
	}

	if start >= end {
		return fmt.Errorf(
			"start must be before end",
		)
	}

	if video.Duration > 0 && end.Seconds() > video.Duration+0.001 {
		return fmt.Errorf(
			"trim end exceeds video duration",
		)
	}

	return nil
}

// parseTimestamp accepts plain seconds ("12", "12.5"), MM:SS, or HH:MM:SS
// timestamps. Fractional seconds are supported in every form.
func parseTimestamp(s string) (time.Duration, error) {
	seconds, err := ffmpeg.ParseTimecode(s)
	if err != nil {
		return 0, err
	}
	return time.Duration(seconds * float64(time.Second)), nil
}
