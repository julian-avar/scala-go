package rt

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

// The native side of java.lang.System's properties and of java.lang.Runtime
// (javalib/src/scalago/runtime/Process.scala).

var (
	propertiesMu   sync.Mutex
	properties     map[string]*String
	propertiesOnce sync.Once
)

// defaultProperties are the JDK's standard system properties that describe the platform.
func defaultProperties() map[string]*String {
	p := map[string]string{
		"line.separator":    "\n",
		"file.separator":    string(filepath.Separator),
		"path.separator":    string(filepath.ListSeparator),
		"file.encoding":     "UTF-8",
		"native.encoding":   "UTF-8",
		"sun.jnu.encoding":  "UTF-8",
		"stdout.encoding":   "UTF-8",
		"stderr.encoding":   "UTF-8",
		"java.io.tmpdir":    tempDir(),
		"os.name":           osName(),
		"os.arch":           osArch(),
		"java.vm.name":      "scala-go",
		"java.vendor":       "scala-go",
		"java.vendor.url":   "https://github.com/scalago/scala-go",
		"java.class.path":   "",
		"java.library.path": "",
	}
	if runtime.GOOS == "windows" {
		p["line.separator"] = "\r\n"
	}
	if wd, err := os.Getwd(); err == nil {
		p["user.dir"] = wd
	}
	if home, err := os.UserHomeDir(); err == nil {
		p["user.home"] = home
	}
	for _, v := range []string{"USER", "LOGNAME", "USERNAME"} {
		if name := os.Getenv(v); name != "" {
			p["user.name"] = name
			break
		}
	}
	out := make(map[string]*String, len(p))
	for k, v := range p {
		out[k] = Str(v)
	}
	return out
}

// tempDir is java.io.tmpdir: as on the JVM, $TMPDIR is only used on macOS.
func tempDir() string {
	if runtime.GOOS == "linux" {
		return "/tmp"
	}
	return os.TempDir()
}

func osName() string {
	switch runtime.GOOS {
	case "linux":
		return "Linux"
	case "darwin":
		return "Mac OS X"
	case "windows":
		return "Windows"
	case "freebsd":
		return "FreeBSD"
	}
	return runtime.GOOS
}

func osArch() string {
	switch runtime.GOARCH {
	case "arm64":
		return "aarch64"
	case "386":
		return "x86"
	}
	return runtime.GOARCH
}

func withProperties[T any](f func(map[string]*String) T) T {
	propertiesOnce.Do(func() { properties = defaultProperties() })
	propertiesMu.Lock()
	defer propertiesMu.Unlock()
	return f(properties)
}

func GetProperty(key *String) *String {
	k := key.GoString()
	return withProperties(func(p map[string]*String) *String { return p[k] })
}

// SetProperty sets a property (or removes it, if value is null) and returns the previous value.
func SetProperty(key, value *String) *String {
	k := key.GoString()
	return withProperties(func(p map[string]*String) *String {
		old := p[k]
		if value == nil {
			delete(p, k)
		} else {
			p[k] = value
		}
		return old
	})
}

// PropertyNames returns the names of the properties.
func PropertyNames() *Array[any] {
	return withProperties(func(p map[string]*String) *Array[any] {
		names := make([]any, 0, len(p))
		for k := range p {
			names = append(names, Str(k))
		}
		return &Array[any]{Data: names}
	})
}

// Environ returns the environment as alternating names and values.
func Environ() *Array[any] {
	env := os.Environ()
	out := make([]any, 0, 2*len(env))
	for _, kv := range env {
		if k, v, ok := strings.Cut(kv, "="); ok && k != "" {
			out = append(out, Str(k), Str(v))
		}
	}
	return &Array[any]{Data: out}
}

func AvailableProcessors() int32 { return int32(runtime.NumCPU()) }

// Memory statistics for Runtime.totalMemory/freeMemory/maxMemory: the heap Go obtained from the
// system, and the part of it not in use.
func TotalMemory() int64 {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return int64(m.HeapSys)
}

func FreeMemory() int64 {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return int64(m.HeapSys - m.HeapAlloc)
}

func GC() { runtime.GC() }
