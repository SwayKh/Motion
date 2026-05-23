package main

import (
	"fmt"
	"log"
	"net/http"

	"VideoProject/internal/api"
	"VideoProject/internal/db"
	"VideoProject/internal/job"
	"VideoProject/internal/logger"
)

func main() {
	logger.Init()
	database, err := db.Open("data/app.db")
	if err != nil {
		panic(err)
	}
	store := db.NewVideoStore(database)

	job.StartWorkers(3, store)
	go job.StartCleaner(store)

	mux := http.NewServeMux()

	mux.HandleFunc("POST /upload", api.UploadHandler(database))
	mux.HandleFunc("GET /videos/{id}", api.GetVideoHandler(database))
	mux.HandleFunc("POST /videos/{id}/process", api.ProcessHandler(database))
	mux.HandleFunc("GET /videos/{id}/download", api.DownloadHandler(database))

	handler := api.CORS(mux)
	http.ListenAndServe(":8080", handler)

	fmt.Printf("Starting server on port 8080...")
	if err := http.ListenAndServe(":8080", nil); err != nil {
		log.Fatal(err)
	}
}
