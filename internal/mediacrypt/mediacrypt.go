// Package mediacrypt implements application-level encryption at rest for stored
// media files.
//
// Design summary
//
//   - Files are encrypted with AES-256-GCM using a chunked (streaming) layout so
//     that arbitrarily large videos never need to be held in memory.
//   - Every file gets its own random data-encryption key (DEK). The DEK is
//     wrapped (encrypted) with a master key (KEK) and the wrapped value is stored
//     in the database, separate from the encrypted media.
//   - A fresh random base nonce is written into the file header. Each chunk uses
//     baseNonce || chunkIndex as its 12-byte GCM nonce, so a nonce is never
//     reused. The header is authenticated as additional data.
//
// Threat-model limitation: this protects media at rest against direct filesystem
// access, disk exposure and leaked backups. It does NOT protect against a fully
// compromised running server that has access to the master key in memory.
package mediacrypt

import (
	"bufio"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
)

const (
	// HeaderSize is the size of the encrypted file header in bytes.
	HeaderSize = 16

	// ChunkSize is the plaintext size of every chunk except possibly the last.
	ChunkSize = 1 << 20 // 1 MiB

	tagSize = 16 // AES-GCM authentication tag
)

var magic = [8]byte{'M', 'O', 'T', 'I', 'O', 'N', 'E', '1'}

// ErrNotEncrypted is returned when a file does not start with the Motion
// encryption header.
var ErrNotEncrypted = errors.New("file is not encrypted with the Motion format")

// ErrTampered is returned when authentication of a chunk fails.
var ErrTampered = errors.New("encrypted file failed integrity verification")

// IsEncryptedFile reports whether the file at path begins with the Motion
// encryption magic. It is used by the migration tooling to avoid double
// encryption.
func IsEncryptedFile(path string) (bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer f.Close()

	var header [8]byte
	if _, err := io.ReadFull(f, header[:]); err != nil {
		if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
			return false, nil
		}
		return false, err
	}
	return header == magic, nil
}

// EncryptFile encrypts srcPath into dstPath using key. It streams the data and
// fsyncs the destination before returning so the caller can verify and then
// replace the original.
func EncryptFile(srcPath, dstPath string, key []byte) error {
	src, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer src.Close()

	dst, err := os.OpenFile(dstPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}

	if err := EncryptStream(dst, src, key); err != nil {
		dst.Close()
		os.Remove(dstPath)
		return err
	}
	if err := dst.Sync(); err != nil {
		dst.Close()
		os.Remove(dstPath)
		return err
	}
	return dst.Close()
}

// DecryptFile decrypts srcPath into dstPath using key.
func DecryptFile(srcPath, dstPath string, key []byte) error {
	src, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer src.Close()

	dst, err := os.OpenFile(dstPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}

	if err := DecryptStream(dst, src, key); err != nil {
		dst.Close()
		os.Remove(dstPath)
		return err
	}
	if err := dst.Sync(); err != nil {
		dst.Close()
		os.Remove(dstPath)
		return err
	}
	return dst.Close()
}

