package service

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/mediacrypt"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/storage"
)

func requireFFmpegTools(t *testing.T) {
	t.Helper()
	for _, tool := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Skipf("%s is not installed", tool)
		}
	}
}

func generateEditSource(t *testing.T, path string, withAudio bool) {
	t.Helper()

	args := []string{
		"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24:duration=2",
	}
	if withAudio {
		args = append(args, "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2", "-shortest")
	}
	args = append(args, "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p")
	if withAudio {
		args = append(args, "-c:a", "aac")
	}
	args = append(args, path)

	if output, err := exec.Command("ffmpeg", args...).CombinedOutput(); err != nil {
		t.Skipf("could not generate test video: %v\n%s", err, output)
	}
}

func editStorage(t *testing.T, encrypt bool) *storage.Manager {
	t.Helper()

	dir := t.TempDir()
	cfg := &config.Config{
		UploadDir: filepath.Join(dir, "uploads"),
		OutputDir: filepath.Join(dir, "outputs"),
		TempDir:   filepath.Join(dir, "tmp"),
	}
	if encrypt {
		key, err := mediacrypt.GenerateMasterKey()
		if err != nil {
			t.Fatalf("generate master key: %v", err)
		}
		cfg.EncryptionKey = key
	}

	st, err := storage.New(cfg)
	if err != nil {
		t.Fatalf("new storage: %v", err)
	}
	if err := st.EnsureDirs(); err != nil {
		t.Fatalf("ensure dirs: %v", err)
	}
	return st
}

func runEditJob(t *testing.T, st *storage.Manager, video *models.Video, ops []models.Operation, resolve SourceResolver) (ProcessResult, error) {
	t.Helper()

	jobID := strings.NewReplacer("/", "-", " ", "-").Replace(t.Name())
	return ProcessJob(context.Background(), ProcessInput{
		Job:     &models.Job{ID: jobID, VideoID: video.ID},
		Video:   video,
		Storage: st,
		Resolve: resolve,
		Request: models.ProcessRequest{Operations: ops},
	})
}

func probeDuration(t *testing.T, path string) float64 {
	t.Helper()

	output, err := exec.Command(
		"ffprobe", "-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=nw=1:nk=1",
		path,
	).Output()
	if err != nil {
		t.Fatalf("probe duration: %v", err)
	}
	value, err := strconv.ParseFloat(strings.TrimSpace(string(output)), 64)
	if err != nil {
		t.Fatalf("parse duration %q: %v", output, err)
	}
	return value
}

func assertPlayable(t *testing.T, path string) {
	t.Helper()

	if output, err := exec.Command(
		"ffmpeg", "-hide_banner", "-loglevel", "error",
		"-i", path, "-f", "null", "-",
	).CombinedOutput(); err != nil {
		t.Fatalf("decode output media: %v\n%s", err, output)
	}
}

func TestProcessJobSliceAndRemoveSection(t *testing.T) {
	requireFFmpegTools(t)
	st := editStorage(t, false)

	src := filepath.Join(t.TempDir(), "src.mp4")
	generateEditSource(t, src, true)
	video := &models.Video{ID: "src", OriginalPath: src, Duration: 2, Width: 320, Height: 240}

	t.Run("slice", func(t *testing.T) {
		result, err := runEditJob(t, st, video, []models.Operation{{Type: "slice", Start: "0", End: "1"}}, nil)
		if err != nil {
			t.Fatalf("slice: %v", err)
		}
		if result.OutputType != "video" {
			t.Fatalf("output type = %q, want video", result.OutputType)
		}
		assertPlayable(t, result.OutputPath)
		if d := probeDuration(t, result.OutputPath); d > 1.5 {
			t.Fatalf("slice duration = %.2f, want <= 1.5", d)
		}
	})

	t.Run("remove_section", func(t *testing.T) {
		result, err := runEditJob(t, st, video, []models.Operation{{Type: "remove_section", Start: "0.5", End: "1"}}, nil)
		if err != nil {
			t.Fatalf("remove_section: %v", err)
		}
		assertPlayable(t, result.OutputPath)
		if d := probeDuration(t, result.OutputPath); d < 1.0 || d > 1.9 {
			t.Fatalf("remove_section duration = %.2f, want ~1.5", d)
		}
	})
}

