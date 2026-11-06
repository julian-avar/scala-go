package rt

import (
	"reflect"
	"strconv"
)

// Go's own data types seen from Scala (scalago.go.GoSlice, GoMap, GoChan, GoPtr): Go values in
// an `any`, shared with Go code without copying.
//
// Their elements use canonical Scala representations, decided by the Go element type alone (as
// JavaScript values have canonical Scala.js representations):
//
//	int, int64, uint, uint64, uintptr  Long (int64)
//	int32, uint32                      Int (int32)
//	int16 / uint16                     Short (int16) / Char (uint16)
//	int8, uint8                        Byte (int8)
//	float32, float64, bool             Float, Double, Boolean
//	string                             GoString (the Go string itself)
//	struct values                      a pointer to a copy (facades of struct types hold pointers)
//	anything else                      the Go value itself
//
// Only the predeclared (unnamed) basic types are converted: values of named types such as
// time.Duration stay Go values, as their facades expect.

// ---- Types (scalago.go.GoType) ------------------------------------------------------------

var basicTypes = map[string]reflect.Type{
	"bool": reflect.TypeFor[bool](), "string": reflect.TypeFor[string](),
	"int": reflect.TypeFor[int](), "int8": reflect.TypeFor[int8](), "int16": reflect.TypeFor[int16](),
	"int32": reflect.TypeFor[int32](), "int64": reflect.TypeFor[int64](),
	"uint": reflect.TypeFor[uint](), "uint8": reflect.TypeFor[uint8](), "uint16": reflect.TypeFor[uint16](),
	"uint32": reflect.TypeFor[uint32](), "uint64": reflect.TypeFor[uint64](), "uintptr": reflect.TypeFor[uintptr](),
	"float32": reflect.TypeFor[float32](), "float64": reflect.TypeFor[float64](),
	"any": reflect.TypeFor[any](), "error": reflect.TypeFor[error](),
	"scala.String": reflect.TypeFor[*String](),
}

func GoTypeBasic(name *String) any {
	t, ok := basicTypes[name.GoString()]
	if !ok {
		ThrowKind(IllegalArgument, "unknown Go type "+name.GoString())
	}
	return t
}

func goType(t any) reflect.Type { return Cast[reflect.Type](t) }

func GoTypeSlice(elem any) any     { return reflect.SliceOf(goType(elem)) }
func GoTypeMap(key, value any) any { return reflect.MapOf(goType(key), goType(value)) }
func GoTypeChan(elem any) any      { return reflect.ChanOf(reflect.BothDir, goType(elem)) }
func GoTypePtr(elem any) any       { return reflect.PointerTo(goType(elem)) }
func GoTypeName(t any) *String     { return Str(goType(t).String()) }

// ---- Conversions --------------------------------------------------------------------------

// toScalaValue is the canonical Scala representation of a Go value.
func toScalaValue(v reflect.Value) any {
	t := v.Type()
	if t.PkgPath() == "" && t.Name() != "" { // predeclared
		switch t.Kind() {
		case reflect.Int, reflect.Int64:
			return v.Int()
		case reflect.Uint, reflect.Uint64, reflect.Uintptr:
			return int64(v.Uint())
		case reflect.Int32:
			return int32(v.Int())
		case reflect.Uint32:
			return int32(uint32(v.Uint()))
		case reflect.Int16:
			return int16(v.Int())
		case reflect.Uint16:
			return uint16(v.Uint())
		case reflect.Int8:
			return int8(v.Int())
		case reflect.Uint8:
			return int8(uint8(v.Uint()))
		}
	}
	switch t.Kind() {
	case reflect.Struct:
		p := reflect.New(t)
		p.Elem().Set(v)
		return p.Interface()
	case reflect.Interface:
		if v.IsNil() {
			return nil
		}
		return v.Elem().Interface()
	case reflect.Pointer:
		if v.IsNil() {
			return nil
		}
	}
	return v.Interface()
}

