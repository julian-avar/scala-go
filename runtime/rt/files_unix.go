//go:build unix

package rt

import (
	"errors"
	"math"
	"os"
	"syscall"
)

// access checks permissions as the JDK's File.canRead/canWrite/canExecute do, with access(2).
func access(path string, mode uint32) bool { return syscall.Access(path, mode) == nil }

// lockFile takes a POSIX record lock (fcntl), as the JDK's FileChannel.lock. A size of
// Long.MAX_VALUE means up to the end of the file and beyond (a length of 0 for fcntl).
func lockFile(f *os.File, position, size int64, shared, wait bool) (bool, error) {
	lock := flockOf(position, size, syscall.F_WRLCK)
	if shared {
		lock.Type = syscall.F_RDLCK
	}
	cmd := syscall.F_SETLK
	if wait {
		cmd = syscall.F_SETLKW
	}
	for {
		err := syscall.FcntlFlock(f.Fd(), cmd, lock)
		switch {
		case err == nil:
			return true, nil
		case errors.Is(err, syscall.EINTR):
			continue
		case !wait && (errors.Is(err, syscall.EAGAIN) || errors.Is(err, syscall.EACCES)):
			return false, nil
		}
		return false, err
	}
}

func unlockFile(f *os.File, position, size int64) error {
	return syscall.FcntlFlock(f.Fd(), syscall.F_SETLK, flockOf(position, size, syscall.F_UNLCK))
}

func flockOf(position, size int64, kind int16) *syscall.Flock_t {
	if size == math.MaxInt64 {
		size = 0
	}
	return &syscall.Flock_t{Type: kind, Whence: 0, Start: position, Len: size}
}
