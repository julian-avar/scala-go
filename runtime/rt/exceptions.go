package rt

import (
	"regexp"
	"runtime"
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
)

var kindNames = [...]string{
	"java.lang.NullPointerException", "java.lang.ArithmeticException",
	"java.lang.ArrayIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException",
	"java.lang.ClassCastException", "java.lang.IllegalArgumentException",
	"java.lang.NegativeArraySizeException", "java.lang.NumberFormatException",
	"java.lang.UnsupportedOperationException", "java.lang.ArrayStoreException",
	"java.lang.RuntimeException", "java.lang.IllegalMonitorStateException",
	"java.lang.InterruptedException", "java.lang.NoClassDefFoundError",
	"java.lang.IndexOutOfBoundsException",
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

func (e *fallbackException) M_toString_p_rLjava_dlang_dString_e() *String {
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

var indexOutOfRange = regexp.MustCompile(`index out of range \[(-?\d+)\] with length (\d+)`)

// Caught converts a recovered panic value into a Throwable, translating Go runtime errors into
// the corresponding Java exceptions.
func Caught(r any) any {
	switch e := r.(type) {
	case *Exception:
		return e.Value
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
		}
		return NewException(RuntimeError, msg)
	}
	return NewException(RuntimeError, ToGoString(r))
}
