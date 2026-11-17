// Package quota enforces per-user and global resource limits for uploads and
// processing jobs.
//
// File-count and byte limits are derived from the database. The number of
// simultaneously in-flight uploads is tracked in memory, which is enough to
// bound transient resource use within a single process. Job concurrency is
// enforced atomically in the database (see db.JobStore.CreateQueuedLimited) so
// it survives restarts and races between requests.
package quota

import (
	"database/sql"
	"errors"
	"fmt"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/logger"
)

// ErrExceeded is returned when a quota would be exceeded.
var ErrExceeded = errors.New("quota exceeded")

// Manager tracks live upload reservations and validates storage quotas.
type Manager struct {
	db  *sql.DB
	cfg *config.Config

	activeUploads map[string]int
}

// New builds a quota Manager.
func New(database *sql.DB, cfg *config.Config) *Manager {
	return &Manager{
		db:            database,
		cfg:           cfg,
		activeUploads: make(map[string]int),
	}
}

// AcquireUpload reserves one in-flight upload slot for a user and verifies the
// configured file-count, byte and disk-space limits. The returned release
// function must be called when the upload finishes.
func (m *Manager) AcquireUpload(userID string, incomingBytes int64) (func(), error) {
	if m == nil {
		return func() {}, nil
	}
	if userID == "" {
		return nil, fmt.Errorf("%w: authentication required", ErrExceeded)
	}

	if m.cfg.MaxActiveUploadsPerUser > 0 && m.activeUploads[userID] >= m.cfg.MaxActiveUploadsPerUser {
		return nil, fmt.Errorf("%w: too many simultaneous uploads", ErrExceeded)
	}
	m.activeUploads[userID]++

	release := func() {
		m.activeUploads[userID]--
		if m.activeUploads[userID] <= 0 {
			delete(m.activeUploads, userID)
		}
	}

	if err := m.checkStorage(userID, incomingBytes); err != nil {
		release()
		return nil, err
	}
	return release, nil
}

func (m *Manager) checkStorage(userID string, incomingBytes int64) error {
	if m.cfg.MaxSourceFilesPerUser > 0 {
		var count int
		if err := m.db.QueryRow(`SELECT COUNT(1) FROM videos WHERE user_id = ?`, userID).Scan(&count); err != nil {
			return fmt.Errorf("checking source-file quota: %w", err)
		}
		if count >= m.cfg.MaxSourceFilesPerUser {
			return fmt.Errorf("%w: source file limit reached", ErrExceeded)
		}
	}

	if m.cfg.MaxOutputsPerUser > 0 {
		var count int
		if err := m.db.QueryRow(
			`SELECT COUNT(1) FROM jobs WHERE user_id = ? AND status = 'completed'`,
			userID,
		).Scan(&count); err != nil {
			return fmt.Errorf("checking output quota: %w", err)
		}
		if count >= m.cfg.MaxOutputsPerUser {
			return fmt.Errorf("%w: output limit reached", ErrExceeded)
		}
	}

	if m.cfg.MaxStoredBytesPerUser > 0 {
		var used int64
		if err := m.db.QueryRow(
			`SELECT COALESCE(SUM(file_size), 0) FROM videos WHERE user_id = ?`,
			userID,
		).Scan(&used); err != nil {
			return fmt.Errorf("checking storage quota: %w", err)
		}
		if used+incomingBytes > m.cfg.MaxStoredBytesPerUser {
			return fmt.Errorf("%w: per-user storage limit reached", ErrExceeded)
		}
	}

	if m.cfg.MaxTotalStorageBytes > 0 {
		var used int64
		if err := m.db.QueryRow(`SELECT COALESCE(SUM(file_size), 0) FROM videos`).Scan(&used); err != nil {
			return fmt.Errorf("checking global storage quota: %w", err)
		}
		if used+incomingBytes > m.cfg.MaxTotalStorageBytes {
			return fmt.Errorf("%w: server storage limit reached", ErrExceeded)
		}
	}

	if m.cfg.MinFreeDiskBytes > 0 {
		free, err := diskFree(m.cfg.OutputDir)
		if err != nil {
			// The disk guard is an availability safeguard, not a security
			// boundary. If it cannot be evaluated, log and allow the upload
			// rather than blocking legitimate work.
			logger.Error.Printf("checking free disk space: %v", err)
		} else if free < m.cfg.MinFreeDiskBytes+incomingBytes {
			return fmt.Errorf("%w: server is low on disk space", ErrExceeded)
		}
	}

	return nil
}
