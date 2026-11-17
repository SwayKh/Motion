package media

import (
	"context"
	"fmt"
	"image"
	_ "image/jpeg" // register the JPEG decoder for DecodeConfig
	"math"
	"os"
	"os/exec"
	"strconv"
	"strings"
)

// Thumbnail filmstrip generation.
//
// A strip is a single-row JPEG contact sheet: Count frames sampled evenly
// across the whole source, each scaled to TileHeight and laid out left to
// right. Keeping every frame in one image means the timeline only ever needs a
// single cached request per source, and the browser can position a frame purely
// with CSS background-position/background-size.
const (
	// DefaultThumbnailHeight is the pixel height of each filmstrip tile.
	DefaultThumbnailHeight = 72
	// MinThumbnailCount and MaxThumbnailCount bound the number of sampled
	// frames so a very short or very long source still yields a usable strip.
	MinThumbnailCount = 12
	MaxThumbnailCount = 48
)

// ThumbnailStrip describes a generated filmstrip.
type ThumbnailStrip struct {
	Count      int
	TileWidth  int
	TileHeight int
	Width      int
	Height     int
}

// ThumbnailCountFor picks a frame count from a source duration. The aim is
// roughly one thumbnail per second, clamped so short clips still get a full
// strip and long videos do not explode the sprite size.
func ThumbnailCountFor(duration float64) int {
	if duration <= 0 || math.IsNaN(duration) || math.IsInf(duration, 0) {
		return MinThumbnailCount
	}
	n := int(math.Round(duration))
	if n < MinThumbnailCount {
		return MinThumbnailCount
	}
	if n > MaxThumbnailCount {
		return MaxThumbnailCount
	}
	return n
}

// ClampThumbnailCount keeps a caller supplied frame count inside the supported
// range.
func ClampThumbnailCount(count int) int {
	if count < 1 {
		return MinThumbnailCount
	}
	if count < MinThumbnailCount {
		return MinThumbnailCount
	}
	if count > MaxThumbnailCount {
		return MaxThumbnailCount
	}
	return count
}

// GenerateThumbnailStrip renders count frames sampled evenly across srcPath
// into a single-row JPEG at outPath. duration is the source length in seconds
// and drives the sampling rate; a zero or unknown duration falls back to one
// frame per second.
func GenerateThumbnailStrip(ctx context.Context, srcPath, outPath string, duration float64, count, tileHeight int) (ThumbnailStrip, error) {
	count = ClampThumbnailCount(count)
	if tileHeight < 16 {
		tileHeight = DefaultThumbnailHeight
	}

	rate := 1.0
	if duration > 0 && !math.IsNaN(duration) && !math.IsInf(duration, 0) {
		rate = float64(count) / duration
	}
	if rate <= 0 {
		rate = 1
	}

	filter := fmt.Sprintf(
		"fps=%s,scale=-2:%d,tile=%dx1",
		strconv.FormatFloat(rate, 'f', 6, 64),
		tileHeight,
		count,
	)

	args := []string{
		"-hide_banner", "-loglevel", "error", "-y",
		"-i", srcPath,
		"-an",
		"-vf", filter,
		"-frames:v", "1",
		"-q:v", "5",
		outPath,
	}

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	if output, err := cmd.CombinedOutput(); err != nil {
		return ThumbnailStrip{}, fmt.Errorf("generating thumbnails: %w: %s", err, strings.TrimSpace(string(output)))
	}

	width, height, err := ProbeImageSize(outPath)
	if err != nil {
		return ThumbnailStrip{}, err
	}

	strip := ThumbnailStrip{
		Count:      count,
		TileHeight: height,
		Width:      width,
		Height:     height,
	}
	if count > 0 {
		strip.TileWidth = width / count
	}
	if strip.TileWidth <= 0 {
		strip.TileWidth = width
	}
	return strip, nil
}

// ProbeImageSize reads the pixel dimensions of an image file without decoding
// the whole picture.
func ProbeImageSize(path string) (int, int, error) {
	file, err := os.Open(path)
	if err != nil {
		return 0, 0, err
	}
	defer file.Close()

	cfg, _, err := image.DecodeConfig(file)
	if err != nil {
		return 0, 0, fmt.Errorf("reading image size: %w", err)
	}
	return cfg.Width, cfg.Height, nil
}
