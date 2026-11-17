package db

import (
	"database/sql"
	"errors"
	"path/filepath"
	"testing"

	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
)

func init() {
	logger.Init()
}

func openTestDB(t *testing.T) *sql.DB {
	t.Helper()

	database, err := Open(filepath.Join(t.TempDir(), "app.db"))
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	t.Cleanup(func() {
		if err := database.Close(); err != nil {
			t.Errorf("close test database: %v", err)
		}
	})
	return database
}

func createTestVideo(t *testing.T, store *VideoStore, id string) {
	t.Helper()

	video := &models.Video{
		ID:           id,
		OriginalPath: filepath.Join(t.TempDir(), id+".mp4"),
		OriginalName: id + ".mp4",
		FileSize:     1024,
		Status:       models.StatusUploaded,
	}
	if err := store.Create(video); err != nil {
		t.Fatalf("create test video: %v", err)
	}
}

func TestJobLifecycle(t *testing.T) {
	database := openTestDB(t)
	videos := NewVideoStore(database)
	jobs := NewJobStore(database)
	createTestVideo(t, videos, "video-1")

	first, err := jobs.CreateQueued("video-1", `{"operations":[{"type":"trim"}]}`)
	if err != nil {
		t.Fatalf("create queued job: %v", err)
	}
	if first.Status != models.StatusQueued {
		t.Fatalf("status = %q, want queued", first.Status)
	}
	if first.VideoName != "video-1.mp4" {
		t.Fatalf("video name = %q, want snapshot", first.VideoName)
	}
	if first.SourcePath == "" {
		t.Fatal("source path was not snapshotted")
	}

	video, err := videos.Get("video-1")
	if err != nil {
		t.Fatalf("get queued video: %v", err)
	}
	if video.Status != models.StatusQueued {
		t.Fatalf("video status = %q, want queued", video.Status)
	}

	if _, err := jobs.CreateQueued("video-1", `{"operations":[{"type":"flip"}]}`); !errors.Is(err, ErrActiveJob) {
		t.Fatalf("second active job error = %v, want ErrActiveJob", err)
	}

	claimed, err := jobs.ClaimNext()
	if err != nil {
		t.Fatalf("claim job: %v", err)
	}
	if claimed.ID != first.ID || claimed.Status != models.StatusProcessing {
		t.Fatalf("claimed = %s/%s, want %s/processing", claimed.ID, claimed.Status, first.ID)
	}
	if claimed.StartedAt == nil {
		t.Fatal("started timestamp was not set")
	}

	if err := jobs.UpdateProgress(first.ID, 37.5); err != nil {
		t.Fatalf("update progress: %v", err)
	}
	progressed, err := jobs.Get(first.ID)
	if err != nil {
		t.Fatalf("get progressed job: %v", err)
	}
	if progressed.Progress != 37.5 {
		t.Fatalf("job progress = %v, want 37.5", progressed.Progress)
	}
	video, err = videos.Get("video-1")
	if err != nil {
		t.Fatalf("get progressed video: %v", err)
	}
	if video.Progress != 37.5 {
		t.Fatalf("video progress = %v, want 37.5", video.Progress)
	}

	completed, transitioned, err := jobs.Complete(first.ID, "outputs/first.mp4")
	if err != nil {
		t.Fatalf("complete job: %v", err)
	}
	if !transitioned || completed.Status != models.StatusCompleted {
		t.Fatalf("complete transition = %v/%s, want true/completed", transitioned, completed.Status)
	}
	if completed.OutputPath != "outputs/first.mp4" || completed.Progress != 100 {
		t.Fatalf("completed output/progress = %q/%v", completed.OutputPath, completed.Progress)
	}
	video, err = videos.Get("video-1")
	if err != nil {
		t.Fatalf("get completed video: %v", err)
	}
	if video.Status != models.StatusCompleted || video.OutputPath != "outputs/first.mp4" {
		t.Fatalf("video after completion = %s/%s", video.Status, video.OutputPath)
	}

	second, err := jobs.CreateQueued("video-1", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create second job after completion: %v", err)
	}
	if second.ID == first.ID {
		t.Fatal("second attempt reused the first job ID")
	}
	video, err = videos.Get("video-1")
	if err != nil {
		t.Fatalf("get requeued video: %v", err)
	}
	if video.Status != models.StatusQueued || video.OutputPath != "" {
		t.Fatalf("video after new attempt = %s/%q, want queued/empty", video.Status, video.OutputPath)
	}

	claimed, err = jobs.ClaimNext()
	if err != nil {
		t.Fatalf("claim second job: %v", err)
	}
	if claimed.ID != second.ID {
		t.Fatalf("claimed second = %s, want %s", claimed.ID, second.ID)
	}

	cancelled, running, err := jobs.RequestCancel(second.ID)
	if err != nil {
		t.Fatalf("request cancel: %v", err)
	}
	if !running || cancelled.Status != models.StatusCancelRequested {
		t.Fatalf("cancel request = %v/%s, want true/cancel_requested", running, cancelled.Status)
	}
	video, err = videos.Get("video-1")
	if err != nil {
		t.Fatalf("get cancelling video: %v", err)
	}
	if video.Status != models.StatusCancelRequested {
		t.Fatalf("video status = %q, want cancel_requested", video.Status)
	}

	finished, transitioned, err := jobs.MarkCancelled(second.ID)
	if err != nil {
		t.Fatalf("mark cancelled: %v", err)
	}
	if !transitioned || finished.Status != models.StatusCancelled {
		t.Fatalf("cancel transition = %v/%s, want true/cancelled", transitioned, finished.Status)
	}
	if _, transitioned, err := jobs.MarkCancelled(second.ID); err != nil || transitioned {
		t.Fatalf("second mark cancelled = %v/%v, want false/nil", transitioned, err)
	}
	video, err = videos.Get("video-1")
	if err != nil {
		t.Fatalf("get cancelled video: %v", err)
	}
	if video.Status != models.StatusCancelled || video.OutputPath != "" {
		t.Fatalf("video after cancellation = %s/%q", video.Status, video.OutputPath)
	}

	third, err := jobs.CreateQueued("video-1", `{"operations":[{"type":"speed"}]}`)
	if err != nil {
		t.Fatalf("create third job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim third job: %v", err)
	}
	failed, transitioned, err := jobs.Fail(third.ID, "ffmpeg exploded")
	if err != nil {
		t.Fatalf("fail job: %v", err)
	}
	if !transitioned || failed.Status != models.StatusFailed || failed.Error != "ffmpeg exploded" {
		t.Fatalf("failed transition = %v/%s/%q", transitioned, failed.Status, failed.Error)
	}
	video, err = videos.Get("video-1")
	if err != nil {
		t.Fatalf("get failed video: %v", err)
	}
	if video.Status != models.StatusFailed || video.Error != "ffmpeg exploded" || video.OutputPath != "" {
		t.Fatalf("video after failure = %s/%q/%q", video.Status, video.Error, video.OutputPath)
	}

	history, err := jobs.ListForVideo("video-1")
	if err != nil {
		t.Fatalf("list history: %v", err)
	}
	if len(history) != 3 {
		t.Fatalf("history length = %d, want 3", len(history))
	}
}

