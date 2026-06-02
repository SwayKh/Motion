package job

import (
	"os"
	"time"

	"VideoProject/internal/db"
	"VideoProject/internal/logger"
)

func StartCleaner(store *db.VideoStore) {
	ticker := time.NewTicker(1 * time.Hour)

	for range ticker.C {

		logger.Info.Println("cleanup started")
		expiredBefore := time.Now().Add(-24 * time.Hour)

		videos, err := store.ListExpiredVideos(expiredBefore)
		if err != nil {
			logger.Error.Printf("cleanup query failed err=%v", err)
			continue
		}

		for _, video := range videos {

			logger.Info.Printf("cleanup deleting=%s", video.ID)

			// delete upload
			if video.OriginalPath != "" {
				err := os.Remove(video.OriginalPath)
				if err != nil && !os.IsNotExist(err) {
					logger.Error.Printf("cleanup delete upload failed video=%s err=%v", video.ID, err)
				}
			}

			// delete output
			if video.OutputPath != "" {
				err := os.Remove(video.OutputPath)
				if err != nil && !os.IsNotExist(err) {
					logger.Error.Printf("cleanup delete output failed video=%s err=%v", video.ID, err)
				}
			}

			// remove DB row
			err := store.Delete(video.ID)
			if err != nil {
				logger.Error.Printf("cleanup delete db row failed video=%s err=%v", video.ID, err)
			}
		}
	}
}
