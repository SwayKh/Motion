package media

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
)

type VideoMetadata struct {
	Duration float64

	Height int
	Width  int

	VideoCodec string
	AudioCodec string

	FrameRate float64

	HasAudio bool
	FileSize int64
	Format   string
}

type MetaData struct {
	Streams []probeStream `json:"streams"`
	Format  probeFormat   `json:"format"`
}

type probeStream struct {
	CodecType string `json:"codec_type"`
	CodecName string `json:"codec_name"`

	Width  int `json:"width"`
	Height int `json:"height"`

	RFrameRate string `json:"r_frame_rate"`
}
type probeFormat struct {
	Duration   string `json:"duration"`
	FormatName string `json:"format_name"`
	Size       string `json:"size"`
}

func ExtractMetaData(out string) (*VideoMetadata, error) {
	var probe MetaData

	err := json.Unmarshal([]byte(out), &probe)
	if err != nil {
		return nil, err
	}

	meta := &VideoMetadata{}

	// Format Data
	meta.Format = probe.Format.FormatName

	if probe.Format.Duration != "" {
		duration, err := strconv.ParseFloat(probe.Format.Duration, 64)
		if err == nil {
			meta.Duration = duration
		}
	}
	if probe.Format.Size != "" {
		size, err := strconv.ParseInt(probe.Format.Size, 10, 64)
		if err == nil {
			meta.FileSize = size
		}
	}

	for _, stream := range probe.Streams {
		switch stream.CodecType {

		case "video":
			meta.Width = stream.Width
			meta.Height = stream.Height
			meta.VideoCodec = stream.CodecName
			fps, err := parseFrameRate(
				stream.RFrameRate,
			)
			if err == nil {
				meta.FrameRate = fps
			}
		case "audio":
			meta.HasAudio = true
			meta.AudioCodec = stream.CodecName
		}
	}

	if meta.Width == 0 && !meta.HasAudio {
		return nil, errors.New("no video or audio stream found")
	}
	return meta, nil
}

func parseFrameRate(rate string) (float64, error) {
	// ffprobe usually returns:
	// "30/1"
	// "24000/1001"

	var num, den float64
	_, err := fmt.Sscanf(rate, "%f/%f", &num, &den)
	if err != nil {
		return 0, err
	}

	if den == 0 {
		return 0, errors.New("invalid frame rate")
	}

	return num / den, nil
}
