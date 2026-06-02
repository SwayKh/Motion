package job

import (
	"fmt"

	"VideoProject/internal/db"
)

type Job struct {
	VideoID string
}

var Queue = make(chan Job, 100)

func Enqueue(job Job) error {
	select {
	case Queue <- job:
		return nil
	default:
		return fmt.Errorf("job queue is full")
	}
}

func StartWorkers(
	n int,
	store *db.VideoStore,
) {
	for i := range n {
		go worker(i, store)
	}
}
