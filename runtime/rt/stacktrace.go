package rt

import (
	"os"
	"reflect"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"unicode/utf16"
	"unsafe"
)

// Stack traces of Throwables. Throwable.fillInStackTrace captures the call stack as program
// counters (CaptureStackTrace), which is cheap. The frames are symbolized only when the trace is
// asked for (getStackTrace, printStackTrace), into the JVM's StackTraceElements:
//
//   - The generated code carries line directives (see the linker's LineDirectives), so Go reports
//     Scala files and lines.
//   - Method implementations are named Impl_<class>__<method>_p<params>_r<result> (see the
//     linker's Mangling), which demangles back to the Scala class and method names.
//   - Frames the JVM would not show are dropped: the Go runtime, this package, the generated
//     helpers (forwarders, dispatchers, allocators, module accessors), lambda classes, the
//     Throwable's own construction, and the enclosing function of a try block's closure (the
//     closure's frame stands for it, as the JVM has one frame per method invocation).
//
// A Go runtime error (nil dereference, ...) becomes a Java exception while its panic is being
// recovered (Caught), before the stack unwinds, so its trace starts where the fault happened.
//
// SCALAGO_TRACE=1 keeps all frames, with their Go function names and Go positions where there
// is no line directive: for debugging the runtime and the generated code.

// StackTrace is a captured call stack: the value of Throwable's backtrace field.
type StackTrace struct {
	pcs    []uintptr
	once   sync.Once
	frames []stackFrame
}

type stackFrame struct {
	class, method, file *String // file is nil if unknown
	line                int32   // -1 if unknown
}

// maxStackDepth bounds captured stacks, as the JVM's MaxJavaStackTraceDepth does.
const maxStackDepth = 1024

var showAllFrames = os.Getenv("SCALAGO_TRACE") != ""

// preciseCaptures counts the Go runtime errors being converted into exceptions (see Caught).
var preciseCaptures atomic.Int32

// iscgo is set by the runtime if the program links cgo. (The runtime supports linking to it.)
//
//go:linkname iscgo runtime.iscgo
var iscgo bool

// CaptureStackTrace returns the stack of its caller (Throwable.fillInStackTrace).
//
// Where Go keeps frame pointers, the stack is captured by following them: a nanosecond or two per
// frame, where runtime.Callers takes tens. CallersFrames expands the inlined calls later. But a
// function without a frame of its own (a small leaf function) that faults leaves no record of
// its caller, and C frames may not keep frame pointers, so exceptions made from Go runtime
// errors, and programs that have called C code, use runtime.Callers.
//
//go:noinline
func CaptureStackTrace() any {
	var buf [128]uintptr
	n := captureStack(buf[:])
	if n < len(buf) {
		return &StackTrace{pcs: append([]uintptr(nil), buf[:n]...)}
	}
	pcs := make([]uintptr, maxStackDepth)
	return &StackTrace{pcs: pcs[:captureStack(pcs)]}
}

// captureStack fills pcs with the return addresses of the calling frames. (Frames of this
// package, such as CaptureStackTrace's if captureStack is not inlined, are dropped later.)
func captureStack(pcs []uintptr) int {
	if !haveFramePointers || preciseCaptures.Load() != 0 || iscgo && runtime.NumCgoCall() != 0 {
		return runtime.Callers(3, pcs) // skips runtime.Callers, captureStack and CaptureStackTrace
	}
	n := 0
	// The frame record at fp holds the caller's frame pointer, then the return address.
	for fp := framePointer(); fp != nil && n < len(pcs); fp = *(*unsafe.Pointer)(fp) {
		pcs[n] = *(*uintptr)(unsafe.Add(fp, unsafe.Sizeof(fp)))
		n++
	}
	return n
}

func StackTraceDepth(trace any) int32 { return int32(len(symbolized(trace))) }

