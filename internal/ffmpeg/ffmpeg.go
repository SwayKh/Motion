package ffmpeg

import (
	"context"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/SwayKh/Motion/internal/media"

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
	hasAudio     bool
	audioQuality *int
	fpsMode      *string

	progress func(float64)
}

func NewProcessor(path string) *Processor {
	return NewProcessorWithAudio(path, true)
}

func NewProcessorWithAudio(path string, hasAudio bool) *Processor {
	p := &Processor{
		inputPath:    path,
		inputArgs:    ffmpeg_go.KwArgs{},
		kwArgs:       ffmpeg_go.KwArgs{},
		disableVideo: false,
		hasAudio:     hasAudio,
	}
	p.rebuildInput()
	return p
}

func (p *Processor) rebuildInput() {
	in := ffmpeg_go.Input(p.inputPath, p.inputArgs)
	p.input = in
	p.video = in.Video()
	if p.hasAudio {
		p.audio = in.Audio()
	} else {
		p.audio = nil
	}
}

func (o Orientation) Valid() bool {
	switch o {
	case Vertical, Horizontal:
		return true
	default:
		return false
	}
}

func (p *Processor) OnProgress(fn func(float64)) *Processor {
	p.progress = fn
	return p
}

func (p *Processor) audioSpeed(speed float64) *Processor {
	if p.audio != nil {
		p.audio = p.audio.Filter("atempo", ffmpeg_go.Args{strconv.FormatFloat(speed, 'g', -1, 64)})
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
	if multiplier <= 0 {
		return p
	}
	p.audioSpeed(multiplier)
	p.videoSpeed(multiplier)
	// Keep the source frame timestamps instead of letting ffmpeg drop or
	// duplicate frames while the presentation timestamps are rewritten.
	if p.fpsMode == nil {
		mode := "passthrough"
		p.fpsMode = &mode
	}
	return p
}

func (p *Processor) Volume(multiplier float64) *Processor {
	if p.audio != nil {
		p.audio = p.audio.Filter("volume", ffmpeg_go.Args{strconv.FormatFloat(multiplier, 'g', -1, 64)})
		p.audioModified = true
	}

	return p
}

func (p *Processor) ToAudio() *Processor {
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
	var value string
	switch mode {
	case 0:
		value = "passthrough"
	case 1:
		value = "cfr"
	case 2:
		value = "vfr"
	default:
		return p
	}
	p.fpsMode = &value
	return p
}

func (p *Processor) Flip(orient Orientation) *Processor {
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
// Should be fixed now
func (p *Processor) Trim(start, end string) *Processor {
	if p.video != nil {
		p.video = p.video.
			Filter("trim", ffmpeg_go.Args{fmt.Sprintf("start=%s:end=%s", escapeFilterValue(start), escapeFilterValue(end))}).
			Filter("setpts", ffmpeg_go.Args{"PTS-STARTPTS"})
		p.videoModified = true
	}
	if p.audio != nil {
		p.audio = p.audio.
			Filter("atrim", ffmpeg_go.Args{fmt.Sprintf("start=%s:end=%s", escapeFilterValue(start), escapeFilterValue(end))}).
			Filter("asetpts", ffmpeg_go.Args{"PTS-STARTPTS"})
		p.audioModified = true
	}
	return p
}

// escapeFilterValue escapes characters that the filtergraph parser would
// otherwise treat as option separators. The value is parsed twice, so the
// backslash itself must also be escaped.
func escapeFilterValue(value string) string {
	return strings.ReplaceAll(value, ":", `\:`)
}

func (p *Processor) Output(path string) error {
	return p.OutputContext(context.Background(), path)
}

func (p *Processor) OutputContext(ctx context.Context, path string) error {
	var streams []*ffmpeg_go.Stream

	if p.disableVideo {
		p.kwArgs["vn"] = ""
	}

	if p.audioQuality != nil {
		p.kwArgs["q:a"] = *p.audioQuality
	}

	if p.fpsMode != nil {
		p.kwArgs["fps_mode"] = *p.fpsMode
	}

	// Copy optimization
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

	if len(streams) == 0 {
		return fmt.Errorf("no output streams available; the source is missing the required audio or video stream")
	}

	// Enable ffmpeg progress output
	p.kwArgs["progress"] = "pipe:1"
	p.kwArgs["nostats"] = ""

	// Read the duration before starting ffmpeg. If probing fails we simply
	// skip progress updates; we must not return after cmd.Start without
	// waiting on the child process.
	var totalDuration float64
	if p.progress != nil {
		raw, err := p.GetMetadata()
		if err == nil {
			meta, err := media.ExtractMetaData(raw)
			if err == nil {
				totalDuration = meta.Duration
			}
		}
	}

	// The Compile, Start, Wait pipeline is needed to write the progress of
	// command to the struct, so that it can be polled for progress.
	cmd := ffmpeg_go.
		OutputContext(ctx, streams, path, p.kwArgs).
		OverWriteOutput().
		Compile()

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("ffmpeg build command: %w", err)
	}

	// Start process
	err = cmd.Start()
	if err != nil {
		return fmt.Errorf("ffmpeg run command: %w", err)
	}

	// Start progress parser. If progress is unavailable, still drain the
	// progress pipe so a long-running ffmpeg process cannot block on it.
	if p.progress != nil && totalDuration > 0 {
		go parseProgress(
			stdout,
			totalDuration,
			p.progress,
		)
	} else {
		go io.Copy(io.Discard, stdout)
	}

	return cmd.Wait()
}