// fromScalaValue converts a Scala value to Go type t (the inverse of toScalaValue).
func fromScalaValue(x any, t reflect.Type) reflect.Value {
	if x == nil {
		return reflect.Zero(t)
	}
	v := reflect.ValueOf(x)
	if t.PkgPath() == "" && t.Name() != "" {
		switch t.Kind() {
		case reflect.Int, reflect.Int64, reflect.Int32, reflect.Int16, reflect.Int8:
			if v.CanInt() {
				return reflect.ValueOf(v.Int()).Convert(t)
			}
		case reflect.Uint, reflect.Uint64, reflect.Uintptr:
			if v.CanInt() {
				return reflect.ValueOf(uint64(v.Int())).Convert(t)
			}
		case reflect.Uint32:
			if v.CanInt() {
				return reflect.ValueOf(uint32(v.Int())).Convert(t)
			}
		case reflect.Uint8:
			if v.CanInt() {
				return reflect.ValueOf(uint8(v.Int())).Convert(t)
			}
		}
	}
	if t.Kind() == reflect.Struct && v.Kind() == reflect.Pointer && v.Type().Elem() == t {
		return v.Elem()
	}
	if !v.Type().AssignableTo(t) {
		ThrowKind(ClassCast, GetClass(x).name+" cannot be stored as Go type "+t.String())
	}
	if t.Kind() == reflect.Interface {
		r := reflect.New(t).Elem()
		r.Set(v)
		return r
	}
	return v
}

func valueOf(x any) reflect.Value {
	if x == nil {
		ThrowKind(NullPointer, "")
	}
	return reflect.ValueOf(x)
}

func checkIndex(i int32, length int) {
	if i < 0 || int(i) >= length {
		ThrowKind(ArrayIndexOutOfBounds, "Index "+itoa(i)+" out of bounds for length "+strconv.Itoa(length))
	}
}

// GoIsNil reports whether x is null or a nil Go slice, map, channel, pointer or func.
func GoIsNil(x any) bool {
	if x == nil {
		return true
	}
	switch v := reflect.ValueOf(x); v.Kind() {
	case reflect.Slice, reflect.Map, reflect.Chan, reflect.Pointer, reflect.Func, reflect.Interface:
		return v.IsNil()
	}
	return false
}

// ---- Slices -------------------------------------------------------------------------------

func GoMakeSlice(elem any, length, capacity int32) any {
	if length < 0 || capacity < length {
		ThrowKind(IllegalArgument, "invalid slice length "+itoa(length)+" or capacity "+itoa(capacity))
	}
	return reflect.MakeSlice(reflect.SliceOf(goType(elem)), int(length), int(capacity)).Interface()
}

func GoSliceLength(s any) int32 {
	switch s := s.(type) {
	case []byte:
		return int32(len(s))
	case []string:
		return int32(len(s))
	case []int:
		return int32(len(s))
	case []any:
		return int32(len(s))
	}
	return int32(valueOf(s).Len())
}

func GoSliceCapacity(s any) int32 { return int32(valueOf(s).Cap()) }

func GoSliceGet(s any, i int32) any {
	switch s := s.(type) {
	case []byte:
		checkIndex(i, len(s))
		return int8(s[i])
	case []string:
		checkIndex(i, len(s))
		return s[i]
	case []int:
		checkIndex(i, len(s))
		return int64(s[i])
	case []int32:
		checkIndex(i, len(s))
		return s[i]
	case []int64:
		checkIndex(i, len(s))
		return s[i]
	case []float64:
		checkIndex(i, len(s))
		return s[i]
	case []any:
		checkIndex(i, len(s))
		return s[i]
	}
	v := valueOf(s)
	checkIndex(i, v.Len())
	return toScalaValue(v.Index(int(i)))
}

func GoSliceSet(s any, i int32, x any) {
	switch s := s.(type) {
	case []byte:
		checkIndex(i, len(s))
		s[i] = uint8(Cast[int8](x))
		return
	case []string:
		checkIndex(i, len(s))
		s[i] = Cast[string](x)
		return
	case []int:
		checkIndex(i, len(s))
		s[i] = int(Cast[int64](x))
		return
	case []any:
		checkIndex(i, len(s))
		s[i] = x
		return
	}
	v := valueOf(s)
	checkIndex(i, v.Len())
	v.Index(int(i)).Set(fromScalaValue(x, v.Type().Elem()))
}

// GoSliceSlice is s[from:until] (until may go up to the capacity, as in Go).
func GoSliceSlice(s any, from, until int32) any {
	v := valueOf(s)
	if from < 0 || until < from || int(until) > v.Cap() {
		ThrowKind(IndexOutOfBounds, "slice bounds ["+itoa(from)+":"+itoa(until)+"] with capacity "+strconv.Itoa(v.Cap()))
	}
	return v.Slice(int(from), int(until)).Interface()
}

func GoSliceAppend(s any, x any) any {
	v := valueOf(s)
	return reflect.Append(v, fromScalaValue(x, v.Type().Elem())).Interface()
}