func StackTraceClassName(trace any, i int32) *String  { return symbolized(trace)[i].class }
func StackTraceMethodName(trace any, i int32) *String { return symbolized(trace)[i].method }
func StackTraceFileName(trace any, i int32) *String   { return symbolized(trace)[i].file }
func StackTraceLineNumber(trace any, i int32) int32   { return symbolized(trace)[i].line }

func symbolized(trace any) []stackFrame {
	t := trace.(*StackTrace)
	t.once.Do(func() {
		if showAllFrames {
			t.frames = allFrames(t.pcs)
		} else {
			t.frames = scalaFrames(t.pcs)
		}
		t.pcs = nil
	})
	return t.frames
}

// The Go package paths of this package and of the generated code (<module>/internal/{rt,scala}),
// as prefixes of function names.
var (
	rtPrefix        = reflect.TypeOf(StackTrace{}).PkgPath() + "."
	generatedPrefix = strings.TrimSuffix(rtPrefix, "rt.") + "scala."
	tryFunction     = rtPrefix + "Try"
)

// scalaFrames symbolizes a stack captured by Throwable.fillInStackTrace into the frames the JVM
// would show.
func scalaFrames(pcs []uintptr) []stackFrame {
	var frames []stackFrame
	// The capture is made in fillInStackTrace, usually called by Throwable's constructor: skip
	// fillInStackTrace, then the constructors of the Throwable up to its allocation (New_, or Mod_
	// for an object), as the JVM skips the <init> frames of the Throwable's classes.
	skipFill, skipInit := true, true
	// The function a try block's closure belongs to, once its rt.Try frame is seen: its own frame,
	// next, is dropped.
	lastClosure, mergeInto := "", ""
	callers := runtime.CallersFrames(pcs)
	for more := true; more; {
		var f runtime.Frame
		f, more = callers.Next()
		name, generated := strings.CutPrefix(f.Function, generatedPrefix)
		if !generated {
			if f.Function == tryFunction {
				mergeInto = lastClosure
			}
			continue
		}
		base, closure := name, false
		if i := strings.IndexByte(name, '.'); i > 0 {
			base, closure = name[:i], true
		}
		merged := mergeInto != "" && base == mergeInto
		mergeInto, lastClosure = "", ""
		if closure {
			lastClosure = base
		}
		if merged {
			continue
		}
		fn := demangled(base)
		switch {
		case skipFill && fn.kind == implFrame && fn.fill:
			continue
		case fn.kind == allocFrame:
			skipFill, skipInit = false, false
			continue
		case fn.kind == otherFrame:
			continue
		case skipInit && fn.kind == implFrame && fn.init:
			skipFill = false
			continue
		}
		skipFill, skipInit = false, false
		if fn.hidden {
			continue
		}
		frame := stackFrame{class: fn.class, method: fn.method, line: -1}
		// Without a line directive (none emitted, or code without positions), the position is in the
		// generated Go code: unknown, as far as Scala is concerned.
		if frame.file = fileName(f.File); frame.file != nil && fn.kind == implFrame {
			frame.line = int32(f.Line)
		}
		frames = append(frames, frame)
	}
	return frames
}

type frameKind int

const (
	otherFrame     frameKind = iota // generated helpers: not shown
	implFrame                       // a method implementation (Impl_)
	forwarderFrame                  // a static forwarder (Fwd_), shown without a line number
	allocFrame                      // an allocator (New_) or module accessor (Mod_): runs a constructor
)

// function is what a generated Go function stands for in stack traces.
type function struct {
	kind          frameKind
	class, method *String // for implFrame and forwarderFrame
	hidden        bool    // a lambda class's method, hidden as on the JVM
	fill, init    bool    // fillInStackTrace, a constructor
}

// Symbolization results, cached: the same functions and files recur from trace to trace.
var functions, fileNames sync.Map // Go function name -> *function, Go file name -> *String

func demangled(name string) *function {
	if fn, ok := functions.Load(name); ok {
		return fn.(*function)
	}
	fn, _ := functions.LoadOrStore(name, demangle(name))
	return fn.(*function)
}

