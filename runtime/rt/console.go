package rt

import (
	"bufio"
	"os"
	"sync"
)

// Standard output is buffered and flushed at exit (and before writing to standard error), which
// keeps println fast. Standard error is unbuffered. Writes are atomic: concurrent printlns do not
// interleave within a line.
var (
	stdoutMu sync.Mutex
	stdout   = bufio.NewWriterSize(os.Stdout, 32*1024)
)

func writeStdout(s string) {
	stdoutMu.Lock()
	stdout.WriteString(s)
	stdoutMu.Unlock()
}

func WriteStdout(s *String) { writeStdout(s.GoString()) }

func WriteStderr(s *String) {
	FlushStdout()
	os.Stderr.WriteString(s.GoString())
}

func FlushStdout() {
	stdoutMu.Lock()
	stdout.Flush()
	stdoutMu.Unlock()
}

func Print(x any)   { writeStdout(ToGoString(x)) }
func Println(x any) { writeStdout(ToGoString(x) + "\n") }
func PrintNewline() { writeStdout("\n") }

// Exit flushes output and terminates the program (System.exit).
func Exit(status int32) {
	FlushStdout()
	os.Exit(int(status))
}
