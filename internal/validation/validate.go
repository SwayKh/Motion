package validation

import (
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/SwayKh/Motion/internal/ffmpeg"
	"github.com/SwayKh/Motion/internal/models"
)

const (
	MaxOperations = 10
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

	return nil
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
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, fmt.Errorf("timestamp is empty")
	}

	seconds, err := parseSeconds(s)
	if err != nil {
		return 0, err
	}
	if math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds < 0 {
		return 0, fmt.Errorf("timestamp must be a non-negative number")
	}

	return time.Duration(seconds * float64(time.Second)), nil
}

func parseSeconds(s string) (float64, error) {
	if !strings.Contains(s, ":") {
		return strconv.ParseFloat(s, 64)
	}

	parts := strings.Split(s, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return 0, fmt.Errorf("invalid timestamp")
	}

	var total float64
	for i, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			return 0, fmt.Errorf("invalid timestamp")
		}

		value, err := strconv.ParseFloat(part, 64)
		if err != nil {
			return 0, fmt.Errorf("invalid timestamp")
		}
		if math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
			return 0, fmt.Errorf("invalid timestamp")
		}

		// Minutes and seconds must be less than 60 when a larger unit is
		// present.
		if i > 0 && value >= 60 {
			return 0, fmt.Errorf("invalid timestamp")
		}

		total = total*60 + value
	}

	return total, nil
}
