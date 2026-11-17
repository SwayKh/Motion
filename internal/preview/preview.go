// Package preview caches derived, low-resolution media previews — thumbnail
// filmstrips and waveform peak data — so the editor timeline never regenerates
// them on every page load.
//
// Previews are always derived from an owned source and are only ever served
// through authenticated API handlers, so the cache never widens access to the
// underlying media.
package preview

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
)

// Manager owns the on-disk preview cache and the per-key generation locks that
// stop two concurrent requests from rendering the same asset twice.
type Manager struct {
	dir   string
	locks sync.Map // key -> *sync.Mutex
}

// New builds a Manager rooted at dir.
func New(dir string) *Manager {
	return &Manager{dir: dir}
}

// Dir returns the cache directory.
func (m *Manager) Dir() string { return m.dir }

// EnsureDir creates the cache directory if needed.
func (m *Manager) EnsureDir() error {
	if m.dir == "" {
		return fmt.Errorf("preview directory is not configured")
	}
	if err := os.MkdirAll(m.dir, 0o755); err != nil {
		return fmt.Errorf("creating preview directory: %w", err)
	}
	return nil
}

// StripPath is the cache path for a filmstrip with the given tile count and
// height. The parameters are part of the name so different zoom levels or
// heights can coexist without invalidating each other.
func (m *Manager) StripPath(videoID string, count, height int) string {
	return filepath.Join(m.dir, fmt.Sprintf("strip_%s_%d_%d.jpg", videoID, count, height))
}

// WaveformPath is the cache path for waveform peaks with the given bucket count.
func (m *Manager) WaveformPath(videoID string, buckets int) string {
	return filepath.Join(m.dir, fmt.Sprintf("wave_%s_%d.json", videoID, buckets))
}

// Lock serialises generation for one cache key and returns the unlock function.
func (m *Manager) Lock(key string) func() {
	value, _ := m.locks.LoadOrStore(key, &sync.Mutex{})
	mu := value.(*sync.Mutex)
	mu.Lock()
	return mu.Unlock
}

// Exists reports whether a cached file is present and non-empty.
func (m *Manager) Exists(path string) bool {
	if path == "" {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && !info.IsDir() && info.Size() > 0
}

// RemoveForVideo deletes every cached preview that belongs to a video. It is
// called when the video is deleted so the cache cannot grow without bound.
func (m *Manager) RemoveForVideo(videoID string) {
	if m.dir == "" || videoID == "" {
		return
	}
	matches, err := filepath.Glob(filepath.Join(m.dir, "*_"+videoID+"_*"))
	if err != nil {
		return
	}
	for _, path := range matches {
		_ = os.Remove(path)
	}
}
