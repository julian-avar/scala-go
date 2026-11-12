package rt

import (
	"reflect"
	"strconv"
	"strings"
	"sync"
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
func GoCallback() { FlushStdout() }

// ---- Values -----------------------------------------------------------------------------------

// FromPtr stores a Go pointer as a Scala reference: a nil pointer becomes null.
func FromPtr[T any](p *T) any {
	if p == nil {
		return nil
	}
	return p
}

// ConvertGo converts a Go value held by Scala code (a slice, map, channel or pointer) to Go type
// T: as is if it has that type, otherwise with a Go conversion (a bidirectional channel to a
// receive-only one, a slice to a named slice type). Null becomes the zero value (nil).
func ConvertGo[T any](x any) T {
	if v, ok := x.(T); ok || x == nil {
		return v
	}
	v := reflect.ValueOf(x)
	t := reflect.TypeFor[T]()
	if !v.CanConvert(t) {
		ThrowKind(ClassCast, GetClass(x).name+" cannot be converted to Go type "+t.String())
	}
	return v.Convert(t).Interface().(T)
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

// foreignEquals is Go's == for Go values held by Scala code, extended to the values Go cannot
// compare: maps, slices and funcs are equal when they are the same object, and structs, arrays
// and interfaces holding them compare by their parts.
func foreignEquals(a, b any) bool {
	ta, tb := reflect.TypeOf(a), reflect.TypeOf(b)
	if ta != tb {
		return false
	}
	if ta == nil {
		return true
	}
	if k := ta.Kind(); k != reflect.Struct && k != reflect.Array && ta.Comparable() {
		return a == b // structs and arrays may have interface parts holding uncomparable values
	}
	return foreignValueEquals(reflect.ValueOf(a), reflect.ValueOf(b))
}

// foreignValueEquals is foreignEquals on two values of the same type.
func foreignValueEquals(a, b reflect.Value) bool {
	switch a.Kind() {
	case reflect.Map, reflect.Slice, reflect.Func, reflect.Pointer, reflect.Chan, reflect.UnsafePointer:
		return a.Pointer() == b.Pointer() && (a.Kind() != reflect.Slice || a.Len() == b.Len())
	case reflect.Struct:
		for i := range a.NumField() {
			if !foreignValueEquals(a.Field(i), b.Field(i)) {
				return false
			}
		}
		return true
	case reflect.Array:
		for i := range a.Len() {
			if !foreignValueEquals(a.Index(i), b.Index(i)) {
				return false
			}
		}
		return true
	case reflect.Interface:
		if a.IsNil() || b.IsNil() {
			return a.IsNil() && b.IsNil()
		}
		return a.Elem().Type() == b.Elem().Type() && foreignValueEquals(a.Elem(), b.Elem())
	}
	return a.Equal(b) // booleans, numbers and strings
}

// RefEq implements `eq` on operands that may hold Go values: Go's ==, which panics on two values
// of the same uncomparable type (maps, slices, funcs, structs holding them); those compare as ==
// (foreignEquals) does.
func RefEq(a, b any) bool {
	// Objects, strings, arrays and boxes first.
	if k := reflect.ValueOf(a).Kind(); k == reflect.Pointer || k <= reflect.Complex128 {
		return a == b
	}
	return foreignEquals(a, b)
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
	case complex128: // scalago.go.GoComplex, formatted as fmt's %v
		return strconv.FormatComplex(v, 'g', -1, 128), true
	}
	return "", false
}

// foreignClassName names the Go type of a value held by Scala code (e.g. `*strings.Builder`).
func foreignClassName(x any) string { return reflect.TypeOf(x).String() }

// ToGoAny passes a Scala value to a Go parameter of type any: Scala strings become Go strings,
// and Go structs held by Scala code are passed as Go code passes them (see goStructValue).
func ToGoAny(x any) any {
	switch v := x.(type) {
	case nil, scalaClass, scalaClassName:
		return x
	case *String:
		if v != nil {
			return v.GoString()
		}
		return x
	}
	if value, ok := goStructValue(x, nil); ok {
		return value
	}
	return x
}

// ToGoInterface passes a Scala value to a Go parameter of interface type I (see goStructValue).
func ToGoInterface[I any](x any) I {
	switch x.(type) {
	case nil:
		var zero I
		return zero
	case scalaClass, scalaClassName:
		return x.(I)
	}
	if value, ok := goStructValue(x, reflect.TypeFor[I]()); ok {
		return value.(I)
	}
	return x.(I)
}

// structValueKey caches goStructValue's decision per struct pointer type and interface.
type structValueKey struct{ ptr, iface reflect.Type }

var (
	structValues sync.Map // structValueKey -> bool
	scalaPkgs    = func() [2]string {
		rt := reflect.TypeFor[String]().PkgPath()
		return [2]string{rt, strings.TrimSuffix(rt, "/rt") + "/scala"}
	}()
)

// goStructValue returns the struct value that a Go struct pointer held by Scala code stands for,
// when passed to a Go interface (iface; nil for any). Facades hold Go structs as pointers, but Go
// code passes many struct types by value, and code receiving them tests for values (bubbletea's
// `case tea.KeyMsg:`). A struct is passed by value when its value implements the interface:
// for any, when the type has no pointer-receiver methods (`tea.KeyMsg`, `lipgloss.Style`), and
// otherwise when the value's methods suffice (`time.Time` as a `fmt.Stringer`). Types with
// pointer-receiver methods stay pointers (`*bytes.Buffer` as an `io.Writer` or in an any).
func goStructValue(x any, iface reflect.Type) (any, bool) {
	t := reflect.TypeOf(x)
	if t.Kind() != reflect.Pointer || t.Elem().Kind() != reflect.Struct {
		return nil, false
	}
	key := structValueKey{t, iface}
	byValue, ok := structValues.Load(key)
	if !ok {
		elem := t.Elem()
		scala := elem.PkgPath() == scalaPkgs[0] || elem.PkgPath() == scalaPkgs[1]
		if iface == nil {
			byValue = !scala && t.NumMethod() == elem.NumMethod()
		} else {
			byValue = !scala && elem.Implements(iface)
		}
		structValues.Store(key, byValue)
	}
	if !byValue.(bool) {
		return nil, false
	}
	v := reflect.ValueOf(x)
	if v.IsNil() {
		return nil, false
	}
	return v.Elem().Interface(), true
}

// IsStruct implements isInstanceOf for a facade of Go struct type T. Facade references hold
// pointers (*T), but Go interfaces, and so Scala's Any, can also hold T values (bubbletea's
// tea.KeyMsg in a tea.Msg).
func IsStruct[T any](x any) bool {
	if _, ok := x.(*T); ok {
		return true
	}
	_, ok := x.(T)
	return ok
}

// CastStruct implements asInstanceOf for a facade of Go struct type T: a T value is copied to a
// new pointer (see IsStruct); null passes.
func CastStruct[T any](x any) any {
	switch v := x.(type) {
	case nil, *T:
		return x
	case T:
		return &v
	}
	ThrowKind(ClassCast, GetClass(x).name+" cannot be cast to "+reflect.TypeFor[*T]().String())
	return nil
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
