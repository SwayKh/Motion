package ffmpeg

import (
	"errors"
	"os/exec"
)

func IsProbeUnavailableError(err error) bool {
	if errors.Is(err, exec.ErrNotFound) {
		return true
	}

	var execErr *exec.Error
	return errors.As(err, &execErr)
}
