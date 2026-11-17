package ffmpeg

import (
	"fmt"

	ffmpeg_go "github.com/u2takey/ffmpeg-go"
)

func (p *Processor) GetMetadata() (string, error) {
	jsonOutput, err := ffmpeg_go.Probe(p.inputPath)
	if err != nil {
		return "", fmt.Errorf(
			"ffprobe metadata: %w",
			err,
		)
	}
	return jsonOutput, nil
}
