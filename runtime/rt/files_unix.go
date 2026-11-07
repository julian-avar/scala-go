//go:build unix

package rt

import "syscall"

// access checks permissions as the JDK's File.canRead/canWrite/canExecute do, with access(2).
func access(path string, mode uint32) bool { return syscall.Access(path, mode) == nil }