func TestProcessJobExtractAudio(t *testing.T) {
	requireFFmpegTools(t)
	st := editStorage(t, false)

	src := filepath.Join(t.TempDir(), "src.mp4")
	generateEditSource(t, src, true)
	video := &models.Video{ID: "src", OriginalPath: src, Duration: 2, Width: 320, Height: 240}

	result, err := runEditJob(t, st, video, []models.Operation{{Type: "to_audio"}}, nil)
	if err != nil {
		t.Fatalf("to_audio: %v", err)
	}
	if result.OutputType != "audio" {
		t.Fatalf("output type = %q, want audio", result.OutputType)
	}
	assertPlayable(t, result.OutputPath)
}

func TestProcessJobAddAudio(t *testing.T) {
	requireFFmpegTools(t)
	st := editStorage(t, false)

	src := filepath.Join(t.TempDir(), "video.mp4")
	generateEditSource(t, src, true)
	audioSrc := filepath.Join(t.TempDir(), "audio.mp4")
	generateEditSource(t, audioSrc, true)

	video := &models.Video{ID: "src", OriginalPath: src, Duration: 2, Width: 320, Height: 240}
	audioVideo := &models.Video{ID: "audio", OriginalPath: audioSrc, Duration: 2, Width: 320, Height: 240}
	resolve := func(id string) (*models.Video, error) {
		if id == "audio" {
			return audioVideo, nil
		}
		return nil, fmt.Errorf("unknown video %q", id)
	}

	result, err := runEditJob(t, st, video, []models.Operation{
		{Type: "add_audio", AudioSourceID: "audio", AudioVolume: 0.5},
	}, resolve)
	if err != nil {
		t.Fatalf("add_audio: %v", err)
	}
	assertPlayable(t, result.OutputPath)
}

func TestProcessJobConcatClips(t *testing.T) {
	requireFFmpegTools(t)
	st := editStorage(t, false)

	src := filepath.Join(t.TempDir(), "a.mp4")
	generateEditSource(t, src, true)
	silent := filepath.Join(t.TempDir(), "b.mp4")
	generateEditSource(t, silent, false)

	video := &models.Video{ID: "src", OriginalPath: src, Duration: 2, Width: 320, Height: 240}
	silentVideo := &models.Video{ID: "other", OriginalPath: silent, Duration: 2, Width: 320, Height: 240}
	resolve := func(id string) (*models.Video, error) {
		if id == "other" {
			return silentVideo, nil
		}
		return nil, fmt.Errorf("unknown video %q", id)
	}

	ops := []models.Operation{{Type: "concat", Clips: []models.Clip{
		{Start: "0", End: "0.5"},
		{VideoID: "other", Start: "0", End: "0.5"},
	}}}
	result, err := runEditJob(t, st, video, ops, resolve)
	if err != nil {
		t.Fatalf("concat: %v", err)
	}
	assertPlayable(t, result.OutputPath)
	if d := probeDuration(t, result.OutputPath); d < 0.6 || d > 1.6 {
		t.Fatalf("concat duration = %.2f, want ~1.0", d)
	}
}

func generateSolidSource(t *testing.T, path, color string, width, height int, duration float64, withAudio bool) {
	t.Helper()

	args := []string{
		"-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", fmt.Sprintf("color=c=%s:s=%dx%d:r=24:d=%g", color, width, height, duration),
	}
	if withAudio {
		args = append(args, "-f", "lavfi", "-i", fmt.Sprintf("sine=frequency=440:sample_rate=44100:duration=%g", duration), "-shortest")
	}
	args = append(args, "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p")
	if withAudio {
		args = append(args, "-c:a", "aac")
	}
	args = append(args, path)

	if output, err := exec.Command("ffmpeg", args...).CombinedOutput(); err != nil {
		t.Skipf("could not generate solid video: %v\n%s", err, output)
	}
}

