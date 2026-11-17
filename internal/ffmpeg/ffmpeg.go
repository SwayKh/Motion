package ffmpeg

import (
	"context"
	"fmt"
	"io"
	"math"
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

// Slice keeps only the [start, end] portion of the source. It is the segment
// extraction counterpart of Trim and is kept as a distinct method so callers
// can express intent without relying on Trim's historical name.
func (p *Processor) Slice(start, end string) *Processor {
	return p.Trim(start, end)
}

// RemoveSection deletes the [start, end] portion from the middle of the source
// and joins the remaining head and tail into a single continuous stream.
//
// The select/aselect filters keep every frame outside the removed window and
// the setpts/asetpts filters rebuild a gapless timeline, so the result plays as
// one clip without needing an intermediate concat pass.
func (p *Processor) RemoveSection(start, end string) *Processor {
	startSec, startErr := ParseTimecode(start)
	endSec, endErr := ParseTimecode(end)
	if startErr != nil || endErr != nil {
		// Invalid timestamps are rejected during validation; leave the graph
		// untouched rather than emitting a malformed filter.
		return p
	}

	expr := fmt.Sprintf("not(between(t,%s,%s))", formatSeconds(startSec), formatSeconds(endSec))
	if p.video != nil {
		p.video = p.video.
			Filter("select", ffmpeg_go.Args{"expr=" + expr}).
			Filter("setpts", ffmpeg_go.Args{"N/FRAME_RATE/TB"})
		p.videoModified = true
	}
	if p.audio != nil {
		p.audio = p.audio.
			Filter("aselect", ffmpeg_go.Args{"expr=" + expr}).
			Filter("asetpts", ffmpeg_go.Args{"N/SR/TB"})
		p.audioModified = true
	}
	return p
}

// AudioInput describes an additional audio source mixed into the pipeline.
type AudioInput struct {
	Path string

	Volume float64

	OffsetSeconds float64
	TrimStart     float64
	TrimEnd       float64

	// Mix controls whether the added audio is blended with the existing track
	// (true) or replaces it (false).
	Mix bool
}

// AddAudio attaches a secondary audio input. When Mix is true and the source
// already has audio the two tracks are blended with amix; otherwise the added
// track becomes the output audio.
func (p *Processor) AddAudio(input AudioInput) *Processor {
	if input.Path == "" {
		return p
	}

	stream := ffmpeg_go.Input(input.Path).Audio()

	if input.TrimStart > 0 || input.TrimEnd > 0 {
		end := ""
		if input.TrimEnd > 0 {
			end = formatSeconds(input.TrimEnd)
		}
		stream = stream.
			Filter("atrim", ffmpeg_go.Args{fmt.Sprintf("start=%s:end=%s", formatSeconds(input.TrimStart), end)}).
			Filter("asetpts", ffmpeg_go.Args{"PTS-STARTPTS"})
	}

	if input.Volume > 0 && input.Volume != 1 {
		stream = stream.Filter("volume", ffmpeg_go.Args{formatSeconds(input.Volume)})
	}

	if input.OffsetSeconds > 0 {
		ms := fmt.Sprintf("%d", int(input.OffsetSeconds*1000))
		stream = stream.Filter("adelay", ffmpeg_go.Args{fmt.Sprintf("delays=%s:%s", ms, ms)})
	}

	if input.Mix && p.audio != nil {
		p.audio = ffmpeg_go.Filter(
			[]*ffmpeg_go.Stream{p.audio, stream},
			"amix",
			ffmpeg_go.Args{"inputs=2", "duration=longest", "dropout_transition=0", "normalize=0"},
		)
	} else {
		p.audio = stream
	}

	p.audioModified = true
	p.hasAudio = true
	return p
}

func formatSeconds(value float64) string {
	return strconv.FormatFloat(value, 'f', -1, 64)
}

// ParseTimecode accepts plain seconds ("12", "12.5"), MM:SS, or HH:MM:SS
// timestamps and returns the value in seconds. It is shared by validation and
// the filter builder so both interpret timestamps identically.
func ParseTimecode(s string) (float64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, fmt.Errorf("timestamp is empty")
	}
	if !strings.Contains(s, ":") {
		value, err := strconv.ParseFloat(s, 64)
		if err != nil {
			return 0, fmt.Errorf("invalid timestamp")
		}
		if math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
			return 0, fmt.Errorf("timestamp must be a non-negative number")
		}
		return value, nil
	}

	parts := strings.Split(s, ":")
	if len(parts) < 2 || len(parts) > 3 {
		return 0, fmt.Errorf("invalid timestamp")
	}

	var total float64
	for i, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			return 0, fmt.Errorf("invalid timestamp")
		}
		value, err := strconv.ParseFloat(part, 64)
		if err != nil {
			return 0, fmt.Errorf("invalid timestamp")
		}
		if math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
			return 0, fmt.Errorf("invalid timestamp")
		}
		if i > 0 && value >= 60 {
			return 0, fmt.Errorf("invalid timestamp")
		}
		total = total*60 + value
	}
	return total, nil
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
