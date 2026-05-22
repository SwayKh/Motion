package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"

	"VideoProject/internal/db"
	"VideoProject/internal/models"
	"VideoProject/internal/service"
)

func UploadHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)
	videoService := service.NewVideoService(store)

	return func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, 150<<20)

		err := r.ParseMultipartForm(32 << 20)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		file, header, err := r.FormFile("video")
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		video, err := videoService.Upload(file, header)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		w.Header().Set("Content-Type", "application/json")

		json.NewEncoder(w).Encode(video)
	}
}

func ProcessHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")

		video, err := store.Get(id)
		if err != nil {
			http.Error(w, "video not found", http.StatusNotFound)
			return
		}

		if video.Status != models.StatusUploaded {
			http.Error(w, "video already processing", http.StatusBadRequest)
			return
		}

		var req models.ProcessRequest

		err = json.NewDecoder(r.Body).Decode(&req)
		if err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}

		err = store.UpdateStatus(id, models.StatusProcessing)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		go service.ProcessVideo(store, video, req)

		w.WriteHeader(http.StatusAccepted)

		json.NewEncoder(w).Encode(map[string]any{
			"id":     id,
			"status": "processing",
		})
	}
}

func GetVideoHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")

		video, err := store.Get(id)
		if err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")

		json.NewEncoder(w).Encode(video)
	}
}

func DownloadHandler(database *sql.DB) http.HandlerFunc {
	store := db.NewVideoStore(database)

	return func(w http.ResponseWriter, r *http.Request) {
		id := r.PathValue("id")

		video, err := store.Get(id)
		if err != nil {
			http.Error(w, "video not found", http.StatusNotFound)
			return
		}

		if video.Status != models.StatusCompleted {
			http.Error(w, "video not ready", http.StatusBadRequest)
			return
		}

		http.ServeFile(w, r, video.OutputPath)
	}
}

func main() {
	database, err := db.Open("data/app.db")
	if err != nil {
		panic(err)
	}

	mux := http.NewServeMux()

	mux.HandleFunc("POST /upload", UploadHandler(database))
	mux.HandleFunc("GET /videos/{id}", GetVideoHandler(database))
	mux.HandleFunc("POST /videos/{id}/process", ProcessHandler(database))
	mux.HandleFunc("GET /videos/{id}/download", DownloadHandler(database))
	http.ListenAndServe(":8080", mux)

	fmt.Printf("Starting server on port 8080...")
	if err := http.ListenAndServe(":8080", nil); err != nil {
		log.Fatal(err)
	}
}
