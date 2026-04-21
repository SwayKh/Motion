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

func changeAudioSpeed(filePath string, speed float64) *ffmpeg_go.Stream {
	return ffmpeg_go.Input(filePath).Audio().Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%f", speed)})
}

func changeVideoSpeed(filePath string, speed float64) *ffmpeg_go.Stream {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/speed)
	return ffmpeg_go.Input(filePath).Video().Filter("setpts", ffmpeg_go.Args{reciprocalSpeed})
}

func changeSpeedVideoAudio(filePath string, speed float64) error {
	filePathName, filePathExt := getFileNameAndExt(filePath)
	outputPath := filePathName + fmt.Sprintf("_X%f", speed) + filePathExt

	spedUpAudio := changeAudioSpeed(filePath, speed)
	spedUpVideo := changeVideoSpeed(filePath, speed)

	// vsync flag keeps all orignial frames
	err := MergeStreamAndOutput(spedUpVideo, spedUpAudio, outputPath, ffmpeg_go.KwArgs{"vsync": 0})
	if err != nil {
		return err
	}
	return nil
}

func changeAudioVolume(filePath string, multiplier float64) *ffmpeg_go.Stream {
	return ffmpeg_go.Input(filePath).Audio().Filter("volume", ffmpeg_go.Args{fmt.Sprintf("%f", multiplier)})
}

func changeVideoVolume(filePath string, multiplier float64) error {
	filePathName, filePathExt := getFileNameAndExt(filePath)
	outputPath := filePathName + fmt.Sprintf("_X%f", multiplier) + filePathExt

	amplifiedAudio := changeAudioVolume(filePath, multiplier)
	video := ffmpeg_go.Input(filePath).Video()

	// copy flag copies over the video when only audio has changed, saving
	// computational power
	err := MergeStreamAndOutput(video, amplifiedAudio, outputPath, ffmpeg_go.KwArgs{"c:v": "copy"})
	if err != nil {
		return err
	}
	return nil
}

func MergeStreamAndOutput(video, audio *ffmpeg_go.Stream, outputPath string, KwArgs ffmpeg_go.KwArgs) error {
	err := ffmpeg_go.Output([]*ffmpeg_go.Stream{video, audio}, outputPath, KwArgs).OverWriteOutput().Run()
	if err != nil {
		return fmt.Errorf("output error %w", err)
	}
	return nil
}

func main() {
	multiplier := 4
	filePath := "example.mp4"
	videoSpeedErr := changeSpeedVideoAudio(filePath, float64(multiplier))
	if videoSpeedErr != nil {
		fmt.Println(videoSpeedErr)
	}

	videoVolumeErr := changeVideoVolume(filePath, float64(multiplier))
	if videoVolumeErr != nil {
		fmt.Println(videoVolumeErr)
	}

	fmt.Println("Done")
}
