package validation

import (
	"slices"
	"strings"
	"testing"

	"github.com/SwayKh/Motion/internal/models"
)

func TestValidateEditOperationsAccepted(t *testing.T) {
	video := &models.Video{Duration: 10}

	tests := []struct {
		name string
		req  models.ProcessRequest
	}{
		{
			name: "slice",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "slice", Start: "1", End: "4"},
			}},
		},
		{
			name: "remove_section",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "remove_section", Start: "2", End: "5"},
			}},
		},
		{
			name: "add_audio",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "add_audio", AudioSourceID: "other", AudioVolume: 0.5, Offset: "1", AudioStart: "0", AudioEnd: "5"},
			}},
		},
		{
			name: "replace_audio",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "replace_audio", AudioSourceID: "other", AudioVolume: 2},
			}},
		},
		{
			name: "concat",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "concat", Clips: []models.Clip{
					{Start: "0", End: "2"},
					{VideoID: "other", Start: "1", End: "3"},
				}},
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

func TestValidateEditOperationsRejected(t *testing.T) {
	video := &models.Video{Duration: 10}

	clips := make([]models.Clip, 21)
	for i := range clips {
		clips[i] = models.Clip{Start: "0", End: "1"}
	}

	tests := []struct {
		name      string
		req       models.ProcessRequest
		wantError string
	}{
		{
			name:      "slice start after end",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "slice", Start: "5", End: "2"}}},
			wantError: "slice invalid",
		},
		{
			name:      "remove_section start equals end",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "remove_section", Start: "3", End: "3"}}},
			wantError: "remove_section invalid",
		},
		{
			name:      "remove_section beyond duration",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "remove_section", Start: "0", End: "11"}}},
			wantError: "remove_section invalid",
		},
		{
			name:      "add_audio missing source",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "add_audio"}}},
			wantError: "audio source video is required",
		},
		{
			name:      "add_audio negative volume",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "add_audio", AudioSourceID: "other", AudioVolume: -1}}},
			wantError: "must not be negative",
		},
		{
			name:      "add_audio huge volume",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "add_audio", AudioSourceID: "other", AudioVolume: 11}}},
			wantError: "too large",
		},
		{
			name:      "add_audio bad offset",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "add_audio", AudioSourceID: "other", Offset: "abc"}}},
			wantError: "invalid offset",
		},
		{
			name:      "add_audio start after end",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "add_audio", AudioSourceID: "other", AudioStart: "5", AudioEnd: "2"}}},
			wantError: "start must be before audio end",
		},
		{
			name:      "concat single clip",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "concat", Clips: []models.Clip{{Start: "0", End: "1"}}}}},
			wantError: "at least two clips",
		},
		{
			name:      "concat too many clips",
			req:       models.ProcessRequest{Operations: []models.Operation{{Type: "concat", Clips: clips}}},
			wantError: "at most 20 clips",
		},
		{
			name: "concat invalid clip start",
			req: models.ProcessRequest{Operations: []models.Operation{{Type: "concat", Clips: []models.Clip{
				{Start: "abc", End: "1"},
				{Start: "0", End: "1"},
			}}}},
			wantError: "invalid start",
		},
		{
			name: "concat clip start after end",
			req: models.ProcessRequest{Operations: []models.Operation{{Type: "concat", Clips: []models.Clip{
				{Start: "5", End: "1"},
				{Start: "0", End: "1"},
			}}}},
			wantError: "start must be before end",
		},
		{
			name: "duplicate concat",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "concat", Clips: []models.Clip{{Start: "0", End: "1"}, {Start: "0", End: "1"}}},
				{Type: "concat", Clips: []models.Clip{{Start: "0", End: "1"}, {Start: "0", End: "1"}}},
			}},
			wantError: "only one concat",
		},
		{
			name: "concat with another operation",
			req: models.ProcessRequest{Operations: []models.Operation{
				{Type: "concat", Clips: []models.Clip{{Start: "0", End: "1"}, {Start: "0", End: "1"}}},
				{Type: "flip", Direction: 1},
			}},
			wantError: "concat must be the only operation",
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

func TestReferencedVideoIDs(t *testing.T) {
	req := models.ProcessRequest{Operations: []models.Operation{
		{Type: "add_audio", AudioSourceID: "audio"},
		{Type: "concat", Clips: []models.Clip{
			{VideoID: "clip-a"},
			{VideoID: "clip-b"},
			{VideoID: ""},
			{VideoID: "audio"},
			{VideoID: "primary"},
		}},
	}}

	got := ReferencedVideoIDs("primary", req)
	want := []string{"audio", "clip-a", "clip-b"}
	if !slices.Equal(got, want) {
		t.Fatalf("ReferencedVideoIDs = %v, want %v", got, want)
	}

	if ids := ReferencedVideoIDs("primary", models.ProcessRequest{}); len(ids) != 0 {
		t.Fatalf("empty request returned %v", ids)
	}
}
