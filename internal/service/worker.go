package service

import (
	"fmt"

	"VideoProject/internal/db"
)

func worker(
	id int,
	store *db.VideoStore,
) {
	for job := range Queue {

		fmt.Println("WORKER", id, "PROCESSING", job.VideoID)

		video, err := store.Get(job.VideoID)
		if err != nil {
			fmt.Println(err)
			continue
		}

		ProcessVideo(
			store,
			video,
			job.Request,
		)
	}
}
