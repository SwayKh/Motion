package api

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/SwayKh/Motion/internal/models"
)

// seedRealVideo seeds a video record and replaces its (fake) original file with
// a real, decodable clip so the preview generators have something to work with.
func seedRealVideo(t *testing.T, e *testEnv, withAudio bool) *models.Video {
	t.Helper()

	video := e.seedVideo(t, models.StatusUploaded)
	path := filepath.Join(e.dir, "uploads", video.ID+".mp4")
	if withAudio {
		generateTestVideo(t, path)
	} else {
		generateTestVideoWithoutAudio(t, path)
	}
	return video
}

func TestThumbnailsAndWaveformEndpoints(t *testing.T) {
	requireVideoTools(t)
	e := newTestEnv(t)

	video := seedRealVideo(t, e, true)

	// Metadata endpoint.
	resp, body := doRequest(t, http.MethodGet, e.url("/api/videos/"+video.ID+"/thumbnails"), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("thumbnails status = %d, body=%s", resp.StatusCode, body)
	}
	meta := decodeJSON[struct {
		Count      int    `json:"count"`
		TileWidth  int    `json:"tileWidth"`
		TileHeight int    `json:"tileHeight"`
		Width      int    `json:"width"`
		Height     int    `json:"height"`
		SpriteURL  string `json:"spriteUrl"`
	}](t, body)
	if meta.Count < 12 {
		t.Fatalf("thumbnail count = %d, want >= 12", meta.Count)
	}
	if meta.TileHeight != 72 {
		t.Fatalf("tile height = %d, want 72", meta.TileHeight)
	}
	if meta.TileWidth <= 0 || meta.Width <= 0 {
		t.Fatalf("invalid sprite geometry: %+v", meta)
	}
	if meta.Width != meta.TileWidth*meta.Count {
		t.Fatalf("sprite width %d != tileWidth %d * count %d", meta.Width, meta.TileWidth, meta.Count)
	}
	if meta.SpriteURL == "" {
		t.Fatal("spriteUrl is empty")
	}

	// Sprite endpoint serves a JPEG.
	resp, body = doRequest(t, http.MethodGet, e.url(meta.SpriteURL), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("sprite status = %d, body=%s", resp.StatusCode, body)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "image/jpeg" {
		t.Fatalf("sprite content type = %q, want image/jpeg", ct)
	}
	if len(body) == 0 {
		t.Fatal("sprite body is empty")
	}
	if body[0] != 0xFF || body[1] != 0xD8 {
		t.Fatalf("sprite is not a JPEG (magic %x)", body[:2])
	}

	// Waveform endpoint reports audio with peaks.
	resp, body = doRequest(t, http.MethodGet, e.url("/api/videos/"+video.ID+"/waveform"), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("waveform status = %d, body=%s", resp.StatusCode, body)
	}
	wave := decodeJSON[struct {
		Buckets int       `json:"buckets"`
		Audio   bool      `json:"audio"`
		Peaks   []float64 `json:"peaks"`
	}](t, body)
	if !wave.Audio {
		t.Fatal("expected audio=true for a clip with an audio track")
	}
	if len(wave.Peaks) != wave.Buckets || wave.Buckets < 200 {
		t.Fatalf("waveform buckets = %d, peaks = %d", wave.Buckets, len(wave.Peaks))
	}
}

func TestPreviewCacheIsReused(t *testing.T) {
	requireVideoTools(t)
	e := newTestEnv(t)

	video := seedRealVideo(t, e, true)

	resp, body := doRequest(t, http.MethodGet, e.url("/api/videos/"+video.ID+"/thumbnails"), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("thumbnails status = %d, body=%s", resp.StatusCode, body)
	}

	cachePath := filepath.Join(e.dir, "data", "previews", "strip_"+video.ID+"_12_72.jpg")
	if _, err := os.Stat(cachePath); err != nil {
		// The count depends on the seeded duration; discover the real path.
		matches, _ := filepath.Glob(filepath.Join(e.dir, "data", "previews", "strip_"+video.ID+"_*.jpg"))
		if len(matches) == 0 {
			t.Fatalf("no cached filmstrip found: %v", err)
		}
		cachePath = matches[0]
	}

	// Force a distinguishable timestamp and confirm a second request does not
	// rewrite the cache file.
	past := time.Now().Add(-time.Hour)
	if err := os.Chtimes(cachePath, past, past); err != nil {
		t.Fatalf("chtimes: %v", err)
	}

	resp, body = doRequest(t, http.MethodGet, e.url("/api/videos/"+video.ID+"/thumbnails"), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("second thumbnails status = %d, body=%s", resp.StatusCode, body)
	}

	second, err := os.Stat(cachePath)
	if err != nil {
		t.Fatalf("stat cached filmstrip after second request: %v", err)
	}
	if !second.ModTime().Equal(past) {
		t.Fatalf("cache was regenerated: mtime changed from %v to %v", past, second.ModTime())
	}
}

func TestWaveformWithoutAudio(t *testing.T) {
	requireVideoTools(t)
	e := newTestEnv(t)

	video := seedRealVideo(t, e, false)

	resp, body := doRequest(t, http.MethodGet, e.url("/api/videos/"+video.ID+"/waveform"), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("waveform status = %d, body=%s", resp.StatusCode, body)
	}
	var raw map[string]any
	if err := json.Unmarshal(body, &raw); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if audio, _ := raw["audio"].(bool); audio {
		t.Fatalf("expected audio=false for a silent clip, body=%s", body)
	}

	// A negative cache entry must exist so the next load skips FFmpeg.
	matches, _ := filepath.Glob(filepath.Join(e.dir, "data", "previews", "wave_"+video.ID+"_*.json"))
	if len(matches) == 0 {
		t.Fatal("no cached waveform entry was written")
	}
}

func TestPreviewEndpointsRequireOwnership(t *testing.T) {
	e := newTestEnv(t)

	// A video that does not exist must 404 rather than leak that it is absent
	// or present.
	for _, path := range []string{
		"/api/videos/00000000-0000-0000-0000-000000000000/thumbnails",
		"/api/videos/00000000-0000-0000-0000-000000000000/thumbnails/sprite",
		"/api/videos/00000000-0000-0000-0000-000000000000/waveform",
	} {
		resp, body := doRequest(t, http.MethodGet, e.url(path), "", "")
		if resp.StatusCode != http.StatusNotFound {
			t.Fatalf("%s status = %d, want 404, body=%s", path, resp.StatusCode, body)
		}
	}
}

func TestDeleteVideoRemovesPreviews(t *testing.T) {
	requireVideoTools(t)
	e := newTestEnv(t)

	video := seedRealVideo(t, e, true)

	resp, body := doRequest(t, http.MethodGet, e.url("/api/videos/"+video.ID+"/thumbnails"), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("thumbnails status = %d, body=%s", resp.StatusCode, body)
	}

	resp, body = doRequest(t, http.MethodDelete, e.url("/api/videos/"+video.ID), "", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("delete status = %d, body=%s", resp.StatusCode, body)
	}

	matches, _ := filepath.Glob(filepath.Join(e.dir, "data", "previews", "*_"+video.ID+"_*"))
	if len(matches) != 0 {
		t.Fatalf("preview cache not cleaned up: %v", matches)
	}
}
