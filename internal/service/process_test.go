package service

import (
	"context"
	"math"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"VideoProject/internal/models"
)

func generateVolumeTestSource(t *testing.T) string {
	t.Helper()

	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is not installed")
	}
	if _, err := exec.LookPath("ffprobe"); err != nil {
		t.Skip("ffprobe is not installed")
	}

	path := filepath.Join(t.TempDir(), "source.mp4")
	command := exec.Command(
		"ffmpeg",
		"-hide_banner",
		"-loglevel", "error",
		"-y",
		"-f", "lavfi",
		"-i", "testsrc2=size=160x120:rate=12:duration=0.5",
		"-f", "lavfi",
		"-i", "sine=frequency=440:sample_rate=44100:duration=0.5",
		"-shortest",
		"-c:v", "libx264",
		"-preset", "ultrafast",
		"-pix_fmt", "yuv420p",
		"-c:a", "aac",
		path,
	)
	if output, err := command.CombinedOutput(); err != nil {
		t.Skipf("could not generate test video with ffmpeg: %v\n%s", err, output)
	}
	return path
}

func TestProcessJobRejectsInvalidVolumeFactors(t *testing.T) {
	sourcePath := generateVolumeTestSource(t)

	for _, factor := range []float64{
		0,
		math.Copysign(0, -1),
		-1,
		math.NaN(),
		math.Inf(1),
		math.Inf(-1),
	} {
		factor := factor
		t.Run(strconv.FormatFloat(factor, 'g', -1, 64), func(t *testing.T) {
			job := &models.Job{ID: "volume-test", SourcePath: sourcePath}
			request := models.ProcessRequest{Operations: []models.Operation{{
				Type:   "volume",
				Factor: factor,
			}}}

			_, err := ProcessJob(context.Background(), job, request, nil)
			if err == nil {
				t.Fatalf("ProcessJob accepted volume factor %v", factor)
			}
			if !strings.Contains(err.Error(), "volume factor must be greater than 0") {
				t.Fatalf("ProcessJob volume error = %q", err)
			}
		})
	}
}
