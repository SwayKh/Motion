package email

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/SwayKh/Motion/internal/logger"
)

// DevOutbox is a Sender used when SMTP is not configured. Instead of silently
// dropping messages it records them in a file (and the log) so a developer can
// retrieve verification and reset links locally without a mail server.
//
// It must never be used in production: it writes message bodies, which contain
// one-time secrets, to disk in plaintext.
type DevOutbox struct {
	Path string

	mu sync.Mutex
}

// NewDevOutbox returns a DevOutbox writing to path. The directory is created on
// first use.
func NewDevOutbox(path string) *DevOutbox {
	return &DevOutbox{Path: path}
}

func (d *DevOutbox) Send(to, subject, body string) error {
	entry := fmt.Sprintf(
		"=== %s ===\nTo: %s\nSubject: %s\n\n%s\n\n",
		time.Now().UTC().Format(time.RFC3339),
		to,
		subject,
		body,
	)
	logger.Info.Printf("dev email queued to=%s subject=%q", to, subject)

	if d.Path == "" {
		return nil
	}

	d.mu.Lock()
	defer d.mu.Unlock()

	if err := os.MkdirAll(filepath.Dir(d.Path), 0o700); err != nil {
		return err
	}
	f, err := os.OpenFile(d.Path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	if _, err := f.WriteString(entry); err != nil {
		return err
	}
	return nil
}
