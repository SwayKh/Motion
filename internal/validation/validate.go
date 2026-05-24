package validation

import (
	"fmt"
	"time"

	"VideoProject/internal/ffmpeg"
	"VideoProject/internal/models"
)

const (
	MaxOperations = 10
	MinSpeed      = 0.25
	MaxSpeed      = 4.0
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

		default:
			return fmt.Errorf(
				"operation %d unknown type=%s",
				i,
				op.Type,
			)
		}
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
	if op.Factor < MinSpeed ||
		op.Factor > MaxSpeed {

		return fmt.Errorf(
			"speed factor must be between %.2f and %.2f",
			MinSpeed,
			MaxSpeed,
		)
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

	duration := time.Duration(
		video.Duration * float64(time.Second),
	)

	if end > duration {
		return fmt.Errorf(
			"trim end exceeds video duration",
		)
	}

	return nil
}

func parseTimestamp(s string) (time.Duration, error) {
	t, err := time.Parse(
		"15:04:05",
		s,
	)
	if err != nil {
		return 0, err
	}

	duration := time.Duration(t.Hour())*time.Hour +
		time.Duration(t.Minute())*time.Minute +
		time.Duration(t.Second())*time.Second

	return duration, nil
}
