//go:build unix

package rt

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func strings2Array(ss ...string) *Array[any] {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = Str(s)
	}
	return &Array[any]{Data: out}
}

// readAll reads a pipe handle to its end.
func readAll(t *testing.T, handle any) string {
	var sb strings.Builder
	buf := NewArray[int8](64)
	for {
		n := OSRead(handle, buf, 0, 64)
		if n < 0 {
			return sb.String()
		}
		for _, b := range buf.Data[:n] {
			sb.WriteByte(byte(b))
		}
	}
}

func pipes() *Array[int32] { return ArrayOf[int32](redirectPipe, redirectPipe, redirectPipe) }

func TestProcessPipesAndExitCode(t *testing.T) {
	p := ProcessStart(strings2Array("sh", "-c", "cat; echo err >&2; exit 3"), nil, nil, pipes(), strings2Array("", "", ""), false)
	if _, ok := p.(*subprocess); !ok {
		t.Fatalf("start failed: %v", p)
	}
	in := []byte("hello")
	OSWrite(ProcessStdin(p), &Array[int8]{Data: Reinterpret[byte, int8](in)}, 0, int32(len(in)))
	OSClose(ProcessStdin(p))
	if out := readAll(t, ProcessStdout(p)); out != "hello" {
		t.Errorf("stdout = %q", out)
	}
	if err := readAll(t, ProcessStderr(p)); err != "err\n" {
		t.Errorf("stderr = %q", err)
	}
	if !ProcessWait(p, -1) || ProcessExitCode(p) != 3 {
		t.Errorf("exit code = %d", ProcessExitCode(p))
	}
}

func TestProcessEnvironmentDirectoryAndMergedErrors(t *testing.T) {
	dir := t.TempDir()
	p := ProcessStart(strings2Array("sh", "-c", "pwd; echo $X >&2"), Str(dir), strings2Array("X", "y"), pipes(),
		strings2Array("", "", ""), true)
	got := readAll(t, ProcessStdout(p))
	real, _ := filepath.EvalSymlinks(dir)
	if got != real+"\ny\n" && got != dir+"\ny\n" {
		t.Errorf("output = %q", got)
	}
	if ProcessStderr(p) != nil {
		t.Error("merged standard error has a pipe")
	}
	ProcessWait(p, -1)
}

func TestProcessSignalAndTimeout(t *testing.T) {
	p := ProcessStart(strings2Array("sh", "-c", "read x"), nil, nil, pipes(), strings2Array("", "", ""), false)
	if ProcessWait(p, 10_000_000) {
		t.Fatal("exited before its input closed")
	}
	ProcessDestroy(p, false)
	if !ProcessWait(p, -1) || ProcessExitCode(p) != 143 {
		t.Errorf("exit code after SIGTERM = %d, want 143", ProcessExitCode(p))
	}
}

func TestProcessStartFailures(t *testing.T) {
	r := ProcessStart(strings2Array("scalago-no-such-program"), nil, nil, pipes(), strings2Array("", "", ""), false)
	if msg := ProcessErrorMessage(r).GoString(); msg != "Exec failed, error: 2 (No such file or directory) " {
		t.Errorf("message = %q", msg)
	}
	missing := filepath.Join(t.TempDir(), "missing")
	r = ProcessStart(strings2Array("cat"), nil, nil, ArrayOf[int32](redirectRead, redirectPipe, redirectPipe),
		strings2Array(missing, "", ""), false)
	if msg := ProcessErrorMessage(r).GoString(); msg != missing+" (No such file or directory)" {
		t.Errorf("message = %q", msg)
	}
}

func TestFileLocks(t *testing.T) {
	path := filepath.Join(t.TempDir(), "f")
	f := OSOpen(Str(path), openRead|openWrite|openCreate, 0o644)
	defer OSClose(f)
	if !OSLock(f, 0, 1<<63-1, false, false) {
		t.Fatal("lock not acquired")
	}
	OSUnlock(f, 0, 1<<63-1)
	if !OSLock(f, 2, 3, true, true) {
		t.Fatal("shared lock not acquired")
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal(err)
	}
}

func TestClassIsAssignableFrom(t *testing.T) {
	for _, c := range []struct {
		to, from string
		want     bool
	}{
		{"java.lang.Object", "java.lang.String", true},
		{"java.lang.CharSequence", "java.lang.String", true},
		{"java.lang.Number", "java.lang.Integer", true},
		{"java.lang.Integer", "java.lang.Number", false},
		{"java.lang.String", "java.lang.Object", false},
		{"int", "int", true},
		{"java.lang.Object", "int", false},
		{"[Ljava.lang.Object;", "[Ljava.lang.Object;", true},
		{"java.io.Serializable", "[I", true},
		{"[Ljava.lang.Object;", "[I", false},
	} {
		if got := ClassIsAssignableFrom(ClassOf(c.to), ClassOf(c.from)); got != c.want {
			t.Errorf("%s.isAssignableFrom(%s) = %v", c.to, c.from, got)
		}
	}
}

func TestIsUnitArray(t *testing.T) {
	if IsUnitArray(NewArray[any](0)) || IsUnitArray(ArrayOf[any](UnitValue, nil)) || !IsUnitArray(ArrayOf[any](UnitValue)) {
		t.Error("IsUnitArray")
	}
}
