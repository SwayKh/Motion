package main

import (
	"fmt"
	"log"
	"net/http"

	"VideoProject/internal/api"
	"VideoProject/internal/db"
	"VideoProject/internal/service"
)

func main() {
	database, err := db.Open("data/app.db")
	if err != nil {
		panic(err)
	}

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
