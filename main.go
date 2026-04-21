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
	return ffmpeg_go.Input(filePath).
		Audio().
		Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%f", speed)})
}

func changeVideoSpeed(filePath string, speed float64) *ffmpeg_go.Stream {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/speed)
	return ffmpeg_go.Input(filePath).
		Video().
		Filter("setpts", ffmpeg_go.Args{reciprocalSpeed})
}

func changeSpeedVideoAudio(filePath string, speed float64) error {
	filePathName, filePathExt := getFileNameAndExt(filePath)
	outputPath := "output/" + filePathName + fmt.Sprintf("_x%d", int(speed)) + filePathExt

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
	return ffmpeg_go.Input(filePath).
		Audio().
		Filter("volume", ffmpeg_go.Args{fmt.Sprintf("%f", multiplier)})
}

func changeVideoVolume(filePath string, multiplier float64) error {
	filePathName, filePathExt := getFileNameAndExt(filePath)
	outputPath := "output/" + filePathName + fmt.Sprintf("_Volume_x%d", int(multiplier)) + filePathExt

	amplifiedAudio := changeAudioVolume(filePath, multiplier)
	video := ffmpeg_go.Input(filePath).
		Video()

	// copy flag copies over the video when only audio has changed, saving
	// computational power
	err := MergeStreamAndOutput(video, amplifiedAudio, outputPath, ffmpeg_go.KwArgs{"c:v": "copy"})
	if err != nil {
		return err
	}
	return nil
}

func MergeStreamAndOutput(video, audio *ffmpeg_go.Stream, outputPath string, KwArgs ffmpeg_go.KwArgs) error {
	err := ffmpeg_go.Output([]*ffmpeg_go.Stream{video, audio}, outputPath, KwArgs).
		OverWriteOutput().
		GlobalArgs("-loglevel", "error", "-stats").
		Run()
	if err != nil {
		return fmt.Errorf("output error %w", err)
	}
	return nil
}

func convertToAudio(filePath string) error {
	filePathName, _ := getFileNameAndExt(filePath)
	outputPath := "output/" + filePathName + ".mp3"

	// vn flag removes video output, "c:a" flag copies over the audio
	err := ffmpeg_go.Input(filePath).
		Output(outputPath, ffmpeg_go.KwArgs{"vn": "", "q:a": 2}).
		OverWriteOutput().
		GlobalArgs("-loglevel", "error", "-stats").
		Run()
	if err != nil {
		return fmt.Errorf("convert to audio error: %w", err)
	}
	return nil
}

// orientation = Vert|Horiz
func flipVideo(filePath string, orientation string) error {
	filePathName, filePathExt := getFileNameAndExt(filePath)
	outputPath := "output/" + filePathName + fmt.Sprintf("_%s_Flipped", orientation) + filePathExt

	input := ffmpeg_go.Input(filePath)
	var video *ffmpeg_go.Stream

	switch orientation {
	case "Vert":
		video = input.Video().VFlip()
	case "Horiz":
		video = input.Video().HFlip()
	default:
		return fmt.Errorf("wrong orientation")
	}

	audio := input.Audio()
	err := MergeStreamAndOutput(video, audio, outputPath, ffmpeg_go.KwArgs{"c:a": "copy", "c:v": "libx264"})
	if err != nil {
		return err
	}
	return nil
}

func trimVideo(filePath, sTime, eTime string) error {
	filePathName, filePathExt := getFileNameAndExt(filePath)
	outputPath := "output/" + filePathName + "_Trimmed_" + filePathExt

	err := ffmpeg_go.Input(filePath, ffmpeg_go.KwArgs{"ss": sTime}).
		Output(outputPath, ffmpeg_go.KwArgs{"to": eTime, "c:a": "copy", "c:v": "libx264"}).
		OverWriteOutput().
		GlobalArgs("-loglevel", "error", "-stats").
		Run()
	if err != nil {
		return err
	}
	return nil
}

func main() {
	multiplier := 2
	filePath := "testing.mp4"

	err := changeSpeedVideoAudio(filePath, float64(multiplier))
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video Speed changed")

	err = changeVideoVolume(filePath, float64(multiplier))
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video Volume changed")

	err = convertToAudio(filePath)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video converted to audio")

	err = flipVideo(filePath, "Vert")
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video flipped vertically")

	err = trimVideo(filePath, "00:00:02", "00:00:26")
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video trimmed")

	fmt.Println("Done")
}
