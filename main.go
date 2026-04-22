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

	audioModified bool
	videoModified bool

	disableVideo bool
	audioQuality *int
	vsync        *int
}

func (p *Processor) rebuildInput() {
	in := ffmpeg_go.Input(p.inputPath, p.inputArgs)
	p.input = in
	p.video = in.Video()
	p.audio = in.Audio()
}

func NewProcessor(path string) *Processor {
	p := &Processor{
		inputPath:     path,
		inputArgs:     ffmpeg_go.KwArgs{},
		kwArgs:        ffmpeg_go.KwArgs{},
		audioModified: false,
		videoModified: false,
		disableVideo:  false,
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

func (p *Processor) audioSpeed(speed float64) *Processor {
	p.audio = p.audio.Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%f", speed)})
	p.audioModified = true
	return p
}

func (p *Processor) videoSpeed(speed float64) *Processor {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/speed)

	if p.video != nil {
		p.video = p.video.Filter("setpts", ffmpeg_go.Args{reciprocalSpeed})
	}
	p.videoModified = true

	vSyncVal := 0
	p.vsync = &vSyncVal
	return p
}

func (p *Processor) Speed(multiplier float64) *Processor {
	if multiplier <= 0 {
		return p
	}
	p.audioSpeed(multiplier)
	p.videoSpeed(multiplier)
	return p
}

func (p *Processor) Volume(multiplier float64) *Processor {
	p.audio = p.audio.Filter("volume", ffmpeg_go.Args{fmt.Sprintf("%f", multiplier)})
	p.audioModified = true

	return p
}

func (p *Processor) ToAudio() *Processor {
	p.video = nil
	p.disableVideo = true
	q := 2
	p.audioQuality = &q
	return p
}

func (p *Processor) VideoCodec(codec string) *Processor {
	p.kwArgs["c:v"] = codec
	return p
}

func (p *Processor) Flip(orient Orientation) *Processor {
	switch orient {
	case Vertical:
		p.video = p.video.VFlip()
	case Horizontal:
		p.video = p.video.HFlip()
	default:
		return p
	}
	p.videoModified = true
	return p
}

// Trim should be called before other transformations
func (p *Processor) Trim(start, end string) *Processor {
	p.inputArgs["ss"] = start
	p.kwArgs["to"] = end

	p.rebuildInput() // important

	return p
}

func (p *Processor) Output(path string) error {
	var streams []*ffmpeg_go.Stream

	if p.disableVideo {
		p.kwArgs["vn"] = ""
	}
	if p.audioQuality != nil {
		p.kwArgs["q:a"] = *p.audioQuality
	}
	if p.vsync != nil {
		p.kwArgs["vsync"] = *p.vsync
	}
	// copy flag copies over the video when only audio has changed, saving
	// computational power
	if !p.videoModified && p.video != nil {
		p.kwArgs["c:v"] = "copy"
	}
	if !p.audioModified && p.audio != nil {
		p.kwArgs["c:a"] = "copy"
	}
	if p.video != nil {
		streams = append(streams, p.video)
	}
	if p.audio != nil {
		streams = append(streams, p.audio)
	}

	cmd := ffmpeg_go.Output(streams, path, p.kwArgs).OverWriteOutput()
	return cmd.Run()
}

func main() {
	var multiplier float64 = 2
	filePath := "testing.mp4"

	filePathName, filePathExt := getFileNameAndExt(filePath)

	output := "output/" + filePathName + fmt.Sprintf("_x%.2f", multiplier) + filePathExt
	err := NewProcessor(filePath).Speed(multiplier).Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video Speed changed")

	output = "output/" + filePathName + fmt.Sprintf("_Volume_x%.2f", multiplier) + filePathExt
	err = NewProcessor(filePath).Volume(multiplier).Output(output)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video Volume changed")

	output = "output/" + filePathName + ".mp3"
	err = NewProcessor(filePath).ToAudio().Output(output)
	if err != nil {
		fmt.Println(err)
	}
	fmt.Println("Video converted to audio")

	output = "output/" + filePathName + "_Flipped" + filePathExt
	err = NewProcessor(filePath).Flip(Vertical).VideoCodec("libx264").Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video flipped vertically")

	output = "output/" + filePathName + "_Trimmed_" + filePathExt
	err = NewProcessor(filePath).Trim("00:00:02", "00:00:26").VideoCodec("libx264").Output(output)
	if err != nil {
		fmt.Println(err)
	}

	fmt.Println("Video trimmed")

	fmt.Println("Done")
}
