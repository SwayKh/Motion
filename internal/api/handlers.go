package api

import (
	"database/sql"
	"encoding/json"
	"net/http"

	"VideoProject/internal/db"
	"VideoProject/internal/job"
	"VideoProject/internal/models"
	"VideoProject/internal/service"
	"VideoProject/internal/validation"
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

		err = validation.ValidateProcessRequest(video, req)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}

		opsJSON, err := json.Marshal(req)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		err = store.Queue(id, string(opsJSON))
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		err = job.Enqueue(job.Job{VideoID: id})
		if err != nil {
			// Revert the queued status since we couldn't actually enqueue
			store.UpdateStatus(id, models.StatusUploaded)
			http.Error(w, "server is busy, try again later", http.StatusServiceUnavailable)
			return
		}

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