func TestQueuedJobCancellation(t *testing.T) {
	database := openTestDB(t)
	videos := NewVideoStore(database)
	jobs := NewJobStore(database)
	createTestVideo(t, videos, "video-1")

	job, err := jobs.CreateQueued("video-1", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create queued job: %v", err)
	}

	cancelled, running, err := jobs.RequestCancel(job.ID)
	if err != nil {
		t.Fatalf("cancel queued job: %v", err)
	}
	if running {
		t.Fatal("queued cancellation unexpectedly requested a process interrupt")
	}
	if cancelled.Status != models.StatusCancelled || cancelled.FinishedAt == nil {
		t.Fatalf("queued cancellation = %s/%v", cancelled.Status, cancelled.FinishedAt)
	}
	if _, _, err := jobs.RequestCancel(job.ID); !errors.Is(err, ErrJobNotCancellable) {
		t.Fatalf("second cancellation error = %v, want ErrJobNotCancellable", err)
	}
}

func TestRequeueAndClaimOrder(t *testing.T) {
	database := openTestDB(t)
	videos := NewVideoStore(database)
	jobs := NewJobStore(database)
	createTestVideo(t, videos, "video-1")
	createTestVideo(t, videos, "video-2")

	if _, err := jobs.CreateQueued("video-1", `{"operations":[{"type":"flip"}]}`); err != nil {
		t.Fatalf("create first job: %v", err)
	}
	if _, err := jobs.CreateQueued("video-2", `{"operations":[{"type":"flip"}]}`); err != nil {
		t.Fatalf("create second job: %v", err)
	}

	claimed, err := jobs.ClaimNext()
	if err != nil {
		t.Fatalf("claim first job: %v", err)
	}

	requeued, transitioned, err := jobs.Requeue(claimed.ID)
	if err != nil {
		t.Fatalf("requeue job: %v", err)
	}
	if !transitioned || requeued.Status != models.StatusQueued || requeued.StartedAt != nil {
		t.Fatalf("requeue = %v/%s/%v", transitioned, requeued.Status, requeued.StartedAt)
	}

	reclaimed, err := jobs.ClaimNext()
	if err != nil {
		t.Fatalf("reclaim job: %v", err)
	}
	if reclaimed.ID != claimed.ID {
		t.Fatalf("reclaimed = %s, want requeued %s", reclaimed.ID, claimed.ID)
	}
}

