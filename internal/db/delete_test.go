package db

import (
	"errors"
	"testing"

	"github.com/SwayKh/Motion/internal/models"
)

func TestDeleteAndResetVideo(t *testing.T) {
	database := openTestDB(t)
	videos := NewVideoStore(database)
	jobs := NewJobStore(database)
	createTestVideo(t, videos, "v1")

	job, err := jobs.CreateQueued("v1", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create queued job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim job: %v", err)
	}
	if _, _, err := jobs.CompleteWithOutput(job.ID, "outputs/out1.mp4", false, ""); err != nil {
		t.Fatalf("complete job: %v", err)
	}

	video, err := videos.Get("v1")
	if err != nil {
		t.Fatalf("get video: %v", err)
	}
	if video.OutputPath != "outputs/out1.mp4" || video.Status != models.StatusCompleted {
		t.Fatalf("video state = %s/%q, want completed/outputs/out1.mp4", video.Status, video.OutputPath)
	}

	if err := jobs.DeleteAndResetVideo(job.ID); err != nil {
		t.Fatalf("delete job: %v", err)
	}
	video, err = videos.Get("v1")
	if err != nil {
		t.Fatalf("get video after delete: %v", err)
	}
	if video.OutputPath != "" || video.Status != models.StatusUploaded {
		t.Fatalf("video state after delete = %s/%q, want uploaded/empty", video.Status, video.OutputPath)
	}

	if err := jobs.DeleteAndResetVideo("missing"); !errors.Is(err, ErrJobNotFound) {
		t.Fatalf("delete missing job error = %v, want ErrJobNotFound", err)
	}
}

func TestDeleteJobKeepsNewerOutput(t *testing.T) {
	database := openTestDB(t)
	videos := NewVideoStore(database)
	jobs := NewJobStore(database)
	createTestVideo(t, videos, "v2")

	first, err := jobs.CreateQueued("v2", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create first job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim first job: %v", err)
	}
	if _, _, err := jobs.CompleteWithOutput(first.ID, "outputs/old.mp4", false, ""); err != nil {
		t.Fatalf("complete first job: %v", err)
	}

	second, err := jobs.CreateQueued("v2", `{"operations":[{"type":"speed","factor":1.5}]}`)
	if err != nil {
		t.Fatalf("create second job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim second job: %v", err)
	}
	if _, _, err := jobs.CompleteWithOutput(second.ID, "outputs/new.mp4", false, ""); err != nil {
		t.Fatalf("complete second job: %v", err)
	}

	if err := jobs.DeleteAndResetVideo(first.ID); err != nil {
		t.Fatalf("delete first job: %v", err)
	}
	video, err := videos.Get("v2")
	if err != nil {
		t.Fatalf("get video: %v", err)
	}
	if video.OutputPath != "outputs/new.mp4" {
		t.Fatalf("video output = %q, want outputs/new.mp4", video.OutputPath)
	}
}

func TestJobOutputEncryptionPersistsToVideo(t *testing.T) {
	database := openTestDB(t)
	videos := NewVideoStore(database)
	jobs := NewJobStore(database)
	createTestVideo(t, videos, "v3")

	job, err := jobs.CreateQueued("v3", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim job: %v", err)
	}
	if _, _, err := jobs.CompleteWithOutput(job.ID, "outputs/enc.mp4", true, "wrapped-key"); err != nil {
		t.Fatalf("complete encrypted job: %v", err)
	}

	video, err := videos.Get("v3")
	if err != nil {
		t.Fatalf("get video: %v", err)
	}
	if !video.OutputEncrypted || video.OutputEncKey != "wrapped-key" {
		t.Fatalf("video output encryption = %v/%q, want true/wrapped-key", video.OutputEncrypted, video.OutputEncKey)
	}

	reloaded, err := jobs.Get(job.ID)
	if err != nil {
		t.Fatalf("get job: %v", err)
	}
	if !reloaded.Encrypted || reloaded.EncKey != "wrapped-key" {
		t.Fatalf("job encryption = %v/%q, want true/wrapped-key", reloaded.Encrypted, reloaded.EncKey)
	}
}
