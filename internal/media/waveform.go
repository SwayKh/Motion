package media

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"math"
	"os/exec"
	"strconv"
	"strings"
)

// ErrNoAudio reports that a source has no usable audio stream. Callers treat it
// as an expected, cacheable outcome rather than a failure.
var ErrNoAudio = errors.New("source has no audio")

// IsNoAudioError reports whether err means the source has no audio.
func IsNoAudioError(err error) bool {
	return errors.Is(err, ErrNoAudio)
}

// Waveform extraction.
//
// The audio is decoded once to raw mono PCM at a low sample rate and reduced to
// a fixed number of peak buckets in Go. Returning normalised peaks (0..1) keeps
// the payload small and lets the timeline draw a crisp waveform at any zoom
// level without shipping the audio to the browser.
const (
	// waveformSampleRate is deliberately low: a waveform only needs enough
	// resolution to draw peaks, and decoding less audio keeps the operation
	// fast even for long videos.
	waveformSampleRate = 8000

	// MinWaveformBuckets and MaxWaveformBuckets bound the peak count.
	MinWaveformBuckets = 200
	MaxWaveformBuckets = 4000
)

// WaveformCountFor picks a peak-bucket count from a source duration.
func WaveformCountFor(duration float64) int {
	if duration <= 0 || math.IsNaN(duration) || math.IsInf(duration, 0) {
		return MinWaveformBuckets
	}
	n := int(math.Round(duration * 8))
	if n < MinWaveformBuckets {
		return MinWaveformBuckets
	}
	if n > MaxWaveformBuckets {
		return MaxWaveformBuckets
	}
	return n
}

// ClampWaveformBuckets keeps a caller supplied bucket count inside range.
func ClampWaveformBuckets(buckets int) int {
	if buckets < MinWaveformBuckets {
		return MinWaveformBuckets
	}
	if buckets > MaxWaveformBuckets {
		return MaxWaveformBuckets
	}
	return buckets
}

// GenerateWaveform decodes the audio track of srcPath and returns buckets
// normalised peak values in the range 0..1. It returns an error when the source
// has no decodable audio stream.
func GenerateWaveform(ctx context.Context, srcPath string, buckets int) ([]float64, error) {
	buckets = ClampWaveformBuckets(buckets)

	args := []string{
		"-hide_banner", "-loglevel", "error",
		"-i", srcPath,
		"-vn",
		"-ac", "1",
		"-ar", strconv.Itoa(waveformSampleRate),
		"-f", "s16le",
		"-",
	}

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		if looksLikeNoAudio(stderr.String()) {
			return nil, fmt.Errorf("%w: %s", ErrNoAudio, strings.TrimSpace(stderr.String()))
		}
		return nil, fmt.Errorf("extracting waveform: %w: %s", err, strings.TrimSpace(stderr.String()))
	}

	samples := len(out) / 2
	if samples == 0 {
		return nil, fmt.Errorf("%w: the source has no audio to analyse", ErrNoAudio)
	}

	peaks := make([]float64, buckets)
	per := float64(samples) / float64(buckets)
	for i := 0; i < buckets; i++ {
		start := int(float64(i) * per)
		end := int(float64(i+1) * per)
		if end > samples {
			end = samples
		}
		if end <= start {
			end = start + 1
		}
		peak := 0
		for j := start; j < end; j++ {
			value := int(int16(binary.LittleEndian.Uint16(out[j*2:])))
			if value < 0 {
				value = -value
			}
			if value > peak {
				peak = value
			}
		}
		peaks[i] = float64(peak) / 32768.0
	}
	return peaks, nil
}

// looksLikeNoAudio inspects FFmpeg's stderr to distinguish "this file simply has
// no audio track" from a genuine decode failure.
func looksLikeNoAudio(stderr string) bool {
	lower := strings.ToLower(stderr)
	markers := []string{
		"does not contain any stream",
		"matches no streams",
		"no such stream",
		"stream map",
		"could not find codec parameters for stream",
	}
	for _, marker := range markers {
		if strings.Contains(lower, marker) {
			return true
		}
	}
	return false
}
