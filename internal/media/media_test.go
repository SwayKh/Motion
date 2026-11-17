package media

import (
	"context"
	"math"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestThumbnailCountFor(t *testing.T) {
	cases := []struct {
		duration float64
		want     int
	}{
		{0, MinThumbnailCount},
		{-1, MinThumbnailCount},
		{math.NaN(), MinThumbnailCount},
		{1, MinThumbnailCount},
		{5, 12},
		{12, 12},
		{30, 30},
		{60, 48},
		{3600, 48},
	}
	for _, c := range cases {
		if got := ThumbnailCountFor(c.duration); got != c.want {
			t.Errorf("ThumbnailCountFor(%v) = %d, want %d", c.duration, got, c.want)
		}
	}
}

func TestClampThumbnailCount(t *testing.T) {
	if got := ClampThumbnailCount(0); got != MinThumbnailCount {
		t.Errorf("ClampThumbnailCount(0) = %d, want %d", got, MinThumbnailCount)
	}
	if got := ClampThumbnailCount(1000); got != MaxThumbnailCount {
		t.Errorf("ClampThumbnailCount(1000) = %d, want %d", got, MaxThumbnailCount)
	}
	if got := ClampThumbnailCount(20); got != 20 {
		t.Errorf("ClampThumbnailCount(20) = %d, want 20", got)
	}
}

func TestWaveformCountFor(t *testing.T) {
	if got := WaveformCountFor(0); got != MinWaveformBuckets {
		t.Errorf("WaveformCountFor(0) = %d, want %d", got, MinWaveformBuckets)
	}
	if got := WaveformCountFor(100); got != 800 {
		t.Errorf("WaveformCountFor(100) = %d, want 800", got)
	}
	if got := WaveformCountFor(100000); got != MaxWaveformBuckets {
		t.Errorf("WaveformCountFor(100000) = %d, want %d", got, MaxWaveformBuckets)
	}
}

func TestClampWaveformBuckets(t *testing.T) {
	if got := ClampWaveformBuckets(1); got != MinWaveformBuckets {
		t.Errorf("ClampWaveformBuckets(1) = %d, want %d", got, MinWaveformBuckets)
	}
	if got := ClampWaveformBuckets(1e9); got != MaxWaveformBuckets {
		t.Errorf("ClampWaveformBuckets(1e9) = %d, want %d", got, MaxWaveformBuckets)
	}
	if got := ClampWaveformBuckets(500); got != 500 {
		t.Errorf("ClampWaveformBuckets(500) = %d, want 500", got)
	}
}

func TestGenerateThumbnailStrip(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is not installed")
	}
	dir := t.TempDir()
	src := filepath.Join(dir, "src.mp4")
	if out, err := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24:duration=4",
		"-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", src).CombinedOutput(); err != nil {
		t.Skipf("could not generate test video: %v\n%s", err, out)
	}

	out := filepath.Join(dir, "strip.jpg")
	strip, err := GenerateThumbnailStrip(context.Background(), src, out, 4, 12, DefaultThumbnailHeight)
	if err != nil {
		t.Fatalf("GenerateThumbnailStrip: %v", err)
	}
	if strip.Count != 12 {
		t.Fatalf("count = %d, want 12", strip.Count)
	}
	if strip.TileHeight != DefaultThumbnailHeight {
		t.Fatalf("tile height = %d, want %d", strip.TileHeight, DefaultThumbnailHeight)
	}
	if strip.TileWidth <= 0 || strip.Width != strip.TileWidth*strip.Count {
		t.Fatalf("bad geometry: %+v", strip)
	}
}

func TestGenerateWaveformAudioAndSilence(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg is not installed")
	}
	dir := t.TempDir()

	withAudio := filepath.Join(dir, "audio.mp4")
	if out, err := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=160x120:rate=24:duration=2",
		"-f", "lavfi", "-i", "sine=frequency=440:duration=2",
		"-shortest", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", withAudio).CombinedOutput(); err != nil {
		t.Skipf("could not generate audio video: %v\n%s", err, out)
	}

	peaks, err := GenerateWaveform(context.Background(), withAudio, 200)
	if err != nil {
		t.Fatalf("GenerateWaveform: %v", err)
	}
	if len(peaks) != 200 {
		t.Fatalf("peaks = %d, want 200", len(peaks))
	}
	var max float64
	for _, p := range peaks {
		if p < 0 || p > 1 {
			t.Fatalf("peak %v out of range 0..1", p)
		}
		if p > max {
			max = p
		}
	}
	if max <= 0 {
		t.Fatal("expected a non-zero peak for a sine tone")
	}

	silent := filepath.Join(dir, "silent.mp4")
	if out, err := exec.Command("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
		"-f", "lavfi", "-i", "testsrc2=size=160x120:rate=24:duration=2",
		"-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", silent).CombinedOutput(); err != nil {
		t.Skipf("could not generate silent video: %v\n%s", err, out)
	}

	if _, err := GenerateWaveform(context.Background(), silent, 200); !IsNoAudioError(err) {
		t.Fatalf("expected ErrNoAudio for a silent clip, got %v", err)
	}
}