func TestStaleCompletionAfterCancelRequested(t *testing.T) {
	database := openTestDB(t)
	videos := NewVideoStore(database)
	jobs := NewJobStore(database)
	createTestVideo(t, videos, "video-1")

	job, err := jobs.CreateQueued("video-1", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create job: %v", err)
	}
	if _, err := jobs.ClaimNext(); err != nil {
		t.Fatalf("claim job: %v", err)
	}
	if _, _, err := jobs.RequestCancel(job.ID); err != nil {
		t.Fatalf("request cancel: %v", err)
	}

	if _, transitioned, err := jobs.Complete(job.ID, "outputs/stale.mp4"); err != nil || transitioned {
		t.Fatalf("stale completion = %v/%v, want false/nil", transitioned, err)
	}
	if _, transitioned, err := jobs.Fail(job.ID, "stale failure"); err != nil || transitioned {
		t.Fatalf("stale failure = %v/%v, want false/nil", transitioned, err)
	}

	cancelled, transitioned, err := jobs.MarkCancelled(job.ID)
	if err != nil {
		t.Fatalf("mark cancelled: %v", err)
	}
	if !transitioned || cancelled.Status != models.StatusCancelled {
		t.Fatalf("cancellation = %v/%s", transitioned, cancelled.Status)
	}
}

func TestJobCascadeOnVideoDelete(t *testing.T) {
	database := openTestDB(t)
	videos := NewVideoStore(database)
	jobs := NewJobStore(database)
	createTestVideo(t, videos, "video-1")

	job, err := jobs.CreateQueued("video-1", `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create job: %v", err)
	}
	if err := videos.Delete("video-1"); err != nil {
		t.Fatalf("delete video: %v", err)
	}
	if _, err := jobs.Get(job.ID); !errors.Is(err, ErrJobNotFound) {
		t.Fatalf("job after video delete error = %v, want ErrJobNotFound", err)
	}
}

func TestMigrationBackfillsLegacyJobs(t *testing.T) {
	path := filepath.Join(t.TempDir(), "legacy.db")
	raw, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatalf("open raw database: %v", err)
	}

	if _, err := raw.Exec(`
		CREATE TABLE schema_migrations (
			version INTEGER PRIMARY KEY,
			applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
		);
		INSERT INTO schema_migrations (version) VALUES (1);
	`); err != nil {
		t.Fatalf("prepare migration table: %v", err)
	}

	tx, err := raw.Begin()
	if err != nil {
		t.Fatalf("begin migration: %v", err)
	}
	if err := migrateVideos(tx); err != nil {
		t.Fatalf("apply videos migration: %v", err)
	}
	if err := tx.Commit(); err != nil {
		t.Fatalf("commit videos migration: %v", err)
	}

	if _, err := raw.Exec(`
		INSERT INTO videos (id, original_path, original_name, status, progress, operations)
		VALUES
			('queued-video', '/tmp/queued.mp4', 'queued.mp4', 'queued', 0, '{"operations":[{"type":"trim"}]}'),
			('completed-video', '/tmp/completed.mp4', 'completed.mp4', 'completed', 100, '{"operations":[{"type":"flip"}]}'),
			('uploaded-video', '/tmp/uploaded.mp4', 'uploaded.mp4', 'uploaded', 0, NULL);
	`); err != nil {
		t.Fatalf("insert legacy videos: %v", err)
	}
	if _, err := raw.Exec(`
		UPDATE videos
		SET output_path = '/tmp/completed-output.mp4'
		WHERE id = 'completed-video';
	`); err != nil {
		t.Fatalf("set legacy output: %v", err)
	}
	if err := raw.Close(); err != nil {
		t.Fatalf("close raw database: %v", err)
	}

	database, err := Open(path)
	if err != nil {
		t.Fatalf("open migrated database: %v", err)
	}
	defer database.Close()

	jobs := NewJobStore(database)
	all, err := jobs.List(50)
	if err != nil {
		t.Fatalf("list migrated jobs: %v", err)
	}
	if len(all) != 2 {
		t.Fatalf("migrated jobs = %d, want 2", len(all))
	}

	byVideo := make(map[string]models.Job, len(all))
	for _, job := range all {
		byVideo[job.VideoID] = job
	}
	queued, ok := byVideo["queued-video"]
	if !ok {
		t.Fatal("queued legacy job was not backfilled")
	}
	if queued.ID != "legacy-queued-video" || queued.Status != models.StatusQueued {
		t.Fatalf("queued legacy job = %s/%s", queued.ID, queued.Status)
	}
	if queued.SourcePath != "/tmp/queued.mp4" || queued.VideoName != "queued.mp4" {
		t.Fatalf("queued legacy snapshot = %q/%q", queued.SourcePath, queued.VideoName)
	}

	completed, ok := byVideo["completed-video"]
	if !ok {
		t.Fatal("completed legacy job was not backfilled")
	}
	if completed.Status != models.StatusCompleted || completed.OutputPath != "/tmp/completed-output.mp4" {
		t.Fatalf("completed legacy job = %s/%q", completed.Status, completed.OutputPath)
	}
	if _, ok := byVideo["uploaded-video"]; ok {
		t.Fatal("uploaded video should not have a legacy job")
	}
}
