//go:build !unix

package rt

import "os"

// access approximates access(2) with the file's permission bits for its owner.
func access(path string, mode uint32) bool {
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return uint32(info.Mode().Perm()>>6)&mode == mode
}