// fileName is the file name of a position, or nil if it is in the generated Go code (there was
// no line directive), which is unknown as far as Scala is concerned.
func fileName(path string) *String {
	if name, ok := fileNames.Load(path); ok {
		return name.(*String)
	}
	var name *String
	if !strings.HasSuffix(path, ".go") {
		name = Str(baseName(path))
	}
	cached, _ := fileNames.LoadOrStore(path, name)
	return cached.(*String)
}

// demangle maps a generated function to its Scala class and method (see the linker's Mangling).
func demangle(name string) *function {
	var kind frameKind
	switch {
	case strings.HasPrefix(name, "Impl_"):
		kind = implFrame
	case strings.HasPrefix(name, "Fwd_"):
		kind = forwarderFrame
	case strings.HasPrefix(name, "New_"), strings.HasPrefix(name, "Mod_"):
		return &function{kind: allocFrame}
	default:
		return &function{kind: otherFrame}
	}
	rest := name[strings.IndexByte(name, '_')+1:]
	sep := strings.Index(rest, "__")
	end := strings.Index(rest, "_p")
	if sep < 0 || end < sep+2 {
		return &function{kind: otherFrame}
	}
	method := rest[sep+2 : end]
	if method == "_init" {
		method = "<init>"
	} else {
		method = unescape(method)
	}
	class := unescape(rest[:sep])
	return &function{kind: kind, class: Str(class), method: Str(method), hidden: strings.Contains(class, "$$Lambda$"),
		fill: method == "fillInStackTrace", init: method == "<init>"}
}

// unescape inverts the linker's Mangling.escape: _u is '_', _d '.', _S '$', _xHHHH a UTF-16 unit.
func unescape(s string) string {
	if !strings.Contains(s, "_") {
		return s
	}
	var b strings.Builder
	var units []uint16 // escaped UTF-16 units, decoded together (surrogate pairs)
	flush := func() {
		if units != nil {
			b.WriteString(string(utf16.Decode(units)))
			units = nil
		}
	}
	for i := 0; i < len(s); i++ {
		if s[i] == '_' && i+1 < len(s) && s[i+1] == 'x' && i+5 < len(s) {
			if v, err := strconv.ParseUint(s[i+2:i+6], 16, 16); err == nil {
				units = append(units, uint16(v))
				i += 5
				continue
			}
		}
		flush()
		if s[i] != '_' || i+1 >= len(s) {
			b.WriteByte(s[i])
			continue
		}
		i++
		switch s[i] {
		case 'u':
			b.WriteByte('_')
		case 'd':
			b.WriteByte('.')
		case 'S':
			b.WriteByte('$')
		default:
			b.WriteByte('_')
			b.WriteByte(s[i])
		}
	}
	flush()
	return b.String()
}

func baseName(path string) string {
	if i := strings.LastIndexAny(path, `/\`); i >= 0 {
		return path[i+1:]
	}
	return path
}

// allFrames symbolizes every frame (SCALAGO_TRACE=1): the class is the Go package path, the
// method the Go function name.
func allFrames(pcs []uintptr) []stackFrame {
	var frames []stackFrame
	callers := runtime.CallersFrames(pcs)
	for more := true; more; {
		var f runtime.Frame
		f, more = callers.Next()
		pkg, fn := "", f.Function
		if slash := strings.LastIndexByte(fn, '/'); slash >= 0 {
			if dot := strings.IndexByte(fn[slash:], '.'); dot >= 0 {
				pkg, fn = fn[:slash+dot], fn[slash+dot+1:]
			}
		} else if dot := strings.IndexByte(fn, '.'); dot >= 0 {
			pkg, fn = fn[:dot], fn[dot+1:]
		}
		frames = append(frames, stackFrame{class: Str(pkg), method: Str(fn), file: Str(baseName(f.File)), line: int32(f.Line)})
	}
	return frames
}
