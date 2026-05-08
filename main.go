package main

import (
	"database/sql"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"VideoProject/cmd/worker/ffmpeg"
	"VideoProject/internal/db"
	"VideoProject/internal/media"
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
		defer file.Close()

		originalPath := "./uploads/" + header.Filename
		dst, err := os.Create(originalPath)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		defer dst.Close()

		io.Copy(dst, file)

		raw, _ := ffmpeg.NewProcessor(originalPath).GetMetadata()
		metaData, _ := media.ExtractMetaData(raw)
		fmt.Printf("%#v", metaData)

		video := db.Video{
			ID:           "123",
			OriginalPath: originalPath,
			Status:       "Uploaded",
		}
		err = db.CreateVideo(database, video)
		if err != nil {
			log.Fatal(err)
		}

		output := "dest/" + header.Filename + ".mp3"
		err = ffmpeg.NewProcessor("./uploads/" + header.Filename).ToAudio().Output(output)
		if err != nil {
			fmt.Println(err)
		}
		fmt.Println("Video converted to audio, and saved to output")
		w.Write([]byte("ok"))
	}
}

func main() {
	database, err := db.Open("app.db")
	if err != nil {
		panic(err)
	}

	// vid, _ := db.GetVideo(database, "123")
	// fmt.Printf("%#v", vid)

	http.HandleFunc("/upload", UploadHandler(database))

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
