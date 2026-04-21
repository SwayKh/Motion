package main

import (
	"fmt"
	"path/filepath"
	"strings"

	ffmpeg_go "github.com/u2takey/ffmpeg-go"
)

func getFileNameAndExt(filePath string) (baseName, extension string) {
	filePathBase := filepath.Base(filePath)
	extension = filepath.Ext(filePath)
	baseName = strings.TrimSuffix(filePathBase, extension)

	return baseName, extension
}

func increaseVideoSpeed(filePath string, speed int) error {
	filePathName, filePathExt := getFileNameAndExt(filePath)
	outputPath := filePathName + fmt.Sprintf("_X%d", speed) + filePathExt

	spedUpAudio := speedUpAudio(filePath, speed)
	spedUpVideo := speedUpVideo(filePath, speed)

	// vsync flag keeps all orignial frames
	err := ffmpeg_go.Output([]*ffmpeg_go.Stream{spedUpVideo, spedUpAudio}, outputPath, ffmpeg_go.KwArgs{"vsync": 0}).OverWriteOutput().Run()
	if err != nil {
		return err
	}
	return nil
}

func speedUpAudio(filePath string, speed int) *ffmpeg_go.Stream {
	return ffmpeg_go.Input(filePath).Audio().Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%d", speed)})
}

func speedUpVideo(filePath string, speed int) *ffmpeg_go.Stream {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/float64(speed))
	return ffmpeg_go.Input(filePath).Video().Filter("setpts", ffmpeg_go.Args{reciprocalSpeed})
}

func main() {
	err := increaseVideoSpeed("./example.mp4", 4)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Done")
}