// EncryptStream writes the encrypted representation of src to dst.
func EncryptStream(dst io.Writer, src io.Reader, key []byte) error {
	if len(key) != 32 {
		return fmt.Errorf("invalid key length %d (want 32)", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}

	var baseNonce [8]byte
	if _, err := rand.Read(baseNonce[:]); err != nil {
		return err
	}

	header := make([]byte, HeaderSize)
	copy(header, magic[:])
	copy(header[8:], baseNonce[:])
	if _, err := dst.Write(header); err != nil {
		return err
	}

	writer := bufio.NewWriterSize(dst, 64<<10)
	defer writer.Flush()

	buf := make([]byte, ChunkSize)
	var counter uint32
	for {
		n, readErr := io.ReadFull(src, buf)
		if readErr == io.EOF {
			break
		}
		if readErr != nil && readErr != io.ErrUnexpectedEOF {
			return readErr
		}

		nonce := chunkNonce(baseNonce, counter)
		sealed := aead.Seal(nil, nonce, buf[:n], header)

		var length [4]byte
		binary.BigEndian.PutUint32(length[:], uint32(n))
		if _, err := writer.Write(length[:]); err != nil {
			return err
		}
		if _, err := writer.Write(sealed); err != nil {
			return err
		}

		counter++
		if readErr == io.ErrUnexpectedEOF {
			break
		}
	}

	return writer.Flush()
}

// DecryptStream reads an encrypted stream from src and writes plaintext to dst.
func DecryptStream(dst io.Writer, src io.Reader, key []byte) error {
	dec, err := NewStreamReader(src, key)
	if err != nil {
		return err
	}
	_, err = io.Copy(dst, dec)
	return err
}

// StreamReader decrypts a chunked Motion stream. It is not seekable.
type StreamReader struct {
	aead      cipher.AEAD
	header    []byte
	baseNonce [8]byte
	r         io.Reader
	counter   uint32
	buf       []byte // decrypted but not yet consumed
	finished  bool
}

// NewStreamReader validates the header of src and returns a decrypting reader.
func NewStreamReader(src io.Reader, key []byte) (*StreamReader, error) {
	if len(key) != 32 {
		return nil, fmt.Errorf("invalid key length %d (want 32)", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	header := make([]byte, HeaderSize)
	if _, err := io.ReadFull(src, header); err != nil {
		return nil, fmt.Errorf("reading encrypted header: %w", err)
	}
	if [8]byte(header[:8]) != magic {
		return nil, ErrNotEncrypted
	}

	sr := &StreamReader{aead: aead, header: header, r: src}
	copy(sr.baseNonce[:], header[8:])
	return sr, nil
}

func (s *StreamReader) Read(p []byte) (int, error) {
	if len(s.buf) == 0 {
		if s.finished {
			return 0, io.EOF
		}
		if err := s.readChunk(); err != nil {
			return 0, err
		}
		if len(s.buf) == 0 {
			return 0, io.EOF
		}
	}
	n := copy(p, s.buf)
	s.buf = s.buf[n:]
	return n, nil
}

func (s *StreamReader) readChunk() error {
	var length [4]byte
	if _, err := io.ReadFull(s.r, length[:]); err != nil {
		if errors.Is(err, io.EOF) {
			s.finished = true
			return nil
		}
		return fmt.Errorf("reading chunk length: %w", err)
	}
	plainLen := int(binary.BigEndian.Uint32(length[:]))
	if plainLen < 0 || plainLen > ChunkSize {
		return ErrTampered
	}

	sealed := make([]byte, plainLen+tagSize)
	if _, err := io.ReadFull(s.r, sealed); err != nil {
		return fmt.Errorf("reading chunk: %w", err)
	}

	nonce := chunkNonce(s.baseNonce, s.counter)
	plain, err := s.aead.Open(nil, nonce, sealed, s.header)
	if err != nil {
		return ErrTampered
	}
	s.counter++
	s.buf = plain
	return nil
}

func chunkNonce(base [8]byte, counter uint32) []byte {
	nonce := make([]byte, 12)
	copy(nonce, base[:])
	binary.BigEndian.PutUint32(nonce[8:], counter)
	return nonce
}

// SeekReader is a seekable decrypting reader over an encrypted file. It is
// suitable for http.ServeContent, which uses Seek to determine the size and to
// serve HTTP range requests (needed for video seeking).
type SeekReader struct {
	f         *os.File
	aead      cipher.AEAD
	header    []byte
	baseNonce [8]byte
	size      int64 // plaintext size
	offset    int64 // current plaintext offset
	numChunks int64
	lastLen   int64 // plaintext length of the final chunk
	buf       []byte
	bufStart  int64 // plaintext offset of the first byte in buf
}

// OpenSeekReader opens an encrypted file for seekable reading.
func OpenSeekReader(path string, key []byte) (*SeekReader, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	sr, err := newSeekReader(f, key)
	if err != nil {
		f.Close()
		return nil, err
	}
	return sr, nil
}

func newSeekReader(f *os.File, key []byte) (*SeekReader, error) {
	if len(key) != 32 {
		return nil, fmt.Errorf("invalid key length %d (want 32)", len(key))
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	fileSize := info.Size()
	if fileSize < HeaderSize {
		return nil, ErrNotEncrypted
	}

	header := make([]byte, HeaderSize)
	if _, err := f.ReadAt(header, 0); err != nil {
		return nil, err
	}
	if [8]byte(header[:8]) != magic {
		return nil, ErrNotEncrypted
	}

	sr := &SeekReader{f: f, aead: aead, header: header}
	copy(sr.baseNonce[:], header[8:])

	body := fileSize - HeaderSize
	perChunk := int64(4 + ChunkSize + tagSize)
	if body == 0 {
		sr.size = 0
		sr.numChunks = 0
		return sr, nil
	}
	full := body / perChunk
	rem := body % perChunk
	if rem == 0 {
		sr.numChunks = full
		sr.lastLen = ChunkSize
	} else {
		sr.numChunks = full + 1
		if rem < int64(4+tagSize) {
			return nil, ErrTampered
		}
		sr.lastLen = rem - int64(4+tagSize)
	}
	if sr.lastLen < 0 || sr.lastLen > ChunkSize {
		return nil, ErrTampered
	}
	sr.size = (sr.numChunks-1)*ChunkSize + sr.lastLen
	return sr, nil
}

// Size returns the plaintext size in bytes.
func (s *SeekReader) Size() int64 { return s.size }

// Close closes the underlying file.
func (s *SeekReader) Close() error { return s.f.Close() }

func (s *SeekReader) Read(p []byte) (int, error) {
	if s.offset >= s.size {
		return 0, io.EOF
	}
	if len(s.buf) == 0 || s.offset < s.bufStart || s.offset >= s.bufStart+int64(len(s.buf)) {
		if err := s.loadChunkFor(s.offset); err != nil {
			return 0, err
		}
	}
	start := s.offset - s.bufStart
	n := copy(p, s.buf[start:])
	s.offset += int64(n)
	return n, nil
}

func (s *SeekReader) loadChunkFor(offset int64) error {
	idx := offset / ChunkSize
	if idx >= s.numChunks {
		return io.EOF
	}
	perChunk := int64(4 + ChunkSize + tagSize)
	cipherOffset := HeaderSize + idx*perChunk

	var length [4]byte
	if _, err := s.f.ReadAt(length[:], cipherOffset); err != nil {
		return err
	}
	plainLen := int(binary.BigEndian.Uint32(length[:]))
	if plainLen < 0 || plainLen > ChunkSize {
		return ErrTampered
	}
	sealed := make([]byte, plainLen+tagSize)
	if _, err := s.f.ReadAt(sealed, cipherOffset+4); err != nil {
		return err
	}
	nonce := chunkNonce(s.baseNonce, uint32(idx))
	plain, err := s.aead.Open(nil, nonce, sealed, s.header)
	if err != nil {
		return ErrTampered
	}
	s.buf = plain
	s.bufStart = idx * ChunkSize
	return nil
}

// Seek implements io.Seeker for plaintext offsets.
func (s *SeekReader) Seek(offset int64, whence int) (int64, error) {
	var abs int64
	switch whence {
	case io.SeekStart:
		abs = offset
	case io.SeekCurrent:
		abs = s.offset + offset
	case io.SeekEnd:
		abs = s.size + offset
	default:
		return 0, errors.New("invalid whence")
	}
	if abs < 0 {
		return 0, errors.New("negative position")
	}
	s.offset = abs
	// Drop the cached chunk; it will be reloaded on the next Read if needed.
	if abs < s.bufStart || abs >= s.bufStart+int64(len(s.buf)) {
		s.buf = nil
	}
	return abs, nil
}

// KeyManager wraps and unwraps per-file data-encryption keys with a master key.
type KeyManager struct {
	master cipher.AEAD
}

// NewKeyManager builds a KeyManager from a base64-encoded 32-byte master key.
func NewKeyManager(encoded string) (*KeyManager, error) {
	if encoded == "" {
		return nil, errors.New("empty encryption key")
	}
	raw, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		// Accept raw URL encoding as a convenience.
		raw, err = base64.RawURLEncoding.DecodeString(encoded)
		if err != nil {
			return nil, fmt.Errorf("master key must be base64: %w", err)
		}
	}
	if len(raw) != 32 {
		return nil, fmt.Errorf("master key must decode to 32 bytes, got %d", len(raw))
	}
	block, err := aes.NewCipher(raw)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &KeyManager{master: aead}, nil
}

// GenerateMasterKey returns a fresh base64-encoded 32-byte master key.
func GenerateMasterKey() (string, error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(raw), nil
}

// NewDEK returns a fresh random 32-byte data-encryption key.
func (km *KeyManager) NewDEK() ([]byte, error) {
	dek := make([]byte, 32)
	if _, err := rand.Read(dek); err != nil {
		return nil, err
	}
	return dek, nil
}

// Wrap encrypts a DEK with the master key and returns base64(nonce||ciphertext).
func (km *KeyManager) Wrap(dek []byte) (string, error) {
	nonce := make([]byte, km.master.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	sealed := km.master.Seal(nil, nonce, dek, nil)
	return base64.StdEncoding.EncodeToString(append(nonce, sealed...)), nil
}

// Unwrap decrypts a wrapped DEK produced by Wrap.
func (km *KeyManager) Unwrap(wrapped string) ([]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(wrapped)
	if err != nil {
		return nil, err
	}
	ns := km.master.NonceSize()
	if len(raw) < ns {
		return nil, errors.New("wrapped key too short")
	}
	nonce, ct := raw[:ns], raw[ns:]
	dek, err := km.master.Open(nil, nonce, ct, nil)
	if err != nil {
		return nil, errors.New("failed to unwrap key (wrong master key?)")
	}
	return dek, nil
}
