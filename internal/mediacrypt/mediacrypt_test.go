package mediacrypt

import (
	"bytes"
	"crypto/rand"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func testKey(t *testing.T) []byte {
	t.Helper()
	k := make([]byte, 32)
	if _, err := rand.Read(k); err != nil {
		t.Fatal(err)
	}
	return k
}

func TestRoundTripStream(t *testing.T) {
	key := testKey(t)
	cases := []int{0, 1, 100, ChunkSize - 1, ChunkSize, ChunkSize + 1, 3*ChunkSize + 12345}
	for _, size := range cases {
		plain := make([]byte, size)
		if _, err := rand.Read(plain); err != nil {
			t.Fatal(err)
		}

		var enc bytes.Buffer
		if err := EncryptStream(&enc, bytes.NewReader(plain), key); err != nil {
			t.Fatalf("size=%d encrypt: %v", size, err)
		}
		if bytes.Contains(enc.Bytes(), []byte("MOTIONE1")) == false {
			t.Fatalf("size=%d missing magic", size)
		}

		var dec bytes.Buffer
		if err := DecryptStream(&dec, bytes.NewReader(enc.Bytes()), key); err != nil {
			t.Fatalf("size=%d decrypt: %v", size, err)
		}
		if !bytes.Equal(plain, dec.Bytes()) {
			t.Fatalf("size=%d round trip mismatch", size)
		}
	}
}

func TestTamperedCiphertextFails(t *testing.T) {
	key := testKey(t)
	plain := bytes.Repeat([]byte("a"), 5000)

	var enc bytes.Buffer
	if err := EncryptStream(&enc, bytes.NewReader(plain), key); err != nil {
		t.Fatal(err)
	}
	data := enc.Bytes()
	data[len(data)-1] ^= 0xff

	var dec bytes.Buffer
	if err := DecryptStream(&dec, bytes.NewReader(data), key); err == nil {
		t.Fatal("expected integrity failure on tampered ciphertext")
	}
}

func TestWrongKeyFails(t *testing.T) {
	plain := []byte("secret media bytes")
	var enc bytes.Buffer
	if err := EncryptStream(&enc, bytes.NewReader(plain), testKey(t)); err != nil {
		t.Fatal(err)
	}
	var dec bytes.Buffer
	if err := DecryptStream(&dec, bytes.NewReader(enc.Bytes()), testKey(t)); err == nil {
		t.Fatal("expected failure with wrong key")
	}
}

func TestNotEncrypted(t *testing.T) {
	var dec bytes.Buffer
	err := DecryptStream(&dec, bytes.NewReader([]byte("this is a plain mp4")), testKey(t))
	if err != ErrNotEncrypted {
		t.Fatalf("want ErrNotEncrypted, got %v", err)
	}
}

func TestSeekReader(t *testing.T) {
	key := testKey(t)
	plain := make([]byte, 3*ChunkSize+777)
	if _, err := rand.Read(plain); err != nil {
		t.Fatal(err)
	}

	dir := t.TempDir()
	srcPath := filepath.Join(dir, "src.bin")
	if err := os.WriteFile(srcPath, plain, 0o600); err != nil {
		t.Fatal(err)
	}
	encPath2 := filepath.Join(dir, "enc2.bin")
	if err := EncryptFile(srcPath, encPath2, key); err != nil {
		t.Fatal(err)
	}

	sr, err := OpenSeekReader(encPath2, key)
	if err != nil {
		t.Fatal(err)
	}
	defer sr.Close()

	if sr.Size() != int64(len(plain)) {
		t.Fatalf("size mismatch: got %d want %d", sr.Size(), len(plain))
	}

	// Full read.
	got, err := io.ReadAll(sr)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, plain) {
		t.Fatal("full read mismatch")
	}

	// Random seeks.
	for _, off := range []int64{0, 1, ChunkSize - 1, ChunkSize, ChunkSize + 1, 2*ChunkSize + 100, int64(len(plain)) - 1} {
		if _, err := sr.Seek(off, io.SeekStart); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 100)
		n, _ := sr.Read(buf)
		if n == 0 && off < int64(len(plain)) {
			t.Fatalf("offset %d: expected data", off)
		}
		end := off + int64(n)
		if !bytes.Equal(buf[:n], plain[off:end]) {
			t.Fatalf("offset %d: seek read mismatch", off)
		}
	}

	// SeekEnd.
	if _, err := sr.Seek(-10, io.SeekEnd); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 10)
	if _, err := io.ReadFull(sr, buf); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(buf, plain[len(plain)-10:]) {
		t.Fatal("seek end mismatch")
	}
}

func TestKeyManagerWrapUnwrap(t *testing.T) {
	master, err := GenerateMasterKey()
	if err != nil {
		t.Fatal(err)
	}
	km, err := NewKeyManager(master)
	if err != nil {
		t.Fatal(err)
	}
	dek, err := km.NewDEK()
	if err != nil {
		t.Fatal(err)
	}
	wrapped, err := km.Wrap(dek)
	if err != nil {
		t.Fatal(err)
	}
	got, err := km.Unwrap(wrapped)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(dek, got) {
		t.Fatal("unwrap mismatch")
	}

	otherMaster, _ := GenerateMasterKey()
	km2, _ := NewKeyManager(otherMaster)
	if _, err := km2.Unwrap(wrapped); err == nil {
		t.Fatal("expected unwrap failure with wrong master key")
	}
}

func TestKeyManagerValidation(t *testing.T) {
	if _, err := NewKeyManager(""); err == nil {
		t.Fatal("empty key should fail")
	}
	if _, err := NewKeyManager("not-base64!!"); err == nil {
		t.Fatal("invalid base64 should fail")
	}
	if _, err := NewKeyManager("c2hvcnQ="); err == nil {
		t.Fatal("short key should fail")
	}
}
