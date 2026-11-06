package rt

import (
	"math"
	"reflect"
	"strconv"
	"strings"
)

// ToString implements String.valueOf(x): Java formatting for primitives, "null" for nil, and
// the object's own toString() otherwise.
func ToString(x any) *String {
	switch v := x.(type) {
	case *String:
		if v == nil {
			return Str("null")
		}
		return v
	case nil:
		return Str("null")
	case uint16:
		return StringValueOfChar(v)
	case scalaToString:
		return ToString(v.M_toString_p_rLjava_dlang_dString_e())
	}
	return Str(ToGoString(x))
}

// ToGoString is ToString as a Go string.
func ToGoString(x any) string {
	switch v := x.(type) {
	case nil:
		return "null"
	case *String:
		if v == nil {
			return "null"
		}
		return v.GoString()
	case string: // scalago.go.GoString
		return v
	case bool:
		return strconv.FormatBool(v)
	case uint16:
		return StringValueOfChar(v).GoString()
	case int8:
		return strconv.FormatInt(int64(v), 10)
	case int16:
		return strconv.FormatInt(int64(v), 10)
	case int32:
		return strconv.FormatInt(int64(v), 10)
	case int64:
		return strconv.FormatInt(v, 10)
	case float32:
		return formatFloating(float64(v), 32)
	case float64:
		return formatFloating(v, 64)
	case *BoxedUnit:
		return "()"
	case *Class:
		return ClassToString(v).GoString()
	case *BigInteger:
		return v.String()
	case scalaToString:
		return ToGoString(v.M_toString_p_rLjava_dlang_dString_e())
	}
	if s, ok := foreignToString(x); ok {
		return s
	}
	return defaultToString(x)
}

// defaultToString mimics java.lang.Object.toString: ClassName@hexHashCode.
func defaultToString(x any) string {
	return GetClass(x).name + "@" + strconv.FormatUint(uint64(uint32(HashCode(x))), 16)
}

// formatFloating implements Java's Double.toString / Float.toString (shortest representation
// that round-trips, as in JDK 19+).
func formatFloating(d float64, bits int) string {
	switch {
	case math.IsNaN(d):
		return "NaN"
	case math.IsInf(d, 1):
		return "Infinity"
	case math.IsInf(d, -1):
		return "-Infinity"
	case d == 0:
		if math.Signbit(d) {
			return "-0.0"
		}
		return "0.0"
	}
	if abs := math.Abs(d); abs >= 1e-3 && abs < 1e7 {
		s := strconv.FormatFloat(d, 'f', -1, bits)
		if !strings.Contains(s, ".") {
			s += ".0"
		}
		return s
	}
	s := strconv.FormatFloat(d, 'E', -1, bits) // e.g. "1.2345E+07"
	mantissa, exponent, _ := strings.Cut(s, "E")
	if !strings.Contains(mantissa, ".") {
		mantissa += ".0"
	}
	exp, _ := strconv.Atoi(exponent)
	return mantissa + "E" + strconv.Itoa(exp)
}

func itoa(i int32) string { return strconv.Itoa(int(i)) }

// ObjectEquals implements a.equals(b) for a non-null receiver.
func ObjectEquals(a, b any) bool {
	switch v := a.(type) {
	case nil:
		ThrowKind(NullPointer, "")
	case *String:
		return StringEquals(v, b)
	case *BigInteger:
		return BigIntegerEquals(v, b)
	case scalaEquals:
		return v.M_equals_pLjava_dlang_dObject_e_rZ(b)
	case float32:
		w, ok := b.(float32)
		return ok && math.Float32bits(v) == math.Float32bits(w)
	case float64:
		w, ok := b.(float64)
		return ok && math.Float64bits(v) == math.Float64bits(w)
	}
	return foreignEquals(a, b)
}

// Equals implements Scala's universal equality (==) on references: null-safe, and cooperative
// between boxed numeric types (1 == 1L is true).
func Equals(a, b any) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	if x, ok := numericValue(a); ok {
		if y, ok := numericValue(b); ok {
			return x.equals(y)
		}
	}
	return ObjectEquals(a, b)
}

type numeric struct {
	isFloating bool
	i          int64
	f          float64
}

func (x numeric) equals(y numeric) bool {
	if x.isFloating || y.isFloating {
		return x.float() == y.float()
	}
	return x.i == y.i
}

