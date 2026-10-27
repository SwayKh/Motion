package job

import (
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
)

func init() {
	logger.Init()
}

func openRecoveryFixture(t *testing.T) (*db.JobStore, *db.VideoStore, *sql.DB, string) {
	t.Helper()

	dir := t.TempDir()
	database, err := db.Open(filepath.Join(dir, "app.db"))
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	t.Cleanup(func() {
		if err := database.Close(); err != nil {
			t.Errorf("close test database: %v", err)
		}
	})

	return db.NewJobStore(database), db.NewVideoStore(database), database, dir
}

func addRecoveryVideo(t *testing.T, videos *db.VideoStore, id, sourcePath string) {
	t.Helper()

	video := &models.Video{
		ID:           id,
		OriginalPath: sourcePath,
		OriginalName: id + ".mp4",
		Status:       models.StatusUploaded,
	}
	if err := videos.Create(video); err != nil {
		t.Fatalf("create video %s: %v", id, err)
	}
}

func TestRecoverInterruptedJobs(t *testing.T) {
	jobs, videos, database, dir := openRecoveryFixture(t)

	validSource := filepath.Join(dir, "valid.mp4")
	if err := os.WriteFile(validSource, []byte("source"), 0o600); err != nil {
		t.Fatalf("write valid source: %v", err)
	}
	addRecoveryVideo(t, videos, "valid-video", validSource)
	validJob, err := jobs.CreateQueued("valid-video", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create valid job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim valid job: %v", err)
	}
	artifact := filepath.Join(dir, "partial.mp4")
	if err := os.WriteFile(artifact, []byte("partial"), 0o600); err != nil {
		t.Fatalf("write partial output: %v", err)
	}
	if _, err := database.Exec("UPDATE jobs SET output_path = ? WHERE id = ?", artifact, validJob.ID); err != nil {
		t.Fatalf("set partial output: %v", err)
	}

	missingSource := filepath.Join(dir, "missing.mp4")
	if err := os.WriteFile(missingSource, []byte("source"), 0o600); err != nil {
		t.Fatalf("write missing source fixture: %v", err)
	}
	addRecoveryVideo(t, videos, "missing-video", missingSource)
	missingJob, err := jobs.CreateQueued("missing-video", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create missing-source job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim missing-source job: %v", err)
	}
	if err := os.Remove(missingSource); err != nil {
		t.Fatalf("remove missing source: %v", err)
	}

	invalidSource := filepath.Join(dir, "invalid.mp4")
	if err := os.WriteFile(invalidSource, []byte("source"), 0o600); err != nil {
		t.Fatalf("write invalid source: %v", err)
	}
	addRecoveryVideo(t, videos, "invalid-video", invalidSource)
	invalidJob, err := jobs.CreateQueued("invalid-video", `{`)
	if err != nil {
		t.Fatalf("create invalid job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim invalid job: %v", err)
	}

	addRecoveryVideo(t, videos, "cancel-video", filepath.Join(dir, "cancel.mp4"))
	cancelJob, err := jobs.CreateQueued("cancel-video", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create cancellation job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim cancellation job: %v", err)
	}
	if _, _, err := jobs.RequestCancel(cancelJob.ID); err != nil {
		t.Fatalf("request cancellation: %v", err)
	}

	manager := NewManager(jobs, 1)
	if err := manager.Recover(); err != nil {
		t.Fatalf("recover jobs: %v", err)
	}

	valid, err := jobs.Get(validJob.ID)
	if err != nil {
		t.Fatalf("get recovered valid job: %v", err)
	}
	if valid.Status != models.StatusQueued || valid.StartedAt != nil || valid.OutputPath != "" {
		t.Fatalf("valid recovery = %s/%v/%q, want queued/nil/empty", valid.Status, valid.StartedAt, valid.OutputPath)
	}
	if _, err := os.Stat(artifact); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("partial artifact still exists: %v", err)
	}

	missing, err := jobs.Get(missingJob.ID)
	if err != nil {
		t.Fatalf("get missing-source job: %v", err)
	}
	if missing.Status != models.StatusFailed || missing.Error != "source file is no longer available" {
		t.Fatalf("missing-source recovery = %s/%q", missing.Status, missing.Error)
	}

	invalid, err := jobs.Get(invalidJob.ID)
	if err != nil {
		t.Fatalf("get invalid job: %v", err)
	}
	if invalid.Status != models.StatusFailed || invalid.Error != "invalid saved operations" {
		t.Fatalf("invalid recovery = %s/%q", invalid.Status, invalid.Error)
	}

	cancelled, err := jobs.Get(cancelJob.ID)
	if err != nil {
		t.Fatalf("get cancellation job: %v", err)
	}
	if cancelled.Status != models.StatusCancelled {
		t.Fatalf("cancel-requested recovery = %s, want cancelled", cancelled.Status)
	}
}
