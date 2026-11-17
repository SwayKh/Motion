package api

import (
	"context"
	"encoding/json"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/media"
	"github.com/SwayKh/Motion/internal/models"
)

// thumbnailResponse is the metadata the editor needs to position a filmstrip on
// the timeline. The sprite itself is fetched from spriteUrl.
type thumbnailResponse struct {
	Count      int     `json:"count"`
	TileWidth  int     `json:"tileWidth"`
	TileHeight int     `json:"tileHeight"`
	Width      int     `json:"width"`
	Height     int     `json:"height"`
	Duration   float64 `json:"duration"`
	SpriteURL  string  `json:"spriteUrl"`
}

// waveformResponse carries normalised peak values (0..1) for an audio track.
type waveformResponse struct {
	Buckets  int       `json:"buckets"`
	Duration float64   `json:"duration"`
	Audio    bool      `json:"audio"`
	Peaks    []float64 `json:"peaks"`
}

// waveformCache is the on-disk representation of a cached waveform. A negative
// entry (Audio=false) records that a source has no usable audio so the editor
// does not re-run FFmpeg on every page load.
type waveformCache struct {
	Buckets  int       `json:"buckets"`
	Duration float64   `json:"duration"`
	Audio    bool      `json:"audio"`
	Peaks    []float64 `json:"peaks"`
}

// previewVideo loads and authorises the video referenced by the request path.
// It writes the error response itself when the video is missing or not owned.
func (d *Deps) previewVideo(w http.ResponseWriter, r *http.Request) (*models.Video, bool) {
	user := getUser(r)
	video, err := d.Videos.Get(r.PathValue("id"))
	if err != nil || !videoAccessible(video, user) {
		writeError(w, http.StatusNotFound, "video not found")
		return nil, false
	}
	if video.OriginalPath == "" {
		writeError(w, http.StatusNotFound, "video is not available")
		return nil, false
	}
	return video, true
}

func requestThumbnailCount(r *http.Request, duration float64) int {
	count := media.ThumbnailCountFor(duration)
	if q := strings.TrimSpace(r.URL.Query().Get("count")); q != "" {
		if n, err := strconv.Atoi(q); err == nil {
			count = media.ClampThumbnailCount(n)
		}
	}
	return count
}

func requestWaveformBuckets(r *http.Request, duration float64) int {
	buckets := media.WaveformCountFor(duration)
	if q := strings.TrimSpace(r.URL.Query().Get("buckets")); q != "" {
		if n, err := strconv.Atoi(q); err == nil {
			buckets = media.ClampWaveformBuckets(n)
		}
	}
	return buckets
}

// ensureThumbnailStrip returns the cached filmstrip, generating and caching it
// on first use. The source is decrypted to a scratch file only when generation
// is actually required.
func (d *Deps) ensureThumbnailStrip(ctx context.Context, video *models.Video, count, height int) (media.ThumbnailStrip, string, error) {
	count = media.ClampThumbnailCount(count)
	if height <= 0 {
		height = media.DefaultThumbnailHeight
	}

	path := d.Previews.StripPath(video.ID, count, height)
	if strip, ok := probeStrip(path, count); ok {
		return strip, path, nil
	}

	unlock := d.Previews.Lock("strip:" + path)
	defer unlock()

	if strip, ok := probeStrip(path, count); ok {
		return strip, path, nil
	}

	if err := d.Previews.EnsureDir(); err != nil {
		return media.ThumbnailStrip{}, "", err
	}

	srcPath, cleanup, err := d.Storage.DecryptToTemp(video.OriginalPath, video.EncKey, video.Encrypted)
	if err != nil {
		return media.ThumbnailStrip{}, "", err
	}
	defer cleanup()

	tmp, err := os.CreateTemp(d.Previews.Dir(), "strip-*.jpg")
	if err != nil {
		return media.ThumbnailStrip{}, "", err
	}
	tmpPath := tmp.Name()
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return media.ThumbnailStrip{}, "", err
	}
	defer os.Remove(tmpPath)

	strip, err := media.GenerateThumbnailStrip(ctx, srcPath, tmpPath, video.Duration, count, height)
	if err != nil {
		return media.ThumbnailStrip{}, "", err
	}

	if err := os.Rename(tmpPath, path); err != nil {
		// A cross-device rename can fail; copy the bytes instead.
		if copyErr := copyFile(tmpPath, path); copyErr != nil {
			return media.ThumbnailStrip{}, "", err
		}
	}
	return strip, path, nil
}

// probeStrip loads metadata for an existing cached filmstrip.
func probeStrip(path string, count int) (media.ThumbnailStrip, bool) {
	if path == "" {
		return media.ThumbnailStrip{}, false
	}
	if info, err := os.Stat(path); err != nil || info.Size() == 0 {
		return media.ThumbnailStrip{}, false
	}
	width, height, err := media.ProbeImageSize(path)
	if err != nil || width <= 0 || height <= 0 {
		return media.ThumbnailStrip{}, false
	}
	strip := media.ThumbnailStrip{
		Count:      count,
		TileWidth:  width / count,
		TileHeight: height,
		Width:      width,
		Height:     height,
	}
	if strip.TileWidth <= 0 {
		return media.ThumbnailStrip{}, false
	}
	return strip, true
}

