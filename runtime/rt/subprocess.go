package rt

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"syscall"
	"time"
)

// The native side of java.lang.ProcessBuilder and Process
// (javalib/src/scalago/runtime/Subprocesses.scala), on os/exec. A started process is a
// *subprocess held by Scala as an Object; the parent's ends of its pipes are *os.File handles
// of the file layer (files.go), which java.io's streams read and write.

// Redirect kinds (Subprocesses.scala).
const (
	redirectPipe = iota
	redirectInherit
	redirectRead
	redirectWrite
	redirectAppend
)

type subprocess struct {
	cmd                   *exec.Cmd
	stdin, stdout, stderr *os.File // the parent's ends of the pipes, or nil
	done                  chan struct{}
	exitCode              int32 // valid once done is closed
}

// startError is a process that could not be started. Its message is the JDK's: "<path>
// (<reason>)" if a redirect's file could not be opened, otherwise "Exec failed, error: <errno>
// (<reason>) ".
type startError struct{ message string }

func (e *startError) Error() string { return e.message }

// ProcessStart starts command (a String array) in dir (inherited if nil), with env (alternating
// names and values; inherited if nil). redirects holds the kinds of standard input, output and
// error, and paths their files. With mergeErrors, standard error goes where standard output
// goes. It returns the process, or a *startError.
func ProcessStart(command *Array[any], dir *String, env *Array[any], redirects *Array[int32], paths *Array[any],
	mergeErrors bool) any {
	args := make([]string, len(command.Data))
	for i, a := range command.Data {
		args[i] = a.(*String).GoString()
	}
	cmd := exec.Command(args[0], args[1:]...)
	if dir != nil {
		cmd.Dir = dir.GoString()
	}
	if env != nil {
		cmd.Env = make([]string, 0, len(env.Data)/2)
		for i := 0; i+1 < len(env.Data); i += 2 {
			cmd.Env = append(cmd.Env, env.Data[i].(*String).GoString()+"="+env.Data[i+1].(*String).GoString())
		}
	}
	p := &subprocess{cmd: cmd, done: make(chan struct{})}
	var childEnds []*os.File // closed once the child has them (or on failure)
	closeChildEnds := func() {
		for _, f := range childEnds {
			f.Close()
		}
	}
	fail := func(err error) any {
		closeChildEnds()
		for _, f := range []*os.File{p.stdin, p.stdout, p.stderr} {
			if f != nil {
				f.Close()
			}
		}
		return err
	}
	for i := 0; i < 3; i++ {
		var child *os.File
		if i == 2 && mergeErrors {
			cmd.Stderr = cmd.Stdout
			break
		}
		switch redirects.Data[i] {
		case redirectPipe:
			r, w, err := os.Pipe()
			if err != nil {
				return fail(&startError{errnoMessage(err)})
			}
			if i == 0 {
				p.stdin, child = w, r
			} else if i == 1 {
				p.stdout, child = r, w
			} else {
				p.stderr, child = r, w
			}
			childEnds = append(childEnds, child)
		case redirectInherit:
			child = []*os.File{os.Stdin, os.Stdout, os.Stderr}[i]
		default:
			path := paths.Data[i].(*String).GoString()
			flags := os.O_RDONLY
			if redirects.Data[i] == redirectWrite {
				flags = os.O_WRONLY | os.O_CREATE | os.O_TRUNC
			} else if redirects.Data[i] == redirectAppend {
				flags = os.O_WRONLY | os.O_CREATE | os.O_APPEND
			}
			f, err := os.OpenFile(path, flags, 0o666)
			if err != nil {
				return fail(&startError{path + " (" + errorReason(err) + ")"})
			}
			child = f
			childEnds = append(childEnds, f)
		}
		switch i {
		case 0:
			cmd.Stdin = child
		case 1:
			cmd.Stdout = child
		default:
			cmd.Stderr = child
		}
	}
	// What the program printed so far comes before what the child prints on an inherited stdout.
	FlushStdout()
	if err := cmd.Start(); err != nil {
		return fail(&startError{errnoMessage(err)})
	}
	closeChildEnds()
	go func() {
		cmd.Wait()
		p.exitCode = exitCode(cmd.ProcessState)
		close(p.done)
	}()
	return p
}

// errnoMessage describes a failure to start a program as the JDK (25) does: "Exec failed, error:
// 2 (No such file or directory) ".
func errnoMessage(err error) string {
	var errno syscall.Errno
	switch {
	case errors.As(err, &errno):
	case errors.Is(err, exec.ErrNotFound), errors.Is(err, fs.ErrNotExist):
		errno = syscall.ENOENT
	case errors.Is(err, fs.ErrPermission):
		errno = syscall.EACCES
	default:
		return "Exec failed, error: 0 (" + err.Error() + ") "
	}
	return fmt.Sprintf("Exec failed, error: %d (%s) ", int(errno), errorReason(errno))
}

func ProcessErrorMessage(e any) *String { return Str(e.(*startError).message) }

func processOf(p any) *subprocess { return p.(*subprocess) }

func fileOrNil(f *os.File) any {
	if f == nil {
		return nil
	}
	return f
}

func ProcessStdin(p any) any  { return fileOrNil(processOf(p).stdin) }
func ProcessStdout(p any) any { return fileOrNil(processOf(p).stdout) }
func ProcessStderr(p any) any { return fileOrNil(processOf(p).stderr) }

func ProcessPid(p any) int64 { return int64(processOf(p).cmd.Process.Pid) }

// ProcessWait waits until the process exits, at most timeoutNanos if it is not negative; it
// reports whether the process has exited. The wait is interruptible (Thread.interrupt).
func ProcessWait(p any, timeoutNanos int64) bool {
	s := processOf(p)
	select {
	case <-s.done:
		return true
	default:
	}
	if timeoutNanos == 0 {
		return false
	}
	t := currentThreadState(GoID())
	if t != nil && t.interrupted.Swap(false) {
		ThrowKind(Interrupted, "")
	}
	timeout := time.Duration(0)
	if timeoutNanos > 0 {
		timeout = time.Duration(timeoutNanos)
	}
	if t.block(s.done, timeout) {
		ThrowKind(Interrupted, "")
	}
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}

// ProcessExitCode is the exit status of a process that has exited: 128 plus the signal's number
// if a signal ended it, as on the JDK.
func ProcessExitCode(p any) int32 { return processOf(p).exitCode }

// ProcessDestroy asks the process to end (SIGTERM), or kills it (SIGKILL) if force is set.
func ProcessDestroy(p any, force bool) {
	s := processOf(p)
	select {
	case <-s.done:
		return
	default:
	}
	terminate(s.cmd.Process, force)
}

func CurrentPid() int64 { return int64(os.Getpid()) }
func ParentPid() int64  { return int64(os.Getppid()) }