// probePixel decodes a single pixel at the given time and position.
func probePixel(t *testing.T, path string, at float64, x, y int) [3]byte {
	t.Helper()

	output, err := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error",
		"-ss", strconv.FormatFloat(at, 'f', 3, 64),
		"-i", path,
		"-vf", fmt.Sprintf("crop=1:1:%d:%d,format=rgb24", x, y),
		"-frames:v", "1", "-f", "rawvideo", "-",
	).Output()
	if err != nil || len(output) < 3 {
		t.Fatalf("probe pixel: err=%v len=%d", err, len(output))
	}
	return [3]byte{output[0], output[1], output[2]}
}

func TestProcessJobComposeOverlayAndText(t *testing.T) {
	requireFFmpegTools(t)
	st := editStorage(t, false)

	base := filepath.Join(t.TempDir(), "base.mp4")
	generateSolidSource(t, base, "black", 320, 240, 2, true)
	overlay := filepath.Join(t.TempDir(), "pip.mp4")
	generateSolidSource(t, overlay, "red", 320, 240, 1, false)

	video := &models.Video{ID: "src", OriginalPath: base, Duration: 2, Width: 320, Height: 240}
	overlayVideo := &models.Video{ID: "pip", OriginalPath: overlay, Duration: 1, Width: 320, Height: 240}
	resolve := func(id string) (*models.Video, error) {
		if id == "pip" {
			return overlayVideo, nil
		}
		return nil, fmt.Errorf("unknown video %q", id)
	}

	plan := &models.ComposePlan{
		Width: 320, Height: 240, FPS: 24, Duration: "2", HasAudio: true,
		Layers: []models.ComposeLayer{
			{Kind: "video", VideoID: "src", Base: true, SourceIn: "0", SourceOut: "2", TimelineStart: "0", TimelineEnd: "2", Opacity: 1, Scale: 1},
			{Kind: "video", VideoID: "pip", SourceIn: "0", SourceOut: "1", TimelineStart: "0.5", TimelineEnd: "1.5", X: 0, Y: 0, Opacity: 1, Scale: 0.5},
			{Kind: "text", Text: "Hi there", TimelineStart: "0", TimelineEnd: "2", X: 4, Y: 200, Opacity: 1, Scale: 1},
		},
		Audio: []models.ComposeAudio{
			{VideoID: "src", SourceIn: "0", SourceOut: "2", TimelineStart: "0", Volume: 1},
		},
	}

	result, err := runEditJob(t, st, video, []models.Operation{{Type: "compose", Compose: plan}}, resolve)
	if err != nil {
		t.Fatalf("compose: %v", err)
	}
	assertPlayable(t, result.OutputPath)
	if d := probeDuration(t, result.OutputPath); d < 1.8 || d > 2.3 {
		t.Fatalf("compose duration = %.2f, want ~2", d)
	}

	// Inside the picture-in-picture window the top-left corner is the red
	// overlay; outside it the black base shows through.
	inside := probePixel(t, result.OutputPath, 1.0, 10, 10)
	if inside[0] < 180 || inside[1] > 80 || inside[2] > 80 {
		t.Fatalf("expected red overlay at t=1.0, got %v", inside)
	}
	outside := probePixel(t, result.OutputPath, 0.1, 10, 10)
	if outside[0] > 40 || outside[1] > 40 || outside[2] > 40 {
		t.Fatalf("expected black base at t=0.1, got %v", outside)
	}
}

