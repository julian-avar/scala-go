package rt

import (
	"math"
	"slices"
)

// Natives for Java classes of the Scala library (scala.runtime.Statics, BoxesRunTime) and for
// java.util.Arrays / java.util.Objects.

// ---- scala.runtime.Statics (MurmurHash3 mixing and `##`) ----------------------------------

func rotl(x int32, n uint) int32 { return int32(uint32(x)<<n | uint32(x)>>(32-n)) }

func StaticsMixLast(hash, data int32) int32 {
	k := data
	k *= -862048943 // 0xcc9e2d51
	k = rotl(k, 15)
	k *= 461845907 // 0x1b873593
	return hash ^ k
}

func StaticsMix(hash, data int32) int32 {
	h := StaticsMixLast(hash, data)
	h = rotl(h, 13)
	return h*5 + -430675100 // 0xe6546b64
}

func StaticsAvalanche(h int32) int32 {
	h ^= int32(uint32(h) >> 16)
	h *= -2048144789 // 0x85ebca6b
	h ^= int32(uint32(h) >> 13)
	h *= -1028477387 // 0xc2b2ae35
	h ^= int32(uint32(h) >> 16)
	return h
}

func StaticsFinalizeHash(hash, length int32) int32 { return StaticsAvalanche(hash ^ length) }

func StaticsLongHash(lv int64) int32 {
	if iv := int32(lv); int64(iv) == lv {
		return iv
	}
	return HashCode(lv)
}

func StaticsDoubleHash(dv float64) int32 {
	if iv := int32(dv); float64(iv) == dv {
		return iv
	}
	if lv := int64(dv); float64(lv) == dv && !math.IsInf(dv, 0) {
		return HashCode(lv)
	}
	if fv := float32(dv); float64(fv) == dv {
		return HashCode(fv)
	}
	return HashCode(dv)
}

func StaticsFloatHash(fv float32) int32 {
	if iv := int32(fv); float32(iv) == fv {
		return iv
	}
	if lv := int64(fv); float32(lv) == fv && !math.IsInf(float64(fv), 0) {
		return HashCode(lv)
	}
	return HashCode(fv)
}

// AnyHash implements `x.##`: like hashCode, but consistent with cooperative equality (1.## == 1L.##).
func AnyHash(x any) int32 {
	switch v := x.(type) {
	case nil:
		return 0
	case int64:
		return StaticsLongHash(v)
	case float64:
		return StaticsDoubleHash(v)
	case float32:
		return StaticsFloatHash(v)
	case int8:
		return int32(v)
	case int16:
		return int32(v)
	case uint16:
		return int32(v)
	}
	return HashCode(x)
}

func IsBoxedNumber(x any) bool {
	switch x.(type) {
	case int8, int16, int32, int64, float32, float64:
		return true
	}
	return false
}

func IsBoxedNumberOrBoolean(x any) bool {
	_, isBool := x.(bool)
	return isBool || IsBoxedNumber(x)
}

// ---- java.util.Arrays -----------------------------------------------------------------------

func ArraysCopyOf[T any](a *Array[T], n int32) *Array[T] {
	if n < 0 {
		ThrowKind(NegativeArraySize, itoa(n))
	}
	data := make([]T, n)
	copy(data, a.Data)
	return &Array[T]{Data: data}
}

func ArraysCopyOfRange[T any](a *Array[T], from, to int32) *Array[T] {
	if from < 0 || int(from) > len(a.Data) {
		ThrowKind(ArrayIndexOutOfBounds, "Array index out of range: "+itoa(from))
	}
	if from > to {
		ThrowKind(IllegalArgument, itoa(from)+" > "+itoa(to))
	}
	data := make([]T, to-from)
	copy(data, a.Data[from:min(int(to), len(a.Data))])
	return &Array[T]{Data: data}
}

func ArraysFill[T any](a *Array[T], v T) {
	for i := range a.Data {
		a.Data[i] = v
	}
}

func ArraysFillRange[T any](a *Array[T], from, to int32, v T) {
	for i := from; i < to; i++ {
		a.Data[i] = v
	}
}

func ArraysEquals[T comparable](a, b *Array[T]) bool {
	if a == nil || b == nil {
		return a == b
	}
	return slices.Equal(a.Data, b.Data)
}

func ArraysEqualsObjects(a, b *Array[any]) bool {
	if a == nil || b == nil {
		return a == b
	}
	if len(a.Data) != len(b.Data) {
		return false
	}
	for i, x := range a.Data {
		if x == nil {
			if b.Data[i] != nil {
				return false
			}
		} else if !ObjectEquals(x, b.Data[i]) {
			return false
		}
	}
	return true
}

func ArraysHashCode[T any](a *Array[T]) int32 {
	if a == nil {
		return 0
	}
	var h int32 = 1
	for _, x := range a.Data {
		var eh int32
		if any(x) != nil {
			eh = HashCode(x)
		}
		h = 31*h + eh
	}
	return h
}

// ArraysSort sorts a primitive array in Java order (for floating point: -0.0 < 0.0, NaN last).
func ArraysSort[T int8 | int16 | int32 | int64 | uint16 | float32 | float64](a *Array[T], from, to int32) {
	if from < 0 || int(to) > len(a.Data) || from > to {
		ThrowKind(ArrayIndexOutOfBounds, "Array index out of range: "+itoa(to))
	}
	slices.SortFunc(a.Data[from:to], func(x, y T) int {
		switch v := any(x).(type) {
		case float64:
			return int(DoubleCompare(v, any(y).(float64)))
		case float32:
			return int(DoubleCompare(float64(v), float64(any(y).(float32))))
		}
		switch {
		case x < y:
			return -1
		case x > y:
			return 1
		}
		return 0
	})
}

// ---- java.util.Objects ----------------------------------------------------------------------

func ObjectsEquals(a, b any) bool {
	if a == nil {
		return b == nil
	}
	return ObjectEquals(a, b)
}

func ObjectsHashCode(a any) int32 {
	if a == nil {
		return 0
	}
	return HashCode(a)
}

func RequireNonNull(x any, message *String) any {
	if x == nil {
		msg := ""
		if message != nil {
			msg = message.GoString()
		}
		ThrowKind(NullPointer, msg)
	}
	return x
}

// PfMarker is scala.runtime.Statics.pfMarker, a sentinel used by PartialFunction.applyOrElse.
var PfMarker any = &struct{ marker byte }{}
