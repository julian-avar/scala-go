package rt

import (
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"syscall"
	"time"
	"unicode"
	"unicode/utf8"
)

// The native side of java.io and java.nio.file (javalib/src/scalago/runtime/OS.scala): a small,
// byte-oriented layer over Go's os package. The Java semantics (which exception, which message)
// are written in Scala on top of it.
//
// A file handle is an *os.File, or a consoleFile for standard output and error, held by Scala as
// an Object. Operations on paths return their result, or a Go error as an Object, which Scala
// turns into the right Java exception (OSIsError, OSErrorKind, OSErrorReason). Reads and writes
// on handles throw java.io.IOException.

// Open modes (OS.scala).
const (
	openRead      = 1
	openWrite     = 2
	openAppend    = 4
	openCreate    = 8
	openCreateNew = 16
	openTruncate  = 32
	openRejectDir = 64 // fail with EISDIR on a directory, as java.io's streams do
)

// consoleFile writes to the runtime's standard output buffer or to standard error, so that
// FileDescriptor.out stays ordered with System.out.
type consoleFile struct{ stderr bool }

var (
	stdoutHandle any = &consoleFile{false}
	stderrHandle any = &consoleFile{true}
)

func OSStdin() any  { return os.Stdin }
func OSStdout() any { return stdoutHandle }
func OSStderr() any { return stderrHandle }

func goPath(path *String) string { return path.GoString() }

// OSOpen opens a file, returning the handle or an error.
func OSOpen(path *String, mode int32, perm int32) any {
	flags := 0
	switch {
	case mode&openRead != 0 && mode&openWrite != 0:
		flags = os.O_RDWR
	case mode&openWrite != 0:
		flags = os.O_WRONLY
	default:
		flags = os.O_RDONLY
	}
	if mode&openAppend != 0 {
		flags |= os.O_APPEND
	}
	if mode&openCreateNew != 0 {
		flags |= os.O_CREATE | os.O_EXCL
	} else if mode&openCreate != 0 {
		flags |= os.O_CREATE
	}
	if mode&openTruncate != 0 {
		flags |= os.O_TRUNC
	}
	p := goPath(path)
	f, err := os.OpenFile(p, flags, fs.FileMode(perm))
	if err != nil {
		return err
	}
	if mode&openRejectDir != 0 {
		if info, err := f.Stat(); err == nil && info.IsDir() {
			f.Close()
			return &fs.PathError{Op: "open", Path: p, Err: syscall.EISDIR}
		}
	}
	return f
}

func ioFailure(err error) {
	ThrowKind(IO, errorReason(err))
}

// OSRead reads up to n bytes into b[off:], returning the count, or -1 at the end of the file.
// Reading standard input first flushes standard output, so that prompts appear.
func OSRead(handle any, b *Array[int8], off, n int32) int32 {
	f, ok := handle.(*os.File)
	if !ok {
		ThrowKind(IO, "Bad file descriptor")
	}
	if n == 0 {
		return 0
	}
	if f == os.Stdin {
		FlushStdout()
	}
	buf := Reinterpret[int8, byte](b.Data[off : off+n])
	for {
		count, err := f.Read(buf)
		if count > 0 {
			return int32(count)
		}
		if err == io.EOF {
			return -1
		}
		if err != nil {
			ioFailure(err)
		}
	}
}

// OSWrite writes b[off:off+n].
func OSWrite(handle any, b *Array[int8], off, n int32) {
	buf := Reinterpret[int8, byte](b.Data[off : off+n])
	switch f := handle.(type) {
	case *consoleFile:
		if f.stderr {
			FlushStdout()
			os.Stderr.Write(buf)
		} else {
			writeStdoutBytes(buf)
		}
	case *os.File:
		if _, err := f.Write(buf); err != nil {
			ioFailure(err)
		}
	default:
		ThrowKind(IO, "Bad file descriptor")
	}
}

func fileOf(handle any) *os.File {
	f, ok := handle.(*os.File)
	if !ok {
		ThrowKind(IO, "Illegal seek")
	}
	return f
}

