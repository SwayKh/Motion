package main

import (
	"fmt"
	"path/filepath"
	"strings"

	ffmpeg_go "github.com/u2takey/ffmpeg-go"
)

func speedUpVideo(filePath string, speed int) error {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/float64(speed))

	filePathBase := filepath.Base(filePath)
	filePathExt := filepath.Ext(filePath)
	filePathName := strings.TrimSuffix(filePathBase, filePathExt)

	outputPath := filePathName + fmt.Sprintf("_X%d", speed) + filePathExt

	inputFile := ffmpeg_go.Input(filePath)
	spedUpVideo := inputFile.Video().Filter("setpts", ffmpeg_go.Args{reciprocalSpeed}) // -r flag for keeping all original frames
	spedUpAudio := inputFile.Audio().Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%d", speed)})

	err := ffmpeg_go.Output([]*ffmpeg_go.Stream{spedUpVideo, spedUpAudio}, outputPath, ffmpeg_go.KwArgs{"vsync": 0}).OverWriteOutput().Run()
	if err != nil {
		return err
	}
	return nil
}

func main() {
	err := speedUpVideo("./example.mp4", 4)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Done")
}