// ensureWaveform returns cached waveform peaks, generating them on first use.
func (d *Deps) ensureWaveform(ctx context.Context, video *models.Video, buckets int) (waveformCache, error) {
	buckets = media.ClampWaveformBuckets(buckets)
	path := d.Previews.WaveformPath(video.ID, buckets)

	if cached, ok := readWaveformCache(path); ok {
		return cached, nil
	}

	unlock := d.Previews.Lock("wave:" + path)
	defer unlock()

	if cached, ok := readWaveformCache(path); ok {
		return cached, nil
	}

	if err := d.Previews.EnsureDir(); err != nil {
		return waveformCache{}, err
	}

	srcPath, cleanup, err := d.Storage.DecryptToTemp(video.OriginalPath, video.EncKey, video.Encrypted)
	if err != nil {
		return waveformCache{}, err
	}
	defer cleanup()

	cache := waveformCache{Buckets: buckets, Duration: video.Duration}
	peaks, err := media.GenerateWaveform(ctx, srcPath, buckets)
	if err != nil {
		if !media.IsNoAudioError(err) {
			return waveformCache{}, err
		}
		cache.Audio = false
		cache.Peaks = []float64{}
	} else {
		cache.Audio = true
		cache.Peaks = peaks
	}

	if payload, mErr := json.Marshal(cache); mErr == nil {
		_ = os.WriteFile(path, payload, 0o644)
	}
	return cache, nil
}

func readWaveformCache(path string) (waveformCache, bool) {
	payload, err := os.ReadFile(path)
	if err != nil {
		return waveformCache{}, false
	}
	var cache waveformCache
	if err := json.Unmarshal(payload, &cache); err != nil {
		return waveformCache{}, false
	}
	if cache.Peaks == nil {
		cache.Peaks = []float64{}
	}
	return cache, true
}

// ThumbnailsHandler returns filmstrip metadata for a video.
func ThumbnailsHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		video, ok := d.previewVideo(w, r)
		if !ok {
			return
		}
		count := requestThumbnailCount(r, video.Duration)
		strip, _, err := d.ensureThumbnailStrip(r.Context(), video, count, media.DefaultThumbnailHeight)
		if err != nil {
			logger.Error.Printf("generating thumbnails video=%s: %v", video.ID, err)
			writeError(w, http.StatusUnprocessableEntity, "could not generate thumbnails for this video")
			return
		}
		writeJSON(w, http.StatusOK, thumbnailResponse{
			Count:      strip.Count,
			TileWidth:  strip.TileWidth,
			TileHeight: strip.TileHeight,
			Width:      strip.Width,
			Height:     strip.Height,
			Duration:   video.Duration,
			SpriteURL:  "/api/videos/" + video.ID + "/thumbnails/sprite?count=" + strconv.Itoa(strip.Count),
		})
	}
}

// ThumbnailSpriteHandler streams the cached filmstrip image.
func ThumbnailSpriteHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		video, ok := d.previewVideo(w, r)
		if !ok {
			return
		}
		count := requestThumbnailCount(r, video.Duration)
		_, path, err := d.ensureThumbnailStrip(r.Context(), video, count, media.DefaultThumbnailHeight)
		if err != nil {
			logger.Error.Printf("serving thumbnail sprite video=%s: %v", video.ID, err)
			writeError(w, http.StatusUnprocessableEntity, "could not generate thumbnails for this video")
			return
		}

		file, err := os.Open(path)
		if err != nil {
			writeError(w, http.StatusNotFound, "thumbnails are not available")
			return
		}
		defer file.Close()

		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "private, max-age=3600")
		http.ServeContent(w, r, "thumbnails.jpg", time.Time{}, file)
	}
}

// WaveformHandler returns normalised waveform peaks for a video's audio track.
func WaveformHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		video, ok := d.previewVideo(w, r)
		if !ok {
			return
		}
		buckets := requestWaveformBuckets(r, video.Duration)
		cache, err := d.ensureWaveform(r.Context(), video, buckets)
		if err != nil {
			logger.Error.Printf("generating waveform video=%s: %v", video.ID, err)
			writeError(w, http.StatusUnprocessableEntity, "could not analyse the audio for this video")
			return
		}
		writeJSON(w, http.StatusOK, waveformResponse{
			Buckets:  cache.Buckets,
			Duration: cache.Duration,
			Audio:    cache.Audio,
			Peaks:    cache.Peaks,
		})
	}
}

// copyFile copies src to dst, creating dst with private permissions.
func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()

	out, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	if _, err := out.ReadFrom(in); err != nil {
		out.Close()
		_ = os.Remove(dst)
		return err
	}
	return out.Close()
}
