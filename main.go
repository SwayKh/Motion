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

type Processor struct {
	input *ffmpeg_go.Stream
	audio *ffmpeg_go.Stream
	video *ffmpeg_go.Stream

	kwArgs ffmpeg_go.KwArgs
}

func NewProcessor(path string) *Processor {
	in := ffmpeg_go.Input(path)

	return &Processor{
		input:  in,
		audio:  in.Audio(),
		video:  in.Video(),
		kwArgs: ffmpeg_go.KwArgs{},
	}
}

func getFileNameAndExt(filePath string) (baseName, extension string) {
	filePathBase := filepath.Base(filePath)
	extension = filepath.Ext(filePath)
	baseName = strings.TrimSuffix(filePathBase, extension)

	return baseName, extension
}

func (p *Processor) changeAudioSpeed(speed float64) *Processor {
	p.audio.Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%f", speed)})
	return p
}

func (p *Processor) changeVideoSpeed(speed float64) *Processor {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/speed)
	p.video.Filter("setpts", ffmpeg_go.Args{reciprocalSpeed})

	p.kwArgs["vsync"] = 0
	return p
}

func (p *Processor) changeSpeed(input, output string, speed float64) error {
	err := NewProcessor(input).
		changeAudioSpeed(speed).
		changeVideoSpeed(speed).
		Output(output)
	if err != nil {
		return err
	}
	return nil
}

func (p *Processor) Output(path string) error {
	var streams []*ffmpeg_go.Stream

	if p.video != nil {
		streams = append(streams, p.video)
	}
	if p.audio != nil {
		streams = append(streams, p.audio)
	}

	cmd := ffmpeg_go.Output(streams, path, p.kwArgs).OverWriteOutput()
	return cmd.Run()
}

func (p *Processor) volume(multiplier float64) *Processor {
	p.audio.Filter("volume", ffmpeg_go.Args{fmt.Sprintf("%f", multiplier)})

	// copy flag copies over the video when only audio has changed, saving
	// computational power
	p.kwArgs["c:v"] = "copy"
	return p
}

func (p *Processor) changeAudioVolume(input, output string, multiplier float64) error {
	err := NewProcessor(input).volume(multiplier).Output(output)
	if err != nil {
		return err
	}
	return nil
}

// func (p *Processor) convertToAudio(input, output string) error {
// 	err := NewProcessor(input)
//
//
// 	// vn flag removes video output, "c:a" flag copies over the audio
// 	err := ffmpeg_go.Input(filePath).
// 		Output(outputPath, ffmpeg_go.KwArgs{"vn": "", "q:a": 2}).
// 		OverWriteOutput().
// 		GlobalArgs("-loglevel", "error", "-stats").
// 		Run()
// 	if err != nil {
// 		return fmt.Errorf("convert to audio error: %w", err)
// 	}
// 	// return nil
// 	err := NewProcessor(input).
// 		ToAudio().
// 		Output(output)
// }

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
	// var multiplier float64 = 2
	// input := "testing.mp4"
	//
	// filePathName, filePathExt := getFileNameAndExt(input)
	//
	// output := "output/" + filePathName + fmt.Sprintf("_x%.2f", multiplier) + filePathExt
	// err := changeSpeed(input, output, float64(multiplier))
	//
	//	if err != nil {
	//		fmt.Println(err)
	//	}
	//
	// fmt.Println("Video Speed changed")
	//
	// output = "output/" + filePathName + fmt.Sprintf("_Volume_x%.2f", multiplier) + filePathExt
	// err = changeAudioVolume(input, output, float64(multiplier))
	//
	//	if err != nil {
	//		fmt.Println(err)
	//	}
	//
	// fmt.Println("Video Volume changed")
	//
	// outputPath := "output/" + filePathName + ".mp3"
	// err = convertToAudio(input)
	//
	//	if err != nil {
	//		fmt.Println(err)
	//	}
	//
	// fmt.Println("Video converted to audio")
	//
	// err = flipVideo(input, "Vert")
	//
	//	if err != nil {
	//		fmt.Println(err)
	//	}
	//
	// fmt.Println("Video flipped vertically")
	//
	// err = trimVideo(input, "00:00:02", "00:00:26")
	//
	//	if err != nil {
	//		fmt.Println(err)
	//	}
	//
	// fmt.Println("Video trimmed")
	//
	// fmt.Println("Done")
}
