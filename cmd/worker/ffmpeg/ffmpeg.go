package ffmpeg

import (
	"fmt"

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
	needsRebuild  bool

	disableVideo bool
	audioQuality *int
	vsync        *int
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

func (p *Processor) GetMetadata() (string, error) {
	jsonOutput, err := ffmpeg_go.Probe(p.inputPath)
	if err != nil {
		return "", err
	}
	return jsonOutput, nil
}

func (p *Processor) rebuildInput() {
	in := ffmpeg_go.Input(p.inputPath, p.inputArgs)
	p.input = in
	p.video = in.Video()
	p.audio = in.Audio()
}

// Rebuilds input graph if input arguments were modified (e.g., Trim)
func (p *Processor) ensureInputReady() {
	if p.needsRebuild {
		p.rebuildInput()
		p.needsRebuild = false
	}
}

func (p *Processor) audioSpeed(speed float64) *Processor {
	if p.audio != nil {
		p.audio = p.audio.Filter("atempo", ffmpeg_go.Args{fmt.Sprintf("%f", speed)})
		p.audioModified = true
	}
	return p
}

func (p *Processor) videoSpeed(speed float64) *Processor {
	reciprocalSpeed := fmt.Sprintf("%.2f*PTS", 1.0/speed)

	if p.video != nil {
		p.video = p.video.Filter("setpts", ffmpeg_go.Args{reciprocalSpeed})
		p.videoModified = true
	}
	return p
}

func (p *Processor) Speed(multiplier float64) *Processor {
	p.ensureInputReady()
	if multiplier <= 0 {
		return p
	}
	p.audioSpeed(multiplier)
	p.videoSpeed(multiplier)
	// optional default
	if p.vsync == nil {
		v := 0
		p.vsync = &v
	}
	return p
}

func (p *Processor) Volume(multiplier float64) *Processor {
	p.ensureInputReady()
	if p.audio != nil {
		p.audio = p.audio.Filter("volume", ffmpeg_go.Args{fmt.Sprintf("%f", multiplier)})
		p.audioModified = true
	}

	return p
}

func (p *Processor) ToAudio() *Processor {
	p.ensureInputReady()
	p.video = nil
	p.disableVideo = true
	q := 2
	p.audioQuality = &q
	p.kwArgs["c:a"] = "libmp3lame" // force encode
	return p
}

func (p *Processor) VideoCodec(codec string) *Processor {
	p.kwArgs["c:v"] = codec
	return p
}

func (p *Processor) VSync(mode int) *Processor {
	p.vsync = &mode // ✅ perfectly fine
	return p
}

func (p *Processor) Flip(orient Orientation) *Processor {
	p.ensureInputReady()
	switch orient {
	case Vertical:
		if p.video != nil {
			p.video = p.video.VFlip()
			p.videoModified = true
		}
	case Horizontal:
		if p.video != nil {
			p.video = p.video.HFlip()
			p.videoModified = true
		}
	default:
		return p
	}
	return p
}

// Trim resets the input graph; transformations before it will be discarded
func (p *Processor) Trim(start, end string) *Processor {
	p.inputArgs["ss"] = start
	p.kwArgs["to"] = end
	p.needsRebuild = true

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
	// This check is the prevent videoCodec from being overwritten
	if _, ok := p.kwArgs["c:v"]; !ok {
		if !p.videoModified && p.video != nil {
			p.kwArgs["c:v"] = "copy"
		}
	}
	if _, ok := p.kwArgs["c:a"]; !ok {
		if !p.audioModified && p.audio != nil {
			p.kwArgs["c:a"] = "copy"
		}
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