// OSReadAt reads up to n bytes at position pos of the file into b[off:], without moving the
// file position: the count, or -1 at the end of the file.
func OSReadAt(handle any, b *Array[int8], off, n int32, pos int64) int32 {
	if n == 0 {
		return 0
	}
	count, err := fileOf(handle).ReadAt(Reinterpret[int8, byte](b.Data[off:off+n]), pos)
	if count > 0 {
		return int32(count)
	}
	if err == io.EOF {
		return -1
	}
	if err != nil {
		ioFailure(err)
	}
	return 0
}

// OSWriteAt writes b[off:off+n] at position pos of the file, without moving the file position.
func OSWriteAt(handle any, b *Array[int8], off, n int32, pos int64) {
	if _, err := fileOf(handle).WriteAt(Reinterpret[int8, byte](b.Data[off:off+n]), pos); err != nil {
		ioFailure(err)
	}
}

// OSLock locks size bytes of the file from position (to the end of the file and beyond if size
// is Long.MAX_VALUE), shared or exclusive, as the JDK does with fcntl. If wait is false and
// another process holds a conflicting lock, it returns false instead of waiting.
func OSLock(handle any, position, size int64, shared, wait bool) bool {
	ok, err := lockFile(fileOf(handle), position, size, shared, wait)
	if err != nil {
		ioFailure(err)
	}
	return ok
}

// OSUnlock releases a lock that OSLock acquired.
func OSUnlock(handle any, position, size int64) {
	if err := unlockFile(fileOf(handle), position, size); err != nil {
		ioFailure(err)
	}
}

// OSSeek moves the file position (whence: 0 start, 1 current, 2 end) and returns the new one.
func OSSeek(handle any, offset int64, whence int32) int64 {
	pos, err := fileOf(handle).Seek(offset, int(whence))
	if err != nil {
		ioFailure(err)
	}
	return pos
}

// OSAvailable estimates the bytes readable without blocking: what remains of a regular file, 0
// for anything else.
func OSAvailable(handle any) int32 {
	f, ok := handle.(*os.File)
	if !ok {
		return 0
	}
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() {
		return 0
	}
	pos, err := f.Seek(0, io.SeekCurrent)
	if err != nil || pos >= info.Size() {
		return 0
	}
	return int32(min(info.Size()-pos, 1<<31-1))
}

func OSFileSize(handle any) int64 {
	info, err := fileOf(handle).Stat()
	if err != nil {
		ioFailure(err)
	}
	return info.Size()
}

func OSTruncate(handle any, size int64) {
	if err := fileOf(handle).Truncate(size); err != nil {
		ioFailure(err)
	}
}

func OSSync(handle any) {
	switch f := handle.(type) {
	case *consoleFile:
		FlushStdout()
	case *os.File:
		if err := f.Sync(); err != nil {
			ioFailure(err)
		}
	}
}

func OSClose(handle any) {
	switch f := handle.(type) {
	case *consoleFile:
		FlushStdout()
	case *os.File:
		if err := f.Close(); err != nil && !errors.Is(err, os.ErrClosed) {
			ioFailure(err)
		}
	}
}

// File types in OSStat's result.
const (
	typeRegular   = 1
	typeDirectory = 2
	typeSymlink   = 3
	typeOther     = 4
)

// OSStat returns [type, size, last modified (ms), permission bits], or an error.
func OSStat(path *String, followLinks bool) any {
	var info fs.FileInfo
	var err error
	if followLinks {
		info, err = os.Stat(goPath(path))
	} else {
		info, err = os.Lstat(goPath(path))
	}
	if err != nil {
		return err
	}
	kind := int64(typeOther)
	switch mode := info.Mode(); {
	case mode.IsRegular():
		kind = typeRegular
	case mode.IsDir():
		kind = typeDirectory
	case mode&fs.ModeSymlink != 0:
		kind = typeSymlink
	}
	return ArrayOf(kind, info.Size(), info.ModTime().UnixMilli(), int64(info.Mode().Perm()))
}

// OSAccess reports whether the process may read (4), write (2) or execute (1) the file.
func OSAccess(path *String, mode int32) bool { return access(goPath(path), uint32(mode)) }

// nilOrError returns a nil Object for success.
func nilOrError(err error) any {
	if err != nil {
		return err
	}
	return nil
}

