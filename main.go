package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"path/filepath"
	"strings"

	"VideoProject/internal/db"
	"VideoProject/internal/ffmpeg"
	"VideoProject/internal/models"
	"VideoProject/internal/service"
)

func getFileNameAndExt(filePath string) (baseName, extension string) {
	filePathBase := filepath.Base(filePath)
	extension = filepath.Ext(filePath)
	baseName = strings.TrimSuffix(filePathBase, extension)

	return baseName, extension
}

func processVideo() error {
	var multiplier float64 = 2
	filePath := "testing.mp4"

	filePathName, filePathExt := getFileNameAndExt(filePath)

	output := "output/" + filePathName + fmt.Sprintf("_x%.2f", multiplier) + filePathExt
	err := ffmpeg.NewProcessor(filePath).Speed(multiplier).VSync(0).Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video Speed changed")

	output = "output/" + filePathName + fmt.Sprintf("_Volume_x%.2f", multiplier) + filePathExt
	err = ffmpeg.NewProcessor(filePath).Volume(multiplier).Output(output)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video Volume changed")

	output = "output/" + filePathName + ".mp3"
	err = ffmpeg.NewProcessor(filePath).ToAudio().Output(output)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video converted to audio")

	output = "output/" + filePathName + "_Flipped" + filePathExt
	err = ffmpeg.NewProcessor(filePath).Flip(ffmpeg.Vertical).VideoCodec("libx264").Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video flipped vertically")

	output = "output/" + filePathName + "_Trimmed_" + filePathExt
	err = ffmpeg.NewProcessor(filePath).Trim("00:00:02", "00:00:26").VideoCodec("libx264").Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video trimmed")

	fmt.Println("Done")
	return nil
}

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
		id := strings.TrimPrefix(r.URL.Path, "/videos/")
		id = strings.TrimSuffix(id, "/process")

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
		id := strings.TrimPrefix(r.URL.Path, "/videos/")

		video, err := store.Get(id)
		if err != nil {
			http.Error(w, "video not found", http.StatusNotFound)
			return
		}

		w.Header().Set("Content-Type", "application/json")

		json.NewEncoder(w).Encode(video)
	}
}

func main() {
	database, err := db.Open("data/app.db")
	if err != nil {
		panic(err)
	}

	// vid, _ := db.GetVideo(database, "123")
	// fmt.Printf("%#v", vid)

	http.HandleFunc("/upload", UploadHandler(database))
	http.HandleFunc("/videos/", ProcessHandler(database))
	http.HandleFunc("/get", GetVideoHandler(database))

	fmt.Printf("Starting server on port 8080...")
	if err := http.ListenAndServe(":8080", nil); err != nil {
		log.Fatal(err)
	}

	// out, err := ffmpeg.NewProcessor("example.mp4").GetMetadata()
	// if err != nil {
	// 	log.Fatal(err)
	// }
	//
	// meta, err := media.ExtractMetaData(out)
	// if err != nil {
	// 	log.Fatal(err)
	// }
	//
	// fmt.Printf("%#v\n", meta)
}
