package job

import (
	"VideoProject/internal/db"
	"VideoProject/internal/logger"
	"VideoProject/internal/service"
)

func worker(
	id int,
	store *db.VideoStore,
) {
	for job := range Queue {

		logger.Info.Printf("worker=%d processing=%s", id, job.VideoID)

		video, err := store.Get(job.VideoID)
		if err != nil {
			logger.Error.Printf("worker=%d failed loading video=%s err=%v", id, job.VideoID, err)
			continue
		}

		service.ProcessVideo(
			store,
			video,
			job.Request,
		)
	}
}
