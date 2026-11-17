// Package storage centralises the on-disk locations used by Motion and the
// optional application-level encryption at rest.
//
// All file paths produced by the service and read by the API handlers go
// through a Manager so that encryption can be enabled without scattering
// crypto logic across the codebase. When encryption is disabled the Manager
// behaves exactly like plain filesystem access, which keeps existing
// installations working unchanged.
package storage

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/mediacrypt"
)

// Manager owns the storage directories and (optionally) the master key used to
// wrap per-file data-encryption keys.
type Manager struct {
	uploadDir  string
	outputDir  string
	tempDir    string
	previewDir string
	km         *mediacrypt.KeyManager
}

// New builds a Manager from configuration. It returns an error when an
// encryption key is configured but is not a valid base64-encoded 32-byte key,
// so a misconfiguration fails closed at startup instead of silently writing
// plaintext.
func New(cfg *config.Config) (*Manager, error) {
	m := &Manager{
		uploadDir:  cfg.UploadDir,
		outputDir:  cfg.OutputDir,
		tempDir:    cfg.TempDir,
		previewDir: cfg.PreviewDir,
	}
	if cfg.EncryptionEnabled() {
		km, err := mediacrypt.NewKeyManager(cfg.EncryptionKey)
		if err != nil {
			return nil, fmt.Errorf("initialising encryption: %w", err)
		}
		m.km = km
	}
	return m, nil
}

// Default returns a Manager with the historical relative directory layout and
// encryption disabled. It is used by tests and by callers that have no
// configuration available.
func Default() *Manager {
	return &Manager{
		uploadDir:  "uploads",
		outputDir:  "outputs",
		tempDir:    filepath.Join("data", "tmp"),
		previewDir: filepath.Join("data", "previews"),
	}
}

// EncryptionEnabled reports whether files are encrypted at rest.
func (m *Manager) EncryptionEnabled() bool { return m != nil && m.km != nil }

// UploadDir returns the directory holding original uploads.
func (m *Manager) UploadDir() string { return m.uploadDir }

// OutputDir returns the directory holding processed outputs.
func (m *Manager) OutputDir() string { return m.outputDir }

// TempDir returns the scratch directory used while probing and transcoding.
func (m *Manager) TempDir() string { return m.tempDir }

// PreviewDir returns the directory holding derived preview assets such as
// thumbnail filmstrips and waveform data.
func (m *Manager) PreviewDir() string { return m.previewDir }

// EnsureDirs creates the storage directories if they do not exist.
func (m *Manager) EnsureDirs() error {
	dirs := []struct {
		path string
		mode os.FileMode
	}{
		{m.uploadDir, 0o755},
		{m.outputDir, 0o755},
		{m.tempDir, 0o700},
		{m.previewDir, 0o755},
	}
	for _, d := range dirs {
		if d.path == "" {
			continue
		}
		if err := os.MkdirAll(d.path, d.mode); err != nil {
			return fmt.Errorf("creating directory %s: %w", d.path, err)
		}
	}
	return nil
}

// Contains reports whether path resolves inside one of the managed storage
// directories. It is used by the API layer to refuse to serve arbitrary files
// even if a stored path were somehow manipulated.
func (m *Manager) Contains(path string) bool {
	if m == nil || path == "" {
		return false
	}
	abs, err := filepath.Abs(filepath.Clean(path))
	if err != nil {
		return false
	}
	for _, dir := range []string{m.uploadDir, m.outputDir} {
		if dir == "" {
			continue
		}
		dirAbs, err := filepath.Abs(dir)
		if err != nil {
			continue
		}
		rel, err := filepath.Rel(dirAbs, abs)
		if err != nil {
			continue
		}
		if rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel) {
			return true
		}
	}
	return false
}

// UploadPath returns the final path for an upload with the given file name.
func (m *Manager) UploadPath(name string) string { return filepath.Join(m.uploadDir, name) }

// OutputPath returns the final path for an output with the given file name.
func (m *Manager) OutputPath(name string) string { return filepath.Join(m.outputDir, name) }

// NewTempFile creates a scratch file in the temp directory. The caller is
// responsible for removing it.
func (m *Manager) NewTempFile(pattern string) (*os.File, error) {
	if err := os.MkdirAll(m.tempDir, 0o700); err != nil {
		return nil, fmt.Errorf("creating temp directory: %w", err)
	}
	return os.CreateTemp(m.tempDir, pattern)
}

// StoreUpload moves a plaintext scratch file into the upload directory,
// encrypting it when encryption is enabled. It returns the final path, whether
// the file is encrypted and the wrapped data-encryption key.
func (m *Manager) StoreUpload(srcPath, name string) (path string, encrypted bool, wrappedKey string, err error) {
	dst := m.UploadPath(name)
	if !m.EncryptionEnabled() {
		if err := moveFile(srcPath, dst); err != nil {
			return "", false, "", err
		}
		return dst, false, "", nil
	}

	dek, err := m.km.NewDEK()
	if err != nil {
		return "", false, "", err
	}
	if err := mediacrypt.EncryptFile(srcPath, dst, dek); err != nil {
		return "", false, "", err
	}
	wrapped, err := m.km.Wrap(dek)
	if err != nil {
		_ = os.Remove(dst)
		return "", false, "", err
	}
	_ = os.Remove(srcPath)
	return dst, true, wrapped, nil
}