func TestProcessJobComposeMultiClipWithAudio(t *testing.T) {
	requireFFmpegTools(t)
	st := editStorage(t, false)

	first := filepath.Join(t.TempDir(), "a.mp4")
	generateSolidSource(t, first, "black", 320, 240, 1, true)
	second := filepath.Join(t.TempDir(), "b.mp4")
	generateSolidSource(t, second, "white", 320, 240, 1, true)

	video := &models.Video{ID: "src", OriginalPath: first, Duration: 1, Width: 320, Height: 240}
	secondVideo := &models.Video{ID: "b", OriginalPath: second, Duration: 1, Width: 320, Height: 240}
	resolve := func(id string) (*models.Video, error) {
		if id == "b" {
			return secondVideo, nil
		}
		return nil, fmt.Errorf("unknown video %q", id)
	}

	// Two background clips back to back plus a music bed: the case the simple
	// pipeline used to reject.
	plan := &models.ComposePlan{
		Width: 320, Height: 240, FPS: 24, Duration: "2",
		Layers: []models.ComposeLayer{
			{Kind: "video", VideoID: "src", Base: true, SourceIn: "0", SourceOut: "1", TimelineStart: "0", TimelineEnd: "1", Opacity: 1, Scale: 1},
			{Kind: "video", VideoID: "b", Base: true, SourceIn: "0", SourceOut: "1", TimelineStart: "1", TimelineEnd: "2", Opacity: 1, Scale: 1},
		},
		Audio: []models.ComposeAudio{
			{VideoID: "src", SourceIn: "0", SourceOut: "1", TimelineStart: "0", Volume: 0.8},
			{VideoID: "b", SourceIn: "0", SourceOut: "1", TimelineStart: "1", Volume: 0.8},
		},
	}

	result, err := runEditJob(t, st, video, []models.Operation{{Type: "compose", Compose: plan}}, resolve)
	if err != nil {
		t.Fatalf("compose: %v", err)
	}
	assertPlayable(t, result.OutputPath)
	if d := probeDuration(t, result.OutputPath); d < 1.8 || d > 2.3 {
		t.Fatalf("compose duration = %.2f, want ~2", d)
	}
	// The second clip replaces the first at t=1.5.
	early := probePixel(t, result.OutputPath, 0.5, 160, 120)
	if early[0] > 60 || early[1] > 60 || early[2] > 60 {
		t.Fatalf("expected black first clip, got %v", early)
	}
	late := probePixel(t, result.OutputPath, 1.5, 160, 120)
	if late[0] < 180 || late[1] < 180 || late[2] < 180 {
		t.Fatalf("expected white second clip, got %v", late)
	}
}

func TestProcessJobEncryptedStorageRoundTrip(t *testing.T) {
	requireFFmpegTools(t)
	st := editStorage(t, true)
	plain := filepath.Join(t.TempDir(), "src.mp4")
	generateEditSource(t, plain, true)

	storedPath, encrypted, key, err := st.StoreUpload(plain, "src.mp4")
	if err != nil {
		t.Fatalf("store upload: %v", err)
	}
	if !encrypted || key == "" {
		t.Fatalf("source was not encrypted: encrypted=%v key=%q", encrypted, key)
	}

	video := &models.Video{
		ID:           "src",
		OriginalPath: storedPath,
		Encrypted:    encrypted,
		EncKey:       key,
		Duration:     2,
		Width:        320,
		Height:       240,
	}
	result, err := runEditJob(t, st, video, []models.Operation{{Type: "slice", Start: "0", End: "1"}}, nil)
	if err != nil {
		t.Fatalf("process encrypted source: %v", err)
	}
	if !result.Encrypted || result.EncKey == "" {
		t.Fatalf("output was not encrypted: %+v", result)
	}

	decrypted, cleanup, err := st.DecryptToTemp(result.OutputPath, result.EncKey, result.Encrypted)
	if err != nil {
		t.Fatalf("decrypt output: %v", err)
	}
	defer cleanup()
	if _, err := os.Stat(decrypted); err != nil {
		t.Fatalf("decrypted output missing: %v", err)
	}
	assertPlayable(t, decrypted)
}
