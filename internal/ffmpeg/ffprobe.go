package ffmpeg

import (
	ffmpeg_go "github.com/u2takey/ffmpeg-go"
)

func (p *Processor) GetMetadata() (string, error) {
	jsonOutput, err := ffmpeg_go.Probe(p.inputPath)
	if err != nil {
		return "", err
	}
	return jsonOutput, nil
}
