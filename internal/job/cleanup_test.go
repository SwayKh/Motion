package job

import (
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"VideoProject/internal/db"
	"VideoProject/internal/models"
)

type cleanupTestEnv struct {
	videos   *db.VideoStore
	jobs     *db.JobStore
	database *sql.DB
	dir      string
}

func newCleanupTestEnv(t *testing.T) *cleanupTestEnv {
	t.Helper()

	dir := t.TempDir()
	t.Chdir(dir)

	if err := os.MkdirAll("uploads", 0o755); err != nil {
		t.Fatalf("create uploads dir: %v", err)
	}
	if err := os.MkdirAll("outputs", 0o755); err != nil {
		t.Fatalf("create outputs dir: %v", err)
	}

	database, err := db.Open(filepath.Join(dir, "app.db"))
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	t.Cleanup(func() {
		if err := database.Close(); err != nil {
			t.Errorf("close test database: %v", err)
		}
	})

	return &cleanupTestEnv{
		videos:   db.NewVideoStore(database),
		jobs:     db.NewJobStore(database),
		database: database,
		dir:      dir,
	}
}

func writeCleanupFile(t *testing.T, path string) {
	t.Helper()

	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("create parent for %s: %v", path, err)
	}
	if err := os.WriteFile(path, []byte("cleanup fixture"), 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func requireCleanupFile(t *testing.T, path string) {
	t.Helper()

	if _, err := os.Stat(path); err != nil {
		t.Fatalf("expected file %s to exist: %v", path, err)
	}
}

func requireMissingCleanupFile(t *testing.T, path string) {
	t.Helper()

	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("expected file %s to be removed: %v", path, err)
	}
}

func addCleanupVideo(t *testing.T, env *cleanupTestEnv, id, status string) *models.Video {
	t.Helper()

	originalPath := filepath.Join("uploads", id+".mp4")
	writeCleanupFile(t, originalPath)

	video := &models.Video{
		ID:           id,
		OriginalPath: originalPath,
		OriginalName: id + ".mp4",
		FileSize:     1024,
		Status:       status,
	}
	if err := env.videos.Create(video); err != nil {
		t.Fatalf("create video %s: %v", id, err)
	}

	switch status {
	case models.StatusCompleted:
		outputPath := filepath.Join("outputs", id+".mp4")
		writeCleanupFile(t, outputPath)
		if err := env.videos.Complete(id, outputPath); err != nil {
			t.Fatalf("complete video %s: %v", id, err)
		}
	case models.StatusFailed:
		if err := env.videos.Fail(id, "fixture failure"); err != nil {
			t.Fatalf("fail video %s: %v", id, err)
		}
	case models.StatusCancelled:
		if err := env.videos.UpdateStatus(id, models.StatusCancelled); err != nil {
			t.Fatalf("cancel video %s: %v", id, err)
		}
	}

	created, err := env.videos.Get(id)
	if err != nil {
		t.Fatalf("reload video %s: %v", id, err)
	}
	return created
}

type cleanupArtifacts struct {
	job          *models.Job
	originalPath string
	outputPath   string
	standardPath string
	partPath     string
}

