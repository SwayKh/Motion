package validation

import (
	"math"
	"strings"
	"testing"
	"time"

	"VideoProject/internal/ffmpeg"
	"VideoProject/internal/models"
)

func TestValidateProcessRequestAcceptsSupportedOperations(t *testing.T) {
	video := &models.Video{Duration: 10}

	tests := []struct {
		name string
		req  models.ProcessRequest
	}{
		{
			name: "video pipeline",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "trim", Start: "00:00:01", End: "00:00:04.5"},
				{Type: "speed", Factor: 0.5},
				{Type: "volume", Factor: 1.2},
				{Type: "flip", Direction: ffmpeg.Horizontal},
			}},
		},
		{
			name: "audio pipeline",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "trim", Start: "1.5", End: "8"},
				{Type: "volume", Factor: 2},
				{Type: "to_audio"},
			}},
		},
		{
			name: "boundary speeds",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "speed", Factor: MinSpeed},
				{Type: "speed", Factor: MaxSpeed},
			}},
		},
		{
			name: "flip both directions",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "flip", Direction: ffmpeg.Vertical},
				{Type: "flip", Direction: ffmpeg.Horizontal},
			}},
		},
		{
			name: "trim exactly at duration",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "trim", Start: "00:00:00", End: "00:00:10"},
			}},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := ValidateProcessRequest(video, test.req); err != nil {
				t.Fatalf("unexpected validation error: %v", err)
			}
		})
	}
}

func TestValidateProcessRequestRejectsInvalidOperations(t *testing.T) {
	video := &models.Video{Duration: 10}

	tooMany := make([]models.Operation, MaxOperations+1)
	for i := range tooMany {
		tooMany[i] = models.Operation{Type: "speed", Factor: 1}
	}

	tests := []struct {
		name      string
		req       models.ProcessRequest
		wantError string
	}{
		{name: "empty", req: models.ProcessRequest{}, wantError: "cannot be empty"},
		{name: "too many", req: models.ProcessRequest{Operations: tooMany}, wantError: "too many operations"},
		{name: "unknown", req: models.ProcessRequest{Operations: []models.Operation{{Type: "crop"}}}, wantError: "unknown type"},
		{name: "flip out of range", req: models.ProcessRequest{Operations: []models.Operation{{Type: "flip", Direction: 2}}}, wantError: "flip invalid"},
		{name: "speed low", req: models.ProcessRequest{Operations: []models.Operation{{Type: "speed", Factor: 0.49}}}, wantError: "speed factor"},
		{name: "speed high", req: models.ProcessRequest{Operations: []models.Operation{{Type: "speed", Factor: 2.01}}}, wantError: "speed factor"},
		{name: "speed nan", req: models.ProcessRequest{Operations: []models.Operation{{Type: "speed", Factor: math.NaN()}}}, wantError: "speed factor"},
		{name: "speed inf", req: models.ProcessRequest{Operations: []models.Operation{{Type: "speed", Factor: math.Inf(1)}}}, wantError: "speed factor"},
		{name: "volume zero", req: models.ProcessRequest{Operations: []models.Operation{{Type: "volume", Factor: 0}}}, wantError: "volume factor"},
		{name: "volume negative", req: models.ProcessRequest{Operations: []models.Operation{{Type: "volume", Factor: -1}}}, wantError: "volume factor"},
		{name: "volume nan", req: models.ProcessRequest{Operations: []models.Operation{{Type: "volume", Factor: math.NaN()}}}, wantError: "volume factor"},
		{name: "volume inf", req: models.ProcessRequest{Operations: []models.Operation{{Type: "volume", Factor: math.Inf(-1)}}}, wantError: "volume factor"},
		{name: "trim empty start", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "", End: "2"}}}, wantError: "trim invalid"},
		{name: "trim malformed start", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "abc", End: "2"}}}, wantError: "trim invalid"},
		{name: "trim negative", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "-1", End: "2"}}}, wantError: "trim invalid"},
		{name: "trim nan", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "NaN", End: "2"}}}, wantError: "trim invalid"},
		{name: "trim inf", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "0", End: "Inf"}}}, wantError: "trim invalid"},
		{name: "trim four parts", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "1:2:3:4", End: "5"}}}, wantError: "trim invalid"},
		{name: "trim seconds 60", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "0", End: "1:60"}}}, wantError: "trim invalid"},
		{name: "trim start equals end", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "2", End: "2"}}}, wantError: "start must be before end"},
		{name: "trim start after end", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "3", End: "2"}}}, wantError: "start must be before end"},
		{name: "trim beyond duration", req: models.ProcessRequest{Operations: []models.Operation{{Type: "trim", Start: "0", End: "10.5"}}}, wantError: "exceeds video duration"},
		{
			name:      "duplicate to_audio",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "to_audio"}, {Type: "to_audio"}}},
			wantError: "only one extract audio",
		},
		{
			name: "to_audio not last",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "to_audio"},
				{Type: "trim", Start: "0", End: "2"},
			}},
			wantError: "must be the last operation",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := ValidateProcessRequest(video, test.req)
			if err == nil {
				t.Fatal("expected validation error")
			}
			if !strings.Contains(err.Error(), test.wantError) {
				t.Fatalf("error = %q, want substring %q", err, test.wantError)
			}
		})
	}
}

func TestValidateTrimAllowsEndWhenDurationUnknown(t *testing.T) {
	req := models.ProcessRequest{Operations: []models.Operation{
		{Type: "trim", Start: "0", End: "999"},
	}}

	if err := ValidateProcessRequest(&models.Video{}, req); err != nil {
		t.Fatalf("unexpected validation error: %v", err)
	}
}

func TestParseTimestamp(t *testing.T) {
	tests := []struct {
		value string
		want  time.Duration
	}{
		{value: "0", want: 0},
		{value: "5", want: 5 * time.Second},
		{value: "5.25", want: 5250 * time.Millisecond},
		{value: " 5 ", want: 5 * time.Second},
		{value: "00:05", want: 5 * time.Second},
		{value: "1:02", want: 62 * time.Second},
		{value: "01:02:03", want: time.Hour + 2*time.Minute + 3*time.Second},
		{value: "01:02:03.5", want: time.Hour + 2*time.Minute + 3500*time.Millisecond},
	}

	for _, test := range tests {
		t.Run(test.value, func(t *testing.T) {
			got, err := parseTimestamp(test.value)
			if err != nil {
				t.Fatalf("parseTimestamp(%q): %v", test.value, err)
			}
			if got != test.want {
				t.Fatalf("parseTimestamp(%q) = %v, want %v", test.value, got, test.want)
			}
		})
	}

	invalid := []string{
		"",
		" ",
		"abc",
		"-1",
		"NaN",
		"Inf",
		"1:",
		":1",
		"1::2",
		"1:2:3:4",
		"1:60",
		"1:2:60",
		"1:-2",
	}

	for _, value := range invalid {
		t.Run("invalid "+value, func(t *testing.T) {
			if _, err := parseTimestamp(value); err == nil {
				t.Fatalf("parseTimestamp(%q) unexpectedly succeeded", value)
			}
		})
	}
}
