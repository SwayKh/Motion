package quota

import (
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
)

func init() {
	logger.Init()
}

func openQuotaDB(t *testing.T) *sql.DB {
	t.Helper()

	database, err := db.Open(filepath.Join(t.TempDir(), "app.db"))
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	t.Cleanup(func() { database.Close() })
	return database
}

func insertQuotaUser(t *testing.T, database *sql.DB, id string) {
	t.Helper()

	now := time.Now()
	if _, err := database.Exec(`
		INSERT INTO users (id, email, normalized_email, password_hash, role, status, email_verified, created_at, updated_at)
		VALUES (?, ?, ?, ?, 'user', 'active', 1, ?, ?)
	`, id, id+"@example.com", id+"@example.com", "hash", now, now); err != nil {
		t.Fatalf("insert user: %v", err)
	}
}

func addQuotaVideo(t *testing.T, database *sql.DB, id, userID string, size int64) {
	t.Helper()

	store := db.NewVideoStore(database)
	uid := userID
	video := &models.Video{
		ID:           id,
		UserID:       &uid,
		OriginalPath: filepath.Join(t.TempDir(), id+".mp4"),
		OriginalName: id + ".mp4",
		FileSize:     size,
		Status:       models.StatusUploaded,
	}
	if err := store.Create(video); err != nil {
		t.Fatalf("create video: %v", err)
	}
}

func newQuotaManager(t *testing.T, database *sql.DB, mutate func(*config.Config)) *Manager {
	t.Helper()

	cfg := &config.Config{
		MaxActiveUploadsPerUser: 3,
		MaxSourceFilesPerUser:   50,
		MaxOutputsPerUser:       200,
		MaxStoredBytesPerUser:   5 << 30,
		MaxTotalStorageBytes:    50 << 30,
		MinFreeDiskBytes:        0,
	}
	if mutate != nil {
		mutate(cfg)
	}
	return New(database, cfg)
}

func TestAcquireUploadRequiresAuthentication(t *testing.T) {
	m := newQuotaManager(t, openQuotaDB(t), nil)
	if _, err := m.AcquireUpload("", 100); !errors.Is(err, ErrExceeded) {
		t.Fatalf("anonymous upload error = %v, want ErrExceeded", err)
	}
}

func TestActiveUploadLimit(t *testing.T) {
	database := openQuotaDB(t)
	insertQuotaUser(t, database, "user-1")
	m := newQuotaManager(t, database, func(c *config.Config) {
		c.MaxActiveUploadsPerUser = 1
	})

	release, err := m.AcquireUpload("user-1", 10)
	if err != nil {
		t.Fatalf("first acquire: %v", err)
	}
	if _, err := m.AcquireUpload("user-1", 10); !errors.Is(err, ErrExceeded) {
		t.Fatalf("second acquire error = %v, want ErrExceeded", err)
	}
	release()
	if _, err := m.AcquireUpload("user-1", 10); err != nil {
		t.Fatalf("acquire after release: %v", err)
	}
}

func TestSourceFileLimit(t *testing.T) {
	database := openQuotaDB(t)
	insertQuotaUser(t, database, "user-1")
	addQuotaVideo(t, database, "v1", "user-1", 10)

	m := newQuotaManager(t, database, func(c *config.Config) {
		c.MaxSourceFilesPerUser = 1
	})
	if _, err := m.AcquireUpload("user-1", 10); !errors.Is(err, ErrExceeded) {
		t.Fatalf("source file limit error = %v, want ErrExceeded", err)
	}
}

func TestStoredBytesLimit(t *testing.T) {
	database := openQuotaDB(t)
	insertQuotaUser(t, database, "user-1")
	addQuotaVideo(t, database, "v1", "user-1", 900)

	m := newQuotaManager(t, database, func(c *config.Config) {
		c.MaxStoredBytesPerUser = 1000
	})

	if _, err := m.AcquireUpload("user-1", 200); !errors.Is(err, ErrExceeded) {
		t.Fatalf("stored bytes limit error = %v, want ErrExceeded", err)
	}
	release, err := m.AcquireUpload("user-1", 50)
	if err != nil {
		t.Fatalf("upload within budget: %v", err)
	}
	release()
}

func TestGlobalStorageLimit(t *testing.T) {
	database := openQuotaDB(t)
	insertQuotaUser(t, database, "user-1")
	insertQuotaUser(t, database, "user-2")
	addQuotaVideo(t, database, "v1", "user-1", 900)

	m := newQuotaManager(t, database, func(c *config.Config) {
		c.MaxTotalStorageBytes = 1000
	})

	if _, err := m.AcquireUpload("user-2", 200); !errors.Is(err, ErrExceeded) {
		t.Fatalf("global storage limit error = %v, want ErrExceeded", err)
	}
}

func TestOutputLimit(t *testing.T) {
	database := openQuotaDB(t)
	insertQuotaUser(t, database, "user-1")
	addQuotaVideo(t, database, "v1", "user-1", 10)

	jobs := db.NewJobStore(database)
	job, err := jobs.CreateQueued("v1", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim job: %v", err)
	}
	if _, _, err := jobs.Complete(job.ID, "outputs/out.mp4"); err != nil {
		t.Fatalf("complete job: %v", err)
	}

	m := newQuotaManager(t, database, func(c *config.Config) {
		c.MaxOutputsPerUser = 1
	})
	if _, err := m.AcquireUpload("user-1", 10); !errors.Is(err, ErrExceeded) {
		t.Fatalf("output limit error = %v, want ErrExceeded", err)
	}
}
