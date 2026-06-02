package job

import (
	"encoding/json"

	"VideoProject/internal/db"
	"VideoProject/internal/logger"
	"VideoProject/internal/models"
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

		err = store.UpdateStatus(
			video.ID,
			models.StatusProcessing,
		)
		if err != nil {
			continue
		}

		var req models.ProcessRequest

		err = json.Unmarshal(
			[]byte(video.Operations),
			&req,
		)
		if err != nil {
			store.Fail(
				video.ID,
				"invalid saved operations",
			)
			continue
		}

		service.ProcessVideo(
			store,
			video,
			req,
		)
	}
}
