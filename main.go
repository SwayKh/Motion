package main

import (
	"fmt"
	"path/filepath"
	"strings"

	ffmpeg_go "github.com/u2takey/ffmpeg-go"
)

type Orientation int

const (
	Vertical Orientation = iota
	Horizontal
)

func getFileNameAndExt(filePath string) (baseName, extension string) {
	filePathBase := filepath.Base(filePath)
	extension = filepath.Ext(filePath)
	baseName = strings.TrimSuffix(filePathBase, extension)

	return baseName, extension
}

func changeAudioSpeed(input *ffmpeg_go.Stream, speed float64) *ffmpeg_go.Stream {
	return input.
		Audio().
		Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%f", speed)})
}

func changeVideoSpeed(input *ffmpeg_go.Stream, speed float64) *ffmpeg_go.Stream {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/speed)
	return input.
		Video().
		Filter("setpts", ffmpeg_go.Args{reciprocalSpeed})
}

func changeSpeed(input, output string, speed float64) error {
	inputStream := ffmpeg_go.Input(input)
	spedUpAudio := changeAudioSpeed(inputStream, speed)
	spedUpVideo := changeVideoSpeed(inputStream, speed)

	// vsync flag keeps all orignial frames
	err := mergeStream(spedUpVideo, spedUpAudio, output, ffmpeg_go.KwArgs{"vsync": 0})
	if err != nil {
		return err
	}
	return nil
}

func buildAudioVoumeFilter(filePath string, multiplier float64) *ffmpeg_go.Stream {
	return ffmpeg_go.Input(filePath).
		Audio().
		Filter("volume", ffmpeg_go.Args{fmt.Sprintf("%f", multiplier)})
}

func changeAudioVolume(input, output string, multiplier float64) error {
	amplifiedAudio := buildAudioVoumeFilter(input, multiplier)
	video := ffmpeg_go.Input(input).Video()

	// copy flag copies over the video when only audio has changed, saving
	// computational power
	err := mergeStream(video, amplifiedAudio, output, ffmpeg_go.KwArgs{"c:v": "copy"})
	if err != nil {
		return err
	}
	return nil
}

func mergeStream(video, audio *ffmpeg_go.Stream, outputPath string, KwArgs ffmpeg_go.KwArgs) error {
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
	err := mergeStream(video, audio, outputPath, ffmpeg_go.KwArgs{"c:a": "copy", "c:v": "libx264"})
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
	input := "testing.mp4"

	filePathName, filePathExt := getFileNameAndExt(input)

	output := "output/" + filePathName + fmt.Sprintf("_x%.2f", multiplier) + filePathExt
	err := changeSpeed(input, output, float64(multiplier))
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video Speed changed")

	output = "output/" + filePathName + fmt.Sprintf("_Volume_x%.2f", multiplier) + filePathExt
	err = changeVideoVolume(input, output, float64(multiplier))
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video Volume changed")

	err = convertToAudio(input)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video converted to audio")

	err = flipVideo(input, "Vert")
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video flipped vertically")

	err = trimVideo(input, "00:00:02", "00:00:26")
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video trimmed")

	fmt.Println("Done")
}