// EncryptOutput encrypts a plaintext output produced by FFmpeg into the output
// directory. It returns the final path, whether the file is encrypted and the
// wrapped data-encryption key.
func (m *Manager) EncryptOutput(srcPath, name string) (path string, encrypted bool, wrappedKey string, err error) {
	dst := m.OutputPath(name)
	if !m.EncryptionEnabled() {
		if err := moveFile(srcPath, dst); err != nil {
			return "", false, "", err
		}
		return dst, false, "", nil
	}

	dek, err := m.km.NewDEK()
	if err != nil {
		return "", false, "", err
	}
	if err := mediacrypt.EncryptFile(srcPath, dst, dek); err != nil {
		return "", false, "", err
	}
	wrapped, err := m.km.Wrap(dek)
	if err != nil {
		_ = os.Remove(dst)
		return "", false, "", err
	}
	_ = os.Remove(srcPath)
	return dst, true, wrapped, nil
}

// DecryptToTemp decrypts an encrypted file into a scratch file so FFmpeg can
// read it. The returned cleanup function removes the scratch file. When the
// file is not encrypted the original path is returned and cleanup is a no-op.
func (m *Manager) DecryptToTemp(srcPath, wrappedKey string, encrypted bool) (string, func(), error) {
	if !encrypted {
		return srcPath, func() {}, nil
	}
	if !m.EncryptionEnabled() {
		return "", nil, errors.New("cannot decrypt media: MOTION_ENCRYPTION_KEY is not configured")
	}
	dek, err := m.km.Unwrap(wrappedKey)
	if err != nil {
		return "", nil, err
	}

	f, err := m.NewTempFile("dec-*" + filepath.Ext(srcPath))
	if err != nil {
		return "", nil, err
	}
	tmpPath := f.Name()
	if err := f.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return "", nil, err
	}
	if err := mediacrypt.DecryptFile(srcPath, tmpPath, dek); err != nil {
		_ = os.Remove(tmpPath)
		return "", nil, err
	}
	return tmpPath, func() { _ = os.Remove(tmpPath) }, nil
}

// EncryptInPlace encrypts an existing plaintext file, replacing it with its
// encrypted form, and returns the wrapped data-encryption key. It is used by the
// encrypt-media migration command.
func (m *Manager) EncryptInPlace(path string) (string, error) {
	if !m.EncryptionEnabled() {
		return "", errors.New("MOTION_ENCRYPTION_KEY is not configured")
	}
	dek, err := m.km.NewDEK()
	if err != nil {
		return "", err
	}
	tmp := path + ".enc"
	if err := mediacrypt.EncryptFile(path, tmp, dek); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return "", err
	}
	wrapped, err := m.km.Wrap(dek)
	if err != nil {
		return "", err
	}
	return wrapped, nil
}

// DecryptInPlace decrypts an encrypted file, replacing it with its plaintext
// form. It is used by the decrypt-media migration command.
func (m *Manager) DecryptInPlace(path, wrappedKey string) error {
	if !m.EncryptionEnabled() {
		return errors.New("MOTION_ENCRYPTION_KEY is not configured")
	}
	dek, err := m.km.Unwrap(wrappedKey)
	if err != nil {
		return err
	}
	tmp := path + ".dec"
	if err := mediacrypt.DecryptFile(path, tmp, dek); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}

// ReadSeekCloser is the minimal interface needed to serve a file with range
// support via http.ServeContent.
type ReadSeekCloser interface {
	io.Reader
	io.Seeker
	io.Closer
}

// OpenReader opens a stored file for reading. Encrypted files are transparently
// decrypted on the fly using a seekable reader.
func (m *Manager) OpenReader(path, wrappedKey string, encrypted bool) (ReadSeekCloser, error) {
	if !encrypted {
		return os.Open(path)
	}
	if !m.EncryptionEnabled() {
		return nil, errors.New("cannot read encrypted media: MOTION_ENCRYPTION_KEY is not configured")
	}
	dek, err := m.km.Unwrap(wrappedKey)
	if err != nil {
		return nil, err
	}
	return mediacrypt.OpenSeekReader(path, dek)
}

// moveFile renames src to dst, falling back to a copy when the source and
// destination live on different filesystems (for example when scratch space and
// the media volume are separate mounts).
func moveFile(src, dst string) error {
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	srcFile, err := os.Open(src)
	if err != nil {
		return err
	}
	defer srcFile.Close()

	dstFile, err := os.OpenFile(dst, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	if _, err := io.Copy(dstFile, srcFile); err != nil {
		dstFile.Close()
		_ = os.Remove(dst)
		return err
	}
	if err := dstFile.Sync(); err != nil {
		dstFile.Close()
		_ = os.Remove(dst)
		return err
	}
	if err := dstFile.Close(); err != nil {
		_ = os.Remove(dst)
		return err
	}
	_ = os.Remove(src)
	return nil
}

// Remove deletes a stored file, ignoring missing files.
func (m *Manager) Remove(path string) error {
	if path == "" {
		return nil
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}
