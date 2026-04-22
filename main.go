package main

import (
	"fmt"
	"path/filepath"
	"strings"

	ffmpeg_go "github.com/u2takey/ffmpeg-go"
)

type Orientation int

const (
	Vertical   Orientation = 0
	Horizontal Orientation = 1
)

type Processor struct {
	inputPath string

	input *ffmpeg_go.Stream
	video *ffmpeg_go.Stream
	audio *ffmpeg_go.Stream

	inputArgs ffmpeg_go.KwArgs
	kwArgs    ffmpeg_go.KwArgs
}

func (p *Processor) rebuildInput() {
	in := ffmpeg_go.Input(p.inputPath, p.inputArgs)
	p.input = in
	p.video = in.Video()
	p.audio = in.Audio()
}

func NewProcessor(path string) *Processor {
	p := &Processor{
		inputPath: path,
		inputArgs: ffmpeg_go.KwArgs{},
		kwArgs:    ffmpeg_go.KwArgs{},
	}
	p.rebuildInput()
	return p
}

func getFileNameAndExt(filePath string) (baseName, extension string) {
	filePathBase := filepath.Base(filePath)
	extension = filepath.Ext(filePath)
	baseName = strings.TrimSuffix(filePathBase, extension)

	return baseName, extension
}

func (p *Processor) changeAudioSpeed(speed float64) *Processor {
	p.audio = p.audio.Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%f", speed)})
	return p
}

func (p *Processor) changeVideoSpeed(speed float64) *Processor {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/speed)
	p.video = p.video.Filter("setpts", ffmpeg_go.Args{reciprocalSpeed})

	p.kwArgs["vsync"] = 0
	return p
}

func (p *Processor) changeAudioVolume(multiplier float64) *Processor {
	p.audio = p.audio.Filter("volume", ffmpeg_go.Args{fmt.Sprintf("%f", multiplier)})

	// copy flag copies over the video when only audio has changed, saving
	// computational power
	p.kwArgs["c:v"] = "copy"
	return p
}

func (p *Processor) convertToAudio() *Processor {
	p.video = nil
	p.kwArgs["vn"] = ""
	p.kwArgs["q:a"] = 2
	return p
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

func (p *Processor) changeOrientation(orient Orientation) *Processor {
	switch orient {
	case Vertical:
		p.video = p.video.VFlip()
	case Horizontal:
		p.video = p.video.HFlip()
	default:
		return p
	}
	p.kwArgs["c:a"] = "copy"
	p.kwArgs["c:v"] = "libx264"
	return p
}

// Trim should be called before other transformations
func (p *Processor) trimVideo(start, end string) *Processor {
	p.inputArgs["ss"] = start
	p.kwArgs["to"] = end
	p.kwArgs["c:a"] = "copy"
	p.kwArgs["c:v"] = "libx264"

	p.rebuildInput() // important

	return p
}

func main() {
	var multiplier float64 = 2
	filePath := "testing.mp4"

	filePathName, filePathExt := getFileNameAndExt(filePath)

	output := "output/" + filePathName + fmt.Sprintf("_x%.2f", multiplier) + filePathExt
	err := NewProcessor(filePath).changeAudioSpeed(multiplier).changeVideoSpeed(multiplier).Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video Speed changed")

	output = "output/" + filePathName + fmt.Sprintf("_Volume_x%.2f", multiplier) + filePathExt
	err = NewProcessor(filePath).changeAudioVolume(multiplier).Output(output)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video Volume changed")

	output = "output/" + filePathName + ".mp3"
	err = NewProcessor(filePath).convertToAudio().Output(output)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video converted to audio")

	output = "output/" + filePathName + "_Flipped" + filePathExt
	err = NewProcessor(filePath).changeOrientation(Vertical).Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video flipped vertically")

	output = "output/" + filePathName + "_Trimmed_" + filePathExt
	err = NewProcessor(filePath).trimVideo("00:00:02", "00:00:26").Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video trimmed")

	fmt.Println("Done")
}
