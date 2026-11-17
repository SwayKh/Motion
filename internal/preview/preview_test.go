package preview

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestPathsAreParameterised(t *testing.T) {
	m := New("/tmp/previews")
	if got := m.StripPath("vid", 24, 72); got != filepath.Join("/tmp/previews", "strip_vid_24_72.jpg") {
		t.Fatalf("StripPath = %q", got)
	}
	if got := m.WaveformPath("vid", 800); got != filepath.Join("/tmp/previews", "wave_vid_800.json") {
		t.Fatalf("WaveformPath = %q", got)
	}
}

func TestEnsureDirAndExists(t *testing.T) {
	dir := t.TempDir()
	m := New(filepath.Join(dir, "nested", "previews"))
	if err := m.EnsureDir(); err != nil {
		t.Fatalf("EnsureDir: %v", err)
	}
	path := m.StripPath("v", 12, 72)
	if m.Exists(path) {
		t.Fatal("Exists reported true for a missing file")
	}
	if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	if !m.Exists(path) {
		t.Fatal("Exists reported false for a present file")
	}
}

func TestRemoveForVideo(t *testing.T) {
	dir := t.TempDir()
	m := New(dir)
	if err := m.EnsureDir(); err != nil {
		t.Fatalf("EnsureDir: %v", err)
	}

	target := m.StripPath("keep-me-not", 12, 72)
	wave := m.WaveformPath("keep-me-not", 200)
	other := m.StripPath("other", 12, 72)
	for _, p := range []string{target, wave, other} {
		if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
			t.Fatalf("write %s: %v", p, err)
		}
	}

	m.RemoveForVideo("keep-me-not")

	if m.Exists(target) || m.Exists(wave) {
		t.Fatal("cached previews for the deleted video were not removed")
	}
	if !m.Exists(other) {
		t.Fatal("RemoveForVideo removed an unrelated video's cache")
	}
}

func TestLockSerialisesSameKey(t *testing.T) {
	m := New(t.TempDir())

	var (
		mu      sync.Mutex
		active  int
		maxSeen int
		wg      sync.WaitGroup
	)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			unlock := m.Lock("same")
			defer unlock()
			mu.Lock()
			active++
			if active > maxSeen {
				maxSeen = active
			}
			mu.Unlock()
			mu.Lock()
			active--
			mu.Unlock()
		}()
	}
	wg.Wait()
	if maxSeen != 1 {
		t.Fatalf("max concurrent holders = %d, want 1", maxSeen)
	}
}
