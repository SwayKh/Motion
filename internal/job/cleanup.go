package job

import (
	"errors"
	"os"
	"time"

	"VideoProject/internal/db"
	"VideoProject/internal/logger"
	"VideoProject/internal/models"
)

const (
	cleanupInterval  = time.Hour
	cleanupRetention = 24 * time.Hour
)

func StartCleaner(store *db.VideoStore) {
	ticker := time.NewTicker(cleanupInterval)
	defer ticker.Stop()

	for range ticker.C {
		if err := cleanupExpired(store, time.Now()); err != nil {
			logger.Error.Printf("cleanup failed err=%v", err)
		}
	}
}

func cleanupExpired(store *db.VideoStore, now time.Time) error {
	logger.Info.Println("cleanup started")
	expiredBefore := now.Add(-cleanupRetention)

	videos, err := store.ListExpiredVideos(expiredBefore)
	if err != nil {
		return err
	}

	var cleanupErrors []error
	for _, video := range videos {
		target, deleted, err := store.DeleteExpiredVideo(video.ID, expiredBefore)
		if err != nil {
			cleanupErrors = append(cleanupErrors, err)
			continue
		}
		if !deleted {
			logger.Info.Printf("cleanup skipped video=%s because it is no longer eligible", video.ID)
			continue
		}

		logger.Info.Printf("cleanup deleting video=%s", target.VideoID)
		for _, cleanupJob := range target.Jobs {
			removeJobArtifacts(&models.Job{
				ID:         cleanupJob.ID,
				OutputPath: cleanupJob.OutputPath,
			})
		}
		removeFile(target.OriginalPath, target.VideoID, "upload")
		removeFile(target.OutputPath, target.VideoID, "output")
	}

	return errors.Join(cleanupErrors...)
}

func removeFile(path, videoID, kind string) {
	if path == "" {
		return
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		logger.Error.Printf("cleanup delete %s failed video=%s err=%v", kind, videoID, err)
	}
}