func (x numeric) float() float64 {
	if x.isFloating {
		return x.f
	}
	return float64(x.i)
}

func numericValue(x any) (numeric, bool) {
	switch v := x.(type) {
	case uint16:
		return numeric{i: int64(v)}, true
	case int8:
		return numeric{i: int64(v)}, true
	case int16:
		return numeric{i: int64(v)}, true
	case int32:
		return numeric{i: int64(v)}, true
	case int64:
		return numeric{i: v}, true
	case float32:
		return numeric{isFloating: true, f: float64(v)}, true
	case float64:
		return numeric{isFloating: true, f: v}, true
	}
	return numeric{}, false
}

// HashCode implements x.hashCode() with Java's results for strings and boxed primitives.
func HashCode(x any) int32 {
	switch v := x.(type) {
	case nil:
		ThrowKind(NullPointer, "")
	case *String:
		return StringHashCode(v)
	case *BigInteger:
		return BigIntegerHashCode(v)
	case bool:
		if v {
			return 1231
		}
		return 1237
	case uint16:
		return int32(v)
	case int8:
		return int32(v)
	case int16:
		return int32(v)
	case int32:
		return v
	case int64:
		return int32(v ^ int64(uint64(v)>>32))
	case float32:
		return int32(math.Float32bits(v))
	case float64:
		b := math.Float64bits(v)
		return int32(b ^ (b >> 32))
	case scalaHashCode:
		return v.M_hashCode_p_rI()
	}
	return foreignHashCode(x)
}

// IdentityHashCode implements System.identityHashCode. Go's garbage collector does not move
// heap objects, so the address is stable.
func IdentityHashCode(x any) int32 {
	if x == nil {
		return 0
	}
	if rv := reflect.ValueOf(x); rv.Kind() == reflect.Pointer {
		p := uint64(rv.Pointer())
		return int32(p ^ (p >> 32))
	}
	return 0
}

// Cast implements asInstanceOf and unboxing: nil converts to the zero value of T.
func Cast[T any](x any) T {
	if x == nil {
		var zero T
		return zero
	}
	return x.(T)
}

// CastNull is a cast to a class that is never instantiated: only null passes.
func CastNull(x any) any {
	if x != nil {
		ThrowKind(ClassCast, GetClass(x).name+" cannot be cast to a class with no instances")
	}
	return nil
}

// Is implements isInstanceOf for a single Go type.
func Is[T any](x any) bool {
	_, ok := x.(T)
	return ok
}

// Never is a type test that cannot succeed (the class is never instantiated).
func Never(any) bool { return false }

// BoxedUnit is scala.runtime.BoxedUnit; its only instance is UnitValue.
type BoxedUnit struct{}

var UnitValue = &BoxedUnit{}

// Object is an instance of java.lang.Object itself (`new Object()`, used as a lock or sentinel).
type Object struct{ ObjectHeader }

func NewObject() any { return &Object{} }

// ObjectDefaultToString is java.lang.Object's own toString: ClassName@hexIdentityHash.
func ObjectDefaultToString(x any) *String {
	return Str(GetClass(x).name + "@" + strconv.FormatUint(uint64(uint32(IdentityHashCode(x))), 16))
}

// ObjectDefaultEquals is java.lang.Object's own equals: reference identity.
func ObjectDefaultEquals(x any, other any) bool { return x == other }

// ---- Null ---------------------------------------------------------------------------------

// Scala references with a concrete Go pointer type (strings, arrays, BigIntegers, classes) are
// nil pointers when null. Stored in an `any`, such a nil pointer would not be a nil `any`, so
// conversions to `any` go through AnyRef, and null tests on `any` values through IsNull.

type nilRef interface{ isNilRef() bool }

func (s *String) isNilRef() bool     { return s == nil }
func (a *Array[T]) isNilRef() bool   { return a == nil }
func (b *BigInteger) isNilRef() bool { return b == nil }
func (c *Class) isNilRef() bool      { return c == nil }

// AnyRef converts a Scala reference of a pointer type to `any`, keeping null a nil `any`.
func AnyRef[T nilRef](x T) any {
	if x.isNilRef() {
		return nil
	}
	return x
}

// IsNull tests an `any` holding a Scala reference for null.
func IsNull(x any) bool {
	if x == nil {
		return true
	}
	if r, ok := x.(nilRef); ok {
		return r.isNilRef()
	}
	return false
}