func GoSliceAppendAll(s any, other any) any {
	return reflect.AppendSlice(valueOf(s), valueOf(other)).Interface()
}

func GoSliceCopy(dst, src any) int32 {
	return int32(reflect.Copy(valueOf(dst), valueOf(src)))
}

// ---- Maps ---------------------------------------------------------------------------------

func GoMakeMap(key, value any) any {
	return reflect.MakeMap(reflect.MapOf(goType(key), goType(value))).Interface()
}

func GoMapSize(m any) int32 { return int32(valueOf(m).Len()) }

// GoMapLookup returns the value for key k, or missing if there is none.
func GoMapLookup(m any, k any, missing any) any {
	v := valueOf(m)
	r := v.MapIndex(fromScalaValue(k, v.Type().Key()))
	if !r.IsValid() {
		return missing
	}
	return toScalaValue(r)
}

func GoMapStore(m any, k any, x any) {
	v := valueOf(m)
	if v.IsNil() {
		ThrowKind(UnsupportedOperation, "assignment to entry in nil map")
	}
	v.SetMapIndex(fromScalaValue(k, v.Type().Key()), fromScalaValue(x, v.Type().Elem()))
}

func GoMapDelete(m any, k any) {
	v := valueOf(m)
	if v.IsNil() {
		return
	}
	v.SetMapIndex(fromScalaValue(k, v.Type().Key()), reflect.Value{})
}

// GoMapEntries returns the entries as an array of alternating keys and values (in Go's map
// iteration order, which is randomized).
func GoMapEntries(m any) *Array[any] {
	v := valueOf(m)
	out := make([]any, 0, 2*v.Len())
	for it := v.MapRange(); it.Next(); {
		out = append(out, toScalaValue(it.Key()), toScalaValue(it.Value()))
	}
	return &Array[any]{Data: out}
}

// ---- Channels -----------------------------------------------------------------------------

func GoMakeChan(elem any, capacity int32) any {
	if capacity < 0 {
		ThrowKind(IllegalArgument, "negative channel capacity")
	}
	return reflect.MakeChan(reflect.ChanOf(reflect.BothDir, goType(elem)), int(capacity)).Interface()
}

func GoChanSend(c any, x any) {
	v := valueOf(c)
	FlushStdout()
	v.Send(fromScalaValue(x, v.Type().Elem()))
}

// GoChanReceive blocks for a value; it returns closed once the channel is closed and drained.
func GoChanReceive(c any, closed any) any {
	v := valueOf(c)
	FlushStdout()
	r, ok := v.Recv()
	if !ok {
		return closed
	}
	return toScalaValue(r)
}

func GoChanTrySend(c any, x any) bool {
	v := valueOf(c)
	return v.TrySend(fromScalaValue(x, v.Type().Elem()))
}

// GoChanTryReceive returns none if no value is ready, closed if the channel is closed.
func GoChanTryReceive(c any, none any, closed any) any {
	r, ok := valueOf(c).TryRecv()
	switch {
	case ok:
		return toScalaValue(r)
	case r.IsValid(): // the zero value: the channel is closed
		return closed
	}
	return none
}

func GoChanClose(c any)          { valueOf(c).Close() }
func GoChanLength(c any) int32   { return int32(valueOf(c).Len()) }
func GoChanCapacity(c any) int32 { return int32(valueOf(c).Cap()) }

// ---- Pointers -----------------------------------------------------------------------------

func GoNewPtr(elem any) any { return reflect.New(goType(elem)).Interface() }

func GoPtrGet(p any) any {
	v := valueOf(p)
	if v.IsNil() {
		ThrowKind(NullPointer, "")
	}
	return toScalaValue(v.Elem())
}

func GoPtrSet(p any, x any) {
	v := valueOf(p)
	if v.IsNil() {
		ThrowKind(NullPointer, "")
	}
	v.Elem().Set(fromScalaValue(x, v.Type().Elem()))
}

// ---- Funcs --------------------------------------------------------------------------------

// Go funcs returned to Scala are wrapped by the linker into these shapes (arguments and result
// already converted), and held by scalago.go.GoFunctionN objects.

func GoCall0(f any) any              { return f.(func() any)() }
func GoCall1(f any, a any) any       { return f.(func(any) any)(a) }
func GoCall2(f any, a, b any) any    { return f.(func(any, any) any)(a, b) }
func GoCall3(f any, a, b, c any) any { return f.(func(any, any, any) any)(a, b, c) }
