package rt

import (
	"reflect"
	"unsafe"
)

// Support for calls between Scala code and Go packages (Go facades, scalago.go.GoImport).

// MakeGoException is installed by the generated program: it wraps a Go error into a
// scalago.go.GoException, or returns nil if that class is not part of the program.
var MakeGoException func(err error) any

// GoExceptionError is installed by the generated program: it returns the Go error wrapped by a
// scalago.go.GoException, or nil if the throwable is not one.
var GoExceptionError func(throwable any) error

// ThrowGoError throws a Go error as a scalago.go.GoException.
func ThrowGoError(err error) {
	if MakeGoException != nil {
		if e := MakeGoException(err); e != nil {
			panic(&Exception{e})
		}
	}
	panic(&Exception{NewException(RuntimeError, err.Error())})
}

// Check throws err (if not nil) as a scalago.go.GoException.
func Check(err error) {
	if err != nil {
		ThrowGoError(err)
	}
}

// ScalaError is a Scala Throwable seen as a Go error (when Scala code implementing a Go
// interface method throws).
type ScalaError struct {
	Throwable any
}

func (e *ScalaError) Error() string { return ToGoString(e.Throwable) }

// RecoverAsError is deferred by Go methods implemented in Scala whose last result is an error:
// a Scala exception becomes that error (a GoException gives back the error it wraps).
func RecoverAsError(err *error) {
	if r := recover(); r != nil {
		throwable := Caught(r)
		if GoExceptionError != nil {
			if e := GoExceptionError(throwable); e != nil {
				*err = e
				return
			}
		}
		*err = &ScalaError{throwable}
	}
}

// GoCallback runs a Scala callback called from Go: buffered Scala output is flushed afterwards,
// so that it stays ordered with output written by Go code.
func GoCallback() { stdout.Flush() }

// ---- Values -----------------------------------------------------------------------------------

// FromPtr stores a Go pointer as a Scala reference: a nil pointer becomes null.
func FromPtr[T any](p *T) any {
	if p == nil {
		return nil
	}
	return p
}

// Addr stores a Go struct value as a Scala reference (facades of struct types hold pointers).
func Addr[T any](v T) any { return &v }

// Deref is the Go struct value of a facade reference (null throws a NullPointerException).
func Deref[T any](x any) T {
	if x == nil {
		ThrowKind(NullPointer, "")
	}
	return *x.(*T)
}

// ---- Slices and arrays -------------------------------------------------------------------------

// SliceOf shares the elements of an array with a Go slice (null becomes a nil slice).
func SliceOf[T any](a *Array[T]) []T {
	if a == nil {
		return nil
	}
	return a.Data
}

// ArrayOfSlice shares the elements of a Go slice with an array (a nil slice becomes an empty array).
func ArrayOfSlice[T any](s []T) *Array[T] { return &Array[T]{Data: s} }

// Reinterpret shares the elements of a slice between types with the same representation
// (Scala's Byte is int8, Go's byte is uint8).
func Reinterpret[T, U any](s []T) []U {
	if s == nil {
		return nil
	}
	if len(s) == 0 {
		return []U{}
	}
	return unsafe.Slice((*U)(unsafe.Pointer(unsafe.SliceData(s))), len(s))
}

// ConvertSlice converts the elements of an array into a new Go slice (null becomes a nil slice).
func ConvertSlice[T, U any](a *Array[T], f func(T) U) []U {
	if a == nil {
		return nil
	}
	out := make([]U, len(a.Data))
	for i, x := range a.Data {
		out[i] = f(x)
	}
	return out
}

// ConvertToArray converts the elements of a Go slice into a new array.
func ConvertToArray[T, U any](s []T, f func(T) U) *Array[U] {
	out := make([]U, len(s))
	for i, x := range s {
		out[i] = f(x)
	}
	return &Array[U]{Data: out}
}

// ---- Foreign values in Scala code -----------------------------------------------------------------

// foreignEquals is reference equality for Go values held by Scala code; values Go cannot compare
// (maps, slices, funcs) are equal when they are the same object.
func foreignEquals(a, b any) bool {
	ta, tb := reflect.TypeOf(a), reflect.TypeOf(b)
	if ta != tb {
		return false
	}
	if ta == nil || ta.Comparable() {
		return a == b
	}
	va, vb := reflect.ValueOf(a), reflect.ValueOf(b)
	switch va.Kind() {
	case reflect.Map, reflect.Slice, reflect.Func, reflect.Pointer, reflect.Chan, reflect.UnsafePointer:
		return va.Pointer() == vb.Pointer() && (va.Kind() != reflect.Slice || va.Len() == vb.Len())
	}
	return false
}

// foreignHashCode hashes Go values held by Scala code consistently with foreignEquals.
func foreignHashCode(x any) int32 {
	switch v := x.(type) {
	case string:
		var h int32
		for i := 0; i < len(v); i++ {
			h = 31*h + int32(v[i])
		}
		return h
	case error:
		return IdentityHashCode(x)
	}
	rv := reflect.ValueOf(x)
	switch rv.Kind() {
	case reflect.Map, reflect.Slice, reflect.Func, reflect.Pointer, reflect.Chan, reflect.UnsafePointer:
		p := uint64(rv.Pointer())
		return int32(p ^ (p >> 32))
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		i := rv.Int()
		return int32(i ^ (i >> 32))
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		i := rv.Uint()
		return int32(i ^ (i >> 32))
	case reflect.Bool:
		if rv.Bool() {
			return 1231
		}
		return 1237
	}
	return 0
}

// foreignToString formats a Go value held by Scala code, as Go's fmt would.
func foreignToString(x any) (string, bool) {
	switch v := x.(type) {
	case error:
		return v.Error(), true
	case interface{ String() string }:
		return v.String(), true
	}
	return "", false
}

// foreignClassName names the Go type of a value held by Scala code (e.g. `*strings.Builder`).
func foreignClassName(x any) string { return reflect.TypeOf(x).String() }

// ToGoAny passes a Scala value to a Go parameter of type any: Scala strings become Go strings.
func ToGoAny(x any) any {
	if s, ok := x.(*String); ok && s != nil {
		return s.GoString()
	}
	return x
}

// CastForeign implements asInstanceOf for a Go type: null passes, other values must have type T.
func CastForeign[T any](x any) any {
	if x != nil {
		if _, ok := x.(T); !ok {
			ThrowKind(ClassCast, GetClass(x).name+" cannot be cast to "+reflect.TypeFor[T]().String())
		}
	}
	return x
}
