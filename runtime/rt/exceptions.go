package rt

import (
	"os"
	"reflect"
	"regexp"
	"runtime"
	"strconv"
	"strings"
)

// Exception is the value passed to panic when Scala code throws: it wraps the Throwable.
type Exception struct {
	Value any
}

func (e *Exception) Error() string { return ToGoString(e.Value) }

// ExceptionKind identifies the java.lang exceptions the runtime itself needs to raise.
type ExceptionKind int

const (
	NullPointer ExceptionKind = iota
	Arithmetic
	ArrayIndexOutOfBounds
	StringIndexOutOfBounds
	ClassCast
	IllegalArgument
	NegativeArraySize
	NumberFormat
	UnsupportedOperation
	ArrayStore
	RuntimeError // any other Go runtime error
	IllegalMonitorState
	Interrupted
	NoClassDefFound
	IndexOutOfBounds
	IO
	NoSuchMethod
	NoSuchField
)

var kindNames = [...]string{
	"java.lang.NullPointerException", "java.lang.ArithmeticException",
	"java.lang.ArrayIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
	"java.lang.ClassCastException", "java.lang.IllegalArgumentException",
	"java.lang.NegativeArraySizeException", "java.lang.NumberFormatException",
	"java.lang.UnsupportedOperationException", "java.lang.ArrayStoreException",
	"java.lang.RuntimeException", "java.lang.IllegalMonitorStateException",
	"java.lang.InterruptedException", "java.lang.NoClassDefFoundError",
	"java.lang.IndexOutOfBoundsException", "java.io.IOException",
	"java.lang.NoSuchMethodError", "java.lang.NoSuchFieldError",
}

// MakeException is installed by the generated program: it instantiates the Scala exception
// class for a kind, or returns nil if that class is not part of the program.
var MakeException func(kind ExceptionKind, message *String) any

// fallbackException stands in for an exception class that is not part of the program.
type fallbackException struct {
	kind    ExceptionKind
	message string
}

func (e *fallbackException) ScalaClassName() string { return kindNames[e.kind] }

func (e *fallbackException) M_toString_p_rT() *String {
	if e.message == "" {
		return Str(kindNames[e.kind])
	}
	return Str(kindNames[e.kind] + ": " + e.message)
}

func NewException(kind ExceptionKind, message string) any {
	var msg *String
	if message != "" {
		msg = Str(message)
	}
	if MakeException != nil {
		if e := MakeException(kind, msg); e != nil {
			return e
		}
	}
	return &fallbackException{kind, message}
}

// Exc prepares a Throwable for panic. Throwing null throws a NullPointerException.
func Exc(throwable any) *Exception {
	if throwable == nil {
		return &Exception{NewException(NullPointer, "")}
	}
	return &Exception{throwable}
}

// ThrowKind throws one of the runtime's own exceptions.
func ThrowKind(kind ExceptionKind, message string) {
	panic(&Exception{NewException(kind, message)})
}

// Try runs body and returns the Throwable it threw, or nil.
func Try(body func()) (throwable any) {
	defer func() {
		if r := recover(); r != nil {
			throwable = Caught(r)
		}
	}()
	body()
	return nil
}

var (
	indexOutOfRange = regexp.MustCompile(`index out of range \[(-?\d+)\] with length (\d+)`)
	negativeIndex   = regexp.MustCompile(`index out of range \[(-\d+)\]$`)
)

// Caught converts a recovered panic value into a Throwable, translating Go runtime errors into
// the corresponding Java exceptions. It must be called by the deferred function that recovers:
// the panicking frames are still on the stack then, so the stack trace of an exception made from
// a runtime error starts where the error happened.
func Caught(r any) any {
	if e, ok := r.(*Exception); ok {
		return e.Value
	}
	// The faulting function may have no frame record (see CaptureStackTrace).
	preciseCaptures.Add(1)
	defer preciseCaptures.Add(-1)
	switch e := r.(type) {
	case *runtime.TypeAssertionError:
		return NewException(ClassCast, e.Error())
	case runtime.Error:
		msg := e.Error()
		switch {
		case strings.Contains(msg, "nil pointer dereference"):
			return NewException(NullPointer, "")
		case strings.Contains(msg, "integer divide by zero"):
			return NewException(Arithmetic, "/ by zero")
		case strings.Contains(msg, "makeslice: len out of range"):
			return NewException(NegativeArraySize, "")
		case indexOutOfRange.MatchString(msg):
			m := indexOutOfRange.FindStringSubmatch(msg)
			return NewException(ArrayIndexOutOfBounds, "Index "+m[1]+" out of bounds for length "+m[2])
		case negativeIndex.MatchString(msg):
			if length, ok := boundsLength(e); ok {
				m := negativeIndex.FindStringSubmatch(msg)
				return NewException(ArrayIndexOutOfBounds, "Index "+m[1]+" out of bounds for length "+strconv.FormatInt(length, 10))
			}
		}
		return NewException(RuntimeError, msg)
	}
	return NewException(RuntimeError, ToGoString(r))
}

// boundsLength is the length in an index error, which gc's message for a negative index leaves
// out: its error value (runtime.boundsError) holds it.
func boundsLength(e runtime.Error) (int64, bool) {
	if v := reflect.ValueOf(e); v.Kind() == reflect.Struct {
		if y := v.FieldByName("y"); y.Kind() == reflect.Int {
			return y.Int(), true
		}
	}
	return 0, false
}

// ReportUncaught prints an exception that ended a thread, as the JVM's default handler does:
// `Exception in thread "main" ` and the stack trace, on standard error.
func ReportUncaught(threadName string, throwable any) {
	FlushStdout()
	os.Stderr.WriteString("Exception in thread \"" + threadName + "\" ")
	if t, ok := throwable.(interface{ M_printStackTrace_p_rV() }); ok {
		Try(t.M_printStackTrace_p_rV) // as on the JVM, an exception thrown while reporting is ignored
		return
	}
	os.Stderr.WriteString(ToGoString(throwable) + "\n")
}
