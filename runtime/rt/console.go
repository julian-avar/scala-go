package rt

import (
	"bufio"
	"os"
	"strconv"
	"sync"
	"unicode/utf16"
	"unicode/utf8"
)

// Standard output is buffered and flushed at exit, before writing to standard error, before
// reading standard input and around calls into Go code, which keeps println fast. Standard error
// is unbuffered. Writes are atomic: concurrent printlns do not interleave within a line.
//
// System.out and System.err (javalib's java.io.PrintStream for the console) print through
// ConsoleWrite and ConsoleWriteLine: one call per print or println, which transcodes the value
// straight into the buffer.
var (
	stdoutMu sync.Mutex
	stdout   = bufio.NewWriterSize(os.Stdout, 32*1024)
)

func writeStdoutBytes(b []byte) {
	stdoutMu.Lock()
	stdout.Write(b)
	stdoutMu.Unlock()
}

func FlushStdout() {
	stdoutMu.Lock()
	stdout.Flush()
	stdoutMu.Unlock()
}

// ConsoleWrite prints String.valueOf(x), followed by a line separator if newline is set.
func ConsoleWrite(stderr bool, x any, newline bool) {
	// Values whose toString is Scala code are converted first: it may print too.
	var s string
	direct := isDirectValue(x)
	if !direct {
		s = ToGoString(x)
	}
	if stderr {
		var buf []byte
		if direct {
			buf = appendValue(nil, x)
		} else {
			buf = []byte(s)
		}
		if newline {
			buf = append(buf, '\n')
		}
		FlushStdout()
		os.Stderr.Write(buf)
		return
	}
	stdoutMu.Lock()
	buf := stdout.AvailableBuffer()
	if direct {
		buf = appendValue(buf, x)
	} else {
		buf = append(buf, s...)
	}
	if newline {
		buf = append(buf, '\n')
	}
	stdout.Write(buf)
	stdoutMu.Unlock()
}

func isDirectValue(x any) bool {
	switch x.(type) {
	case *String, int32, int64, bool, string, nil:
		return true
	}
	return false
}

// appendValue appends String.valueOf(x) in UTF-8, for the values isDirectValue accepts.
func appendValue(buf []byte, x any) []byte {
	switch v := x.(type) {
	case *String:
		if v == nil {
			return append(buf, "null"...)
		}
		return appendUTF16(buf, v.chars)
	case int32:
		return strconv.AppendInt(buf, int64(v), 10)
	case int64:
		return strconv.AppendInt(buf, v, 10)
	case bool:
		return strconv.AppendBool(buf, v)
	case string:
		return append(buf, v...)
	}
	return append(buf, "null"...)
}

// appendUTF16 appends UTF-16 text in UTF-8. As in the JDK's console streams, unpaired
// surrogates become '?'.
func appendUTF16(buf []byte, chars []uint16) []byte {
	for i := 0; i < len(chars); i++ {
		c := chars[i]
		switch {
		case c < utf8.RuneSelf:
			buf = append(buf, byte(c))
		case utf16.IsSurrogate(rune(c)):
			if c < 0xdc00 && i+1 < len(chars) && chars[i+1] >= 0xdc00 && chars[i+1] <= 0xdfff {
				buf = utf8.AppendRune(buf, utf16.DecodeRune(rune(c), rune(chars[i+1])))
				i++
			} else {
				buf = append(buf, '?')
			}
		default:
			buf = utf8.AppendRune(buf, rune(c))
		}
	}
	return buf
}

// ---- Exit and shutdown hooks ------------------------------------------------------------------

var (
	hooksMu      sync.Mutex
	hooks        []shutdownHook
	hooksRunning bool
)

type shutdownHook struct {
	thread any
	run    func(thread any)
}

// Results of AddShutdownHook and RemoveShutdownHook.
const (
	hookDone            = 0
	hookNotDone         = 1 // already registered, or not registered
	hookShutdownStarted = 2
)

// AddShutdownHook registers a Thread (not started; given by its state, NewThreadState) to run at
// exit.
func AddShutdownHook(thread any, run func(thread any)) int32 {
	hooksMu.Lock()
	defer hooksMu.Unlock()
	if hooksRunning {
		return hookShutdownStarted
	}
	for _, h := range hooks {
		if h.thread == thread {
			return hookNotDone
		}
	}
	hooks = append(hooks, shutdownHook{thread, run})
	return hookDone
}

func RemoveShutdownHook(thread any) int32 {
	hooksMu.Lock()
	defer hooksMu.Unlock()
	if hooksRunning {
		return hookShutdownStarted
	}
	for i, h := range hooks {
		if h.thread == thread {
			hooks = append(hooks[:i], hooks[i+1:]...)
			return hookDone
		}
	}
	return hookNotDone
}

// runShutdownHooks starts the registered hooks concurrently and waits for them, once. It returns
// false if they are already running (exit was called from a hook).
func runShutdownHooks() bool {
	hooksMu.Lock()
	if hooksRunning {
		hooksMu.Unlock()
		return false
	}
	hooksRunning = true
	toRun := hooks
	hooks = nil
	hooksMu.Unlock()
	var wg sync.WaitGroup
	for _, h := range toRun {
		wg.Add(1)
		run := h.run
		StartThread(h.thread, true, func(t any) {
			defer wg.Done()
			run(t)
		})
	}
	wg.Wait()
	return true
}

// Exit runs the shutdown hooks, flushes output and terminates the program (System.exit). Called
// again from a hook, it terminates at once.
func Exit(status int32) {
	runShutdownHooks()
	Halt(status)
}

// Halt flushes output and terminates the program without running shutdown hooks
// (Runtime.halt).
func Halt(status int32) {
	FlushStdout()
	os.Exit(int(status))
}
