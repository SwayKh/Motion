//go:build !unix

package quota

import "errors"

// diskFree is unavailable on non-unix platforms; the caller treats the error as
// "unknown" and allows the operation.
func diskFree(path string) (int64, error) {
	return 0, errors.New("disk space check is not supported on this platform")
}