func OSMkdir(path *String, perm int32) any {
	return nilOrError(os.Mkdir(goPath(path), fs.FileMode(perm)))
}

// OSRemove deletes a file or an empty directory.
func OSRemove(path *String) any { return nilOrError(os.Remove(goPath(path))) }

// OSRename moves a file, replacing the target if it is a file.
func OSRename(from, to *String) any { return nilOrError(os.Rename(goPath(from), goPath(to))) }

// OSReadDir returns the names of a directory's entries, in directory order, or an error.
func OSReadDir(path *String) any {
	f, err := os.Open(goPath(path))
	if err != nil {
		return err
	}
	defer f.Close()
	names, err := f.Readdirnames(-1)
	if err != nil {
		return err
	}
	out := make([]any, len(names))
	for i, n := range names {
		out[i] = Str(n)
	}
	return &Array[any]{Data: out}
}

// OSCreateTemp creates a new file or directory named prefix + random digits + suffix in dir,
// returning its path or an error.
func OSCreateTemp(dir, prefix, suffix *String, directory bool) any {
	pattern := prefix.GoString() + "*" + suffix.GoString()
	if directory {
		p, err := os.MkdirTemp(goPath(dir), pattern)
		if err != nil {
			return err
		}
		return Str(p)
	}
	f, err := os.CreateTemp(goPath(dir), pattern)
	if err != nil {
		return err
	}
	f.Close()
	return Str(f.Name())
}

func OSChmod(path *String, perm int32) any {
	return nilOrError(os.Chmod(goPath(path), fs.FileMode(perm)))
}

func OSSetModified(path *String, millis int64) any {
	t := time.UnixMilli(millis)
	return nilOrError(os.Chtimes(goPath(path), t, t))
}

func OSSymlink(target, link *String) any { return nilOrError(os.Symlink(goPath(target), goPath(link))) }

func OSLink(existing, link *String) any { return nilOrError(os.Link(goPath(existing), goPath(link))) }

func OSReadLink(path *String) any {
	target, err := os.Readlink(goPath(path))
	if err != nil {
		return err
	}
	return Str(target)
}

// OSRealPath resolves symbolic links and makes the path absolute.
func OSRealPath(path *String) any {
	p, err := filepath.EvalSymlinks(goPath(path))
	if err != nil {
		return err
	}
	if p, err = filepath.Abs(p); err != nil {
		return err
	}
	return Str(p)
}

func OSWorkingDir() *String {
	wd, err := os.Getwd()
	if err != nil {
		return Str(".")
	}
	return Str(wd)
}

// ---- Errors ---------------------------------------------------------------------------------

// Error kinds (OS.scala), which select the Java exception.
const (
	errOther = iota
	errNoSuchFile
	errExists
	errNotEmpty
	errAccessDenied
	errNotDirectory
	errIsDirectory
	errLoop
)

func OSIsError(x any) bool {
	_, ok := x.(error)
	return ok
}

func OSErrorKind(e any) int32 {
	err := e.(error)
	switch {
	case errors.Is(err, syscall.ENOTEMPTY): // before ErrExist, which matches it too
		return errNotEmpty
	case errors.Is(err, fs.ErrNotExist):
		return errNoSuchFile
	case errors.Is(err, fs.ErrExist):
		return errExists
	case errors.Is(err, fs.ErrPermission):
		return errAccessDenied
	case errors.Is(err, syscall.ENOTDIR):
		return errNotDirectory
	case errors.Is(err, syscall.EISDIR):
		return errIsDirectory
	case errors.Is(err, syscall.ELOOP):
		return errLoop
	}
	return errOther
}

// OSErrorReason is the C library's message for the error ("No such file or directory"), which
// is what the JDK puts in its exceptions.
func OSErrorReason(e any) *String { return Str(errorReason(e.(error))) }

func errorReason(err error) string {
	var pathErr *fs.PathError
	var linkErr *os.LinkError
	switch {
	case errors.As(err, &pathErr):
		err = pathErr.Err
	case errors.As(err, &linkErr):
		err = linkErr.Err
	}
	// Go's messages for system errors are the C library's, starting in lower case.
	msg := err.Error()
	r, size := utf8.DecodeRuneInString(msg)
	return string(unicode.ToUpper(r)) + msg[size:]
}
