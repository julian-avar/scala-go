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
//	struct{}                           Unit
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
	"any": reflect.TypeFor[any](), "error": reflect.TypeFor[error](), "struct{}": emptyStruct,
	"complex128":   reflect.TypeFor[complex128](),
	"scala.String": reflect.TypeFor[*String](),
}

var emptyStruct = reflect.TypeFor[struct{}]()

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
		if t == emptyStruct {
			return UnitValue
		}
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
	if t == emptyStruct && x == any(UnitValue) {
		return reflect.Zero(t)
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

// Blocking channel operations of a java.lang.Thread can be interrupted (Thread.interrupt), which
// throws InterruptedException, as blocking queue operations do on the JVM. gears cancels
// blocked operations this way.

func GoChanSend(c any, x any) {
	v := valueOf(c)
	FlushStdout()
	value := fromScalaValue(x, v.Type().Elem())
	if t := currentThreadState(GoID()); t != nil {
		cases := []reflect.SelectCase{{Dir: reflect.SelectSend, Chan: v, Send: value}, {Dir: reflect.SelectRecv, Chan: reflect.ValueOf(t.wake)}}
		for {
			if chosen, _, _ := reflect.Select(cases); chosen == 0 {
				return
			}
			if t.interrupted.Swap(false) {
				ThrowKind(Interrupted, "")
			}
		}
	}
	v.Send(value)
}

// GoChanReceive blocks for a value; it returns closed once the channel is closed and drained.
func GoChanReceive(c any, closed any) any {
	v := valueOf(c)
	FlushStdout()
	var r reflect.Value
	var ok bool
	if t := currentThreadState(GoID()); t != nil {
		cases := []reflect.SelectCase{{Dir: reflect.SelectRecv, Chan: v}, {Dir: reflect.SelectRecv, Chan: reflect.ValueOf(t.wake)}}
		for {
			var chosen int
			if chosen, r, ok = reflect.Select(cases); chosen == 0 {
				break
			}
			if t.interrupted.Swap(false) {
				ThrowKind(Interrupted, "")
			}
		}
	} else {
		r, ok = v.Recv()
	}
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

// ---- Plain data (scalago.go.GoData) -------------------------------------------------------

// Kinds of Go data, as GoNatives.dataKind reports them.
const (
	basicData = 1
	mapData   = 2
	sliceData = 3
)

// GoDataKind tells values of predeclared basic types, maps, and slices and arrays apart from
// other values (0).
func GoDataKind(x any) int32 {
	if x == nil {
		return 0
	}
	t := reflect.TypeOf(x)
	switch t.Kind() {
	case reflect.Map:
		return mapData
	case reflect.Slice, reflect.Array:
		return sliceData
	case reflect.Bool, reflect.String, reflect.Float32, reflect.Float64,
		reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64,
		reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		if t.PkgPath() == "" { // predeclared, not a named type such as time.Duration
			return basicData
		}
	}
	return 0
}

// GoIntData is x as a Go int, the type of Go's integer literals in an any.
func GoIntData(x int64) any { return int(x) }

// GoBasicData is a value of a basic type as a Scala String, Long, Double or Boolean.
func GoBasicData(x any) any {
	switch v := reflect.ValueOf(x); v.Kind() {
	case reflect.String:
		return Str(v.String())
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return v.Int()
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return int64(v.Uint())
	case reflect.Float32, reflect.Float64:
		return v.Float()
	}
	return x
}

// ---- Funcs --------------------------------------------------------------------------------

// Go funcs returned to Scala are wrapped by the linker into these shapes (arguments and result
// already converted), and held by scalago.go.GoFunctionN objects.

func GoCall0(f any) any              { return f.(func() any)() }
func GoCall1(f any, a any) any       { return f.(func(any) any)(a) }
func GoCall2(f any, a, b any) any    { return f.(func(any, any) any)(a, b) }
func GoCall3(f any, a, b, c any) any { return f.(func(any, any, any) any)(a, b, c) }

// ---- Complex numbers (scalago.go.GoComplex: a complex128 in an any) ------------------------------

func GoComplexMake(re, im float64) any { return complex(re, im) }
func GoComplexReal(z any) float64      { return real(Cast[complex128](z)) }
func GoComplexImag(z any) float64      { return imag(Cast[complex128](z)) }
func GoComplexAdd(z, w any) any        { return Cast[complex128](z) + Cast[complex128](w) }
func GoComplexSub(z, w any) any        { return Cast[complex128](z) - Cast[complex128](w) }
func GoComplexMul(z, w any) any        { return Cast[complex128](z) * Cast[complex128](w) }
func GoComplexDiv(z, w any) any        { return Cast[complex128](z) / Cast[complex128](w) }

// ---- Calls through reflection ----------------------------------------------------------------

// CallReflect calls func f with args and, if f is variadic, the elements of slice spread (or
// none if nil). The generated code calls this when parameter or result types are ones it cannot
// name (unexported, or of internal packages): reflection converts the arguments.
func CallReflect(f any, args []any, spread any) []any {
	fv := reflect.ValueOf(f)
	ft := fv.Type()
	in := make([]reflect.Value, 0, len(args))
	for i, a := range args {
		in = append(in, reflectArg(a, ft.In(i)))
	}
	if ft.IsVariadic() && spread != nil {
		elem := ft.In(ft.NumIn() - 1).Elem()
		sv := reflect.ValueOf(spread)
		for i := 0; i < sv.Len(); i++ {
			in = append(in, reflectArg(sv.Index(i).Interface(), elem))
		}
	}
	out := fv.Call(in)
	results := make([]any, len(out))
	for i, o := range out {
		results[i] = o.Interface()
	}
	return results
}

func reflectArg(a any, t reflect.Type) reflect.Value {
	if a == nil {
		return reflect.Zero(t)
	}
	v := reflect.ValueOf(a)
	switch {
	case v.Type().AssignableTo(t):
		return v
	case v.Type().ConvertibleTo(t):
		return v.Convert(t)
	}
	ThrowKind(ClassCast, GetClass(a).name+" cannot be passed as Go type "+t.String())
	return reflect.Value{}
}

// resultAs is a result of CallReflect as type T (nil is T's zero value).
func resultAs[T any](x any) T {
	if x == nil {
		var zero T
		return zero
	}
	return x.(T)
}

func Results1[A any](r []any) A         { return resultAs[A](r[0]) }
func Results2[A, B any](r []any) (A, B) { return resultAs[A](r[0]), resultAs[B](r[1]) }
func Results3[A, B, C any](r []any) (A, B, C) {
	return resultAs[A](r[0]), resultAs[B](r[1]), resultAs[C](r[2])
}
func Results4[A, B, C, D any](r []any) (A, B, C, D) {
	return resultAs[A](r[0]), resultAs[B](r[1]), resultAs[C](r[2]), resultAs[D](r[3])
}
