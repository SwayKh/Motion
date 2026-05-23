package service

import (
	"VideoProject/internal/db"
	"VideoProject/internal/models"
)

type Job struct {
	VideoID string

	Request models.ProcessRequest
}

var Queue = make(chan Job, 100)

func Enqueue(job Job) {
	Queue <- job
}

func StartWorkers(
	n int,
	store *db.VideoStore,
) {
	for i := range n {
		go worker(i, store)
	}
}
