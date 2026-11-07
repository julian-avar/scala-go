//go:build unix

package rt

import (
	"os"
	"syscall"
)

// exitCode is the process's exit status, or 128 plus the number of the signal that ended it.
func exitCode(state *os.ProcessState) int32 {
	if status, ok := state.Sys().(syscall.WaitStatus); ok && status.Signaled() {
		return 128 + int32(status.Signal())
	}
	return int32(state.ExitCode())
}

func terminate(p *os.Process, force bool) {
	if force {
		p.Signal(syscall.SIGKILL)
	} else {
		p.Signal(syscall.SIGTERM)
	}
}
