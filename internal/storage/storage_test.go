package storage

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"testing"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/mediacrypt"
)

func newTestManager(t *testing.T, encrypt bool) *Manager {
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

	m, err := New(cfg)
	if err != nil {
		t.Fatalf("new storage manager: %v", err)
	}
	if err := m.EnsureDirs(); err != nil {
		t.Fatalf("ensure dirs: %v", err)
	}
	return m
}

func TestContains(t *testing.T) {
	m := newTestManager(t, false)

	if !m.Contains(m.UploadPath("a.mp4")) {
		t.Fatal("upload path should be contained")
	}
	if !m.Contains(m.OutputPath("b.mp4")) {
		t.Fatal("output path should be contained")
	}
	if m.Contains(filepath.Join(m.UploadDir(), "..", "escape.mp4")) {
		t.Fatal("path escaping the upload dir should not be contained")
	}
	if m.Contains("/etc/passwd") {
		t.Fatal("absolute system path should not be contained")
	}
	if m.Contains("") {
		t.Fatal("empty path should not be contained")
	}
}

func TestStoreUploadPlaintext(t *testing.T) {
	m := newTestManager(t, false)

	src := filepath.Join(t.TempDir(), "plain.bin")
	plain := []byte("unencrypted media bytes")
	if err := os.WriteFile(src, plain, 0o600); err != nil {
		t.Fatal(err)
	}

	path, encrypted, key, err := m.StoreUpload(src, "stored.bin")
	if err != nil {
		t.Fatalf("store upload: %v", err)
	}
	if encrypted {
		t.Fatal("plaintext manager reported encrypted file")
	}
	if key != "" {
		t.Fatalf("plaintext manager returned a key: %q", key)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Fatalf("scratch file was not moved: %v", err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatal("stored bytes differ from source")
	}

	reader, err := m.OpenReader(path, "", false)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	read, _ := io.ReadAll(reader)
	if !bytes.Equal(read, plain) {
		t.Fatal("reader returned different bytes")
	}
}

func TestStoreUploadEncryptedRoundTrip(t *testing.T) {
	m := newTestManager(t, true)

	src := filepath.Join(t.TempDir(), "plain.bin")
	plain := bytes.Repeat([]byte("encrypted media "), 5000)
	if err := os.WriteFile(src, plain, 0o600); err != nil {
		t.Fatal(err)
	}

	path, encrypted, key, err := m.StoreUpload(src, "stored.bin")
	if err != nil {
		t.Fatalf("store upload: %v", err)
	}
	if !encrypted || key == "" {
		t.Fatalf("expected encrypted file, got encrypted=%v key=%q", encrypted, key)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Fatalf("scratch file was not removed: %v", err)
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(raw, plain[:64]) {
		t.Fatal("stored file contains plaintext")
	}

	reader, err := m.OpenReader(path, key, encrypted)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	got, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatalf("decrypted bytes mismatch: got %d want %d", len(got), len(plain))
	}

	tmp, cleanup, err := m.DecryptToTemp(path, key, encrypted)
	if err != nil {
		t.Fatalf("decrypt to temp: %v", err)
	}
	if tmp == path {
		t.Fatal("encrypted file was not decrypted to a scratch file")
	}
	tmpBytes, err := os.ReadFile(tmp)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(tmpBytes, plain) {
		t.Fatal("scratch decrypted bytes mismatch")
	}
	cleanup()
	if _, err := os.Stat(tmp); !os.IsNotExist(err) {
		t.Fatalf("scratch file was not cleaned up: %v", err)
	}
}

func TestEncryptOutputRoundTrip(t *testing.T) {
	m := newTestManager(t, true)

	src := filepath.Join(t.TempDir(), "output.bin")
	plain := []byte("processed output data")
	if err := os.WriteFile(src, plain, 0o600); err != nil {
		t.Fatal(err)
	}

	path, encrypted, key, err := m.EncryptOutput(src, "out.bin")
	if err != nil {
		t.Fatalf("encrypt output: %v", err)
	}
	if !encrypted || key == "" {
		t.Fatalf("expected encrypted output, got encrypted=%v key=%q", encrypted, key)
	}
	if !m.Contains(path) {
		t.Fatal("output path is outside the managed directories")
	}

	reader, err := m.OpenReader(path, key, encrypted)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	got, _ := io.ReadAll(reader)
	if !bytes.Equal(got, plain) {
		t.Fatal("decrypted output mismatch")
	}
}

func TestOpenEncryptedWithoutKeyFails(t *testing.T) {
	m := newTestManager(t, false)
	if _, err := m.OpenReader("whatever.bin", "key", true); err == nil {
		t.Fatal("expected failure opening an encrypted file without a key")
	}
	if _, _, err := m.DecryptToTemp("whatever.bin", "key", true); err == nil {
		t.Fatal("expected failure decrypting without a key")
	}
}

func TestEncryptDecryptInPlace(t *testing.T) {
	m := newTestManager(t, true)

	path := filepath.Join(t.TempDir(), "media.bin")
	plain := bytes.Repeat([]byte("in-place "), 1000)
	if err := os.WriteFile(path, plain, 0o600); err != nil {
		t.Fatal(err)
	}

	key, err := m.EncryptInPlace(path)
	if err != nil {
		t.Fatalf("encrypt in place: %v", err)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(raw, plain) {
		t.Fatal("file was not encrypted in place")
	}

	if err := m.DecryptInPlace(path, key); err != nil {
		t.Fatalf("decrypt in place: %v", err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatal("decrypted in-place bytes mismatch")
	}
}

func TestNewRejectsInvalidKey(t *testing.T) {
	cfg := &config.Config{
		UploadDir:     t.TempDir(),
		OutputDir:     t.TempDir(),
		TempDir:       t.TempDir(),
		EncryptionKey: "not-base64!!",
	}
	if _, err := New(cfg); err == nil {
		t.Fatal("expected an error for an invalid encryption key")
	}
}

func TestDefaultManagerLayout(t *testing.T) {
	m := Default()
	if m.EncryptionEnabled() {
		t.Fatal("default manager must not enable encryption")
	}
	if m.UploadDir() != "uploads" || m.OutputDir() != "outputs" {
		t.Fatalf("unexpected default dirs: %q %q", m.UploadDir(), m.OutputDir())
	}
}