func addCompletedCleanupJob(t *testing.T, env *cleanupTestEnv, videoID string) *cleanupArtifacts {
	t.Helper()

	video, err := env.videos.Get(videoID)
	if err != nil {
		t.Fatalf("get video %s: %v", videoID, err)
	}

	job, err := env.jobs.CreateQueued(videoID, `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create job for %s: %v", videoID, err)
	}
	if _, err := env.jobs.ClaimNext(); err != nil {
		t.Fatalf("claim job %s: %v", job.ID, err)
	}

	outputPath := filepath.Join("outputs", "custom-"+job.ID+".mp4")
	standardPath := filepath.Join("outputs", job.ID+".mp4")
	partPath := filepath.Join("outputs", "."+job.ID+".part.mp4")
	writeCleanupFile(t, outputPath)
	writeCleanupFile(t, standardPath)
	writeCleanupFile(t, partPath)

	completed, transitioned, err := env.jobs.Complete(job.ID, outputPath)
	if err != nil {
		t.Fatalf("complete job %s: %v", job.ID, err)
	}
	if !transitioned {
		t.Fatalf("job %s did not transition to completed", job.ID)
	}

	return &cleanupArtifacts{
		job:          completed,
		originalPath: video.OriginalPath,
		outputPath:   outputPath,
		standardPath: standardPath,
		partPath:     partPath,
	}
}

func ageVideo(t *testing.T, env *cleanupTestEnv, videoID string, createdAt time.Time) {
	t.Helper()

	if _, err := env.database.Exec(
		"UPDATE videos SET created_at = ? WHERE id = ?",
		createdAt,
		videoID,
	); err != nil {
		t.Fatalf("age video %s: %v", videoID, err)
	}
}

func ageJob(t *testing.T, env *cleanupTestEnv, jobID string, createdAt, finishedAt time.Time) {
	t.Helper()

	if _, err := env.database.Exec(
		"UPDATE jobs SET created_at = ?, started_at = ?, finished_at = ? WHERE id = ?",
		createdAt,
		createdAt,
		finishedAt,
		jobID,
	); err != nil {
		t.Fatalf("age job %s: %v", jobID, err)
	}
}

func requireCleanupVideoMissing(t *testing.T, env *cleanupTestEnv, id string) {
	t.Helper()

	if _, err := env.videos.Get(id); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("expected video %s to be deleted: %v", id, err)
	}
}

func requireCleanupJobMissing(t *testing.T, env *cleanupTestEnv, id string) {
	t.Helper()

	if _, err := env.jobs.Get(id); !errors.Is(err, db.ErrJobNotFound) {
		t.Fatalf("expected job %s to be deleted: %v", id, err)
	}
}

func TestCleanupDeletesOnlyFullyExpiredVideos(t *testing.T) {
	env := newCleanupTestEnv(t)
	now := time.Now()
	old := now.Add(-25 * time.Hour)

	eligibleVideo := addCleanupVideo(t, env, "eligible", models.StatusUploaded)
	eligible := addCompletedCleanupJob(t, env, eligibleVideo.ID)
	ageVideo(t, env, eligibleVideo.ID, old)
	ageJob(t, env, eligible.job.ID, old, old)

	queuedVideo := addCleanupVideo(t, env, "queued", models.StatusUploaded)
	queuedJob, err := env.jobs.CreateQueued(queuedVideo.ID, `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create queued job: %v", err)
	}
	ageVideo(t, env, queuedVideo.ID, old)

	processingVideo := addCleanupVideo(t, env, "processing", models.StatusUploaded)
	processingJob, err := env.jobs.CreateQueued(processingVideo.ID, `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create processing job: %v", err)
	}
	if _, err := env.jobs.ClaimNext(); err != nil {
		t.Fatalf("claim processing job: %v", err)
	}
	if err := env.jobs.UpdateProgress(processingJob.ID, 50); err != nil {
		t.Fatalf("update processing progress: %v", err)
	}
	ageVideo(t, env, processingVideo.ID, old)

	cancelVideo := addCleanupVideo(t, env, "cancel", models.StatusUploaded)
	cancelJob, err := env.jobs.CreateQueued(cancelVideo.ID, `{"operations":[{"type":"flip"}]}`)
	if err != nil {
		t.Fatalf("create cancellation job: %v", err)
	}
	if _, err := env.jobs.ClaimNext(); err != nil {
		t.Fatalf("claim cancellation job: %v", err)
	}
	if _, _, err := env.jobs.RequestCancel(cancelJob.ID); err != nil {
		t.Fatalf("request cancellation: %v", err)
	}
	ageVideo(t, env, cancelVideo.ID, old)

	recentJobVideo := addCleanupVideo(t, env, "recent-job", models.StatusUploaded)
	recentJob := addCompletedCleanupJob(t, env, recentJobVideo.ID)
	ageVideo(t, env, recentJobVideo.ID, old)

	uploadedVideo := addCleanupVideo(t, env, "uploaded", models.StatusUploaded)
	ageVideo(t, env, uploadedVideo.ID, old)

	recentVideo := addCleanupVideo(t, env, "recent", models.StatusCompleted)

	failedVideo := addCleanupVideo(t, env, "failed", models.StatusFailed)
	ageVideo(t, env, failedVideo.ID, old)

	cancelledVideo := addCleanupVideo(t, env, "cancelled", models.StatusCancelled)
	ageVideo(t, env, cancelledVideo.ID, old)

	if err := cleanupExpired(env.videos, now); err != nil {
		t.Fatalf("cleanup: %v", err)
	}

	requireCleanupVideoMissing(t, env, eligibleVideo.ID)
	requireCleanupJobMissing(t, env, eligible.job.ID)
	requireMissingCleanupFile(t, eligible.originalPath)
	requireMissingCleanupFile(t, eligible.outputPath)
	requireMissingCleanupFile(t, eligible.standardPath)
	requireMissingCleanupFile(t, eligible.partPath)

	for _, video := range []*models.Video{failedVideo, cancelledVideo} {
		requireCleanupVideoMissing(t, env, video.ID)
		requireMissingCleanupFile(t, video.OriginalPath)
	}

	for _, test := range []struct {
		name        string
		videoID     string
		jobID       string
		original    string
		recentJob   bool
		expectVideo bool
	}{
		{name: "queued", videoID: queuedVideo.ID, jobID: queuedJob.ID, original: queuedVideo.OriginalPath},
		{name: "processing", videoID: processingVideo.ID, jobID: processingJob.ID, original: processingVideo.OriginalPath},
		{name: "cancel requested", videoID: cancelVideo.ID, jobID: cancelJob.ID, original: cancelVideo.OriginalPath},
		{name: "recent job", videoID: recentJobVideo.ID, jobID: recentJob.job.ID, original: recentJob.originalPath, recentJob: true},
		{name: "old uploaded", videoID: uploadedVideo.ID, original: uploadedVideo.OriginalPath},
		{name: "recent video", videoID: recentVideo.ID, original: recentVideo.OriginalPath},
	} {
		t.Run(test.name, func(t *testing.T) {
			if _, err := env.videos.Get(test.videoID); err != nil {
				t.Fatalf("video %s was deleted: %v", test.videoID, err)
			}
			if test.jobID != "" {
				if _, err := env.jobs.Get(test.jobID); err != nil {
					t.Fatalf("job %s was deleted: %v", test.jobID, err)
				}
			}
			requireCleanupFile(t, test.original)
			if test.recentJob {
				requireCleanupFile(t, recentJob.outputPath)
				requireCleanupFile(t, recentJob.standardPath)
			}
		})
	}
}

func TestCleanupRechecksEligibilityAfterStaleSnapshot(t *testing.T) {
	env := newCleanupTestEnv(t)
	now := time.Now()
	old := now.Add(-25 * time.Hour)

	video := addCleanupVideo(t, env, "stale", models.StatusUploaded)
	artifacts := addCompletedCleanupJob(t, env, video.ID)
	ageVideo(t, env, video.ID, old)
	ageJob(t, env, artifacts.job.ID, old, old)

	candidates, err := env.videos.ListExpiredVideos(now.Add(-24 * time.Hour))
	if err != nil {
		t.Fatalf("list expired videos: %v", err)
	}
	if len(candidates) != 1 || candidates[0].ID != video.ID {
		t.Fatalf("candidates = %v, want video %s", candidates, video.ID)
	}

	queued, err := env.jobs.CreateQueued(video.ID, `{"operations":[{"type":"speed","factor":1.5}]}`)
	if err != nil {
		t.Fatalf("queue stale candidate: %v", err)
	}

	target, deleted, err := env.videos.DeleteExpiredVideo(video.ID, now.Add(-24*time.Hour))
	if err != nil {
		t.Fatalf("delete stale candidate: %v", err)
	}
	if deleted || target != nil {
		t.Fatalf("stale candidate was deleted: target=%v deleted=%v", target, deleted)
	}

	if _, err := env.videos.Get(video.ID); err != nil {
		t.Fatalf("video was deleted: %v", err)
	}
	if _, err := env.jobs.Get(queued.ID); err != nil {
		t.Fatalf("queued job was deleted: %v", err)
	}
	requireCleanupFile(t, artifacts.originalPath)
	requireCleanupFile(t, artifacts.outputPath)
}

func TestCleanupKeepsFilesWhenDatabaseDeleteFails(t *testing.T) {
	env := newCleanupTestEnv(t)
	now := time.Now()
	old := now.Add(-25 * time.Hour)

	video := addCleanupVideo(t, env, "blocked", models.StatusUploaded)
	artifacts := addCompletedCleanupJob(t, env, video.ID)
	ageVideo(t, env, video.ID, old)
	ageJob(t, env, artifacts.job.ID, old, old)

	if _, err := env.database.Exec(`
		CREATE TRIGGER block_cleanup
		BEFORE DELETE ON videos
		BEGIN
			SELECT RAISE(ABORT, 'cleanup blocked');
		END
	`); err != nil {
		t.Fatalf("create cleanup blocker: %v", err)
	}

	if err := cleanupExpired(env.videos, now); err == nil {
		t.Fatal("cleanup succeeded despite database deletion failure")
	}

	if _, err := env.videos.Get(video.ID); err != nil {
		t.Fatalf("video row was removed: %v", err)
	}
	if _, err := env.jobs.Get(artifacts.job.ID); err != nil {
		t.Fatalf("job row was removed: %v", err)
	}
	requireCleanupFile(t, artifacts.originalPath)
	requireCleanupFile(t, artifacts.outputPath)
	requireCleanupFile(t, artifacts.standardPath)
	requireCleanupFile(t, artifacts.partPath)

	if _, err := env.database.Exec("DROP TRIGGER block_cleanup"); err != nil {
		t.Fatalf("drop cleanup blocker: %v", err)
	}
	if err := cleanupExpired(env.videos, now); err != nil {
		t.Fatalf("cleanup after unblocking: %v", err)
	}
	requireCleanupVideoMissing(t, env, video.ID)
	requireMissingCleanupFile(t, artifacts.originalPath)
	requireMissingCleanupFile(t, artifacts.outputPath)
}

func TestCleanupConcurrentQueueAndDelete(t *testing.T) {
	env := newCleanupTestEnv(t)
	now := time.Now()
	old := now.Add(-25 * time.Hour)

	const videoCount = 8
	ids := make([]string, 0, videoCount)
	for i := 0; i < videoCount; i++ {
		id := "race-" + string(rune('a'+i))
		video := addCleanupVideo(t, env, id, models.StatusUploaded)
		artifacts := addCompletedCleanupJob(t, env, video.ID)
		ageVideo(t, env, video.ID, old)
		ageJob(t, env, artifacts.job.ID, old, old)
		ids = append(ids, id)
	}

	type queueResult struct {
		videoID string
		job     *models.Job
		err     error
	}

	start := make(chan struct{})
	results := make(chan queueResult, len(ids))
	var wg sync.WaitGroup

	for _, id := range ids {
		wg.Add(1)
		go func(videoID string) {
			defer wg.Done()
			<-start
			job, err := env.jobs.CreateQueued(videoID, `{"operations":[{"type":"flip"}]}`)
			results <- queueResult{videoID: videoID, job: job, err: err}
		}(id)
	}

	cleanupDone := make(chan error, 1)
	go func() {
		<-start
		cleanupDone <- cleanupExpired(env.videos, now)
	}()

	close(start)
	wg.Wait()
	if err := <-cleanupDone; err != nil {
		t.Fatalf("cleanup: %v", err)
	}
	close(results)

	for result := range results {
		switch {
		case result.err == nil:
			if _, err := env.videos.Get(result.videoID); err != nil {
				t.Fatalf("queued video %s was deleted: %v", result.videoID, err)
			}
			if _, err := env.jobs.Get(result.job.ID); err != nil {
				t.Fatalf("queued job %s was deleted: %v", result.job.ID, err)
			}
			requireCleanupFile(t, filepath.Join("uploads", result.videoID+".mp4"))
		case errors.Is(result.err, db.ErrVideoNotFound):
			if _, err := env.videos.Get(result.videoID); !errors.Is(err, sql.ErrNoRows) {
				t.Fatalf("cleanup won the race but video %s still exists: %v", result.videoID, err)
			}
		default:
			t.Fatalf("queue %s failed: %v", result.videoID, result.err)
		}
	}
}
