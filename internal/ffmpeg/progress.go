package ffmpeg

import (
	"bufio"
	"io"
	"strconv"
	"strings"
)

func parseProgress(
	reader io.Reader,
	totalDuration float64,
	callback func(float64),
) {
	scanner := bufio.NewScanner(reader)

	for scanner.Scan() {
		line := scanner.Text()

		if !strings.HasPrefix(line, "out_time_ms=") {
			continue
		}

		value := strings.TrimPrefix(line, "out_time_ms=")

		microseconds, err := strconv.ParseFloat(value, 64)
		if err != nil {
			continue
		}

		seconds := microseconds / 1_000_000

		progress := (seconds / totalDuration) * 100

		if progress > 100 {
			progress = 100
		}

		callback(progress)
	}
}
