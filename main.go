package main

import (
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

	// Set process to fail on server restart.
	// This is easier to do than storing the operations and re trying, on restart
	interrupted, err := store.ListProcessing()
	if err != nil {
		logger.Error.Printf("failed to load interrupted jobs: %v", err)
	} else {
		for _, v := range interrupted {
			store.Fail(v.ID, "server restarted while processing")
			logger.Info.Printf("aborted interrupted video=%s", v.ID)
		}
	}

	// At most 3 workers at a time.
	job.StartWorkers(3, store)
	// Cleanup every hour
	go job.StartCleaner(store)

	fs := http.FileServer(http.Dir("./web"))

	mux := http.NewServeMux()
	mux.Handle("/", fs)

	mux.HandleFunc("POST /upload", api.UploadHandler(database))
	mux.HandleFunc("GET /videos/{id}", api.GetVideoHandler(database))
	mux.HandleFunc("POST /videos/{id}/process", api.ProcessHandler(database))
	mux.HandleFunc("GET /videos/{id}/download", api.DownloadHandler(database))

	// Middleware
	handler := api.CORS(mux)

	logger.Info.Println("Starting server on port 8080...")
	if err := http.ListenAndServe(":8080", handler); err != nil {
		log.Fatal(err)
	}
}
