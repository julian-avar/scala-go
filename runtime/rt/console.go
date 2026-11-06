package rt

import (
	"bufio"
	"os"
)

// Standard output is buffered and flushed at exit (and before writing to standard error), which
// keeps println fast. Standard error is unbuffered.
var stdout = bufio.NewWriterSize(os.Stdout, 32*1024)

func WriteStdout(s *String) { stdout.WriteString(s.GoString()) }

func WriteStderr(s *String) {
	stdout.Flush()
	os.Stderr.WriteString(s.GoString())
}

func FlushStdout() { stdout.Flush() }

func Print(x any)   { stdout.WriteString(ToGoString(x)) }
func Println(x any) { stdout.WriteString(ToGoString(x) + "\n") }
func PrintNewline() { stdout.WriteString("\n") }

// Exit flushes output and terminates the program (System.exit).
func Exit(status int32) {
	stdout.Flush()
	os.Exit(int(status))
}
