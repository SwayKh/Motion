//go:build unix

package quota

import "syscall"

// diskFree returns the number of free bytes available on the filesystem that
// holds path.
func diskFree(path string) (int64, error) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(path, &stat); err != nil {
		return 0, err
	}
	return int64(stat.Bavail) * int64(stat.Bsize), nil
}
