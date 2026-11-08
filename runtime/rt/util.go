package rt

import "slices"

// Natives for java.util: java.util.Arrays on primitive arrays (and the copying, filling,
// comparison and hashing of object arrays) and java.util.Objects. The rest of java.util is
// javalib (javalib/src/java/util); UUID's cryptography is in crypto.go.

// ---- java.util.Arrays -----------------------------------------------------------------------

// orderedPrimitive is the element type of the primitive arrays that can be sorted (boolean arrays
// cannot).
type orderedPrimitive interface {
	int8 | int16 | int32 | int64 | uint16 | float32 | float64
}

// checkArrayRange implements Arrays.rangeCheck.
func checkArrayRange(length int, from, to int32) {
	if from > to {
		ThrowKind(IllegalArgument, "fromIndex("+itoa(from)+") > toIndex("+itoa(to)+")")
	}
	if from < 0 {
		ThrowKind(ArrayIndexOutOfBounds, "Array index out of range: "+itoa(from))
	}
	if int(to) > length {
		ThrowKind(ArrayIndexOutOfBounds, "Array index out of range: "+itoa(to))
	}
}

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
	checkArrayRange(len(a.Data), from, to)
	for i := from; i < to; i++ {
		a.Data[i] = v
	}
}

// ArraysEquals compares primitive arrays. Floating-point elements are compared as by
// Double.equals: NaN equals NaN, and 0.0 does not equal -0.0.
func ArraysEquals[T comparable](a, b *Array[T]) bool {
	if a == nil || b == nil {
		return a == b
	}
	switch x := any(a.Data).(type) {
	case []float64:
		return slices.EqualFunc(x, any(b.Data).([]float64), func(p, q float64) bool {
			return DoubleToLongBits(p) == DoubleToLongBits(q)
		})
	case []float32:
		return slices.EqualFunc(x, any(b.Data).([]float32), func(p, q float32) bool {
			return FloatToIntBits(p) == FloatToIntBits(q)
		})
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

// ArraysHashCode implements Arrays.hashCode for every kind of array (and Objects.hash).
func ArraysHashCode[T any](a *Array[T]) int32 {
	if a == nil {
		return 0
	}
	var h int32 = 1
	// Primitive arrays without boxing each element.
	switch d := any(a.Data).(type) {
	case []int32:
		for _, x := range d {
			h = 31*h + x
		}
	case []int64:
		for _, x := range d {
			h = 31*h + int32(x^int64(uint64(x)>>32))
		}
	case []int16:
		for _, x := range d {
			h = 31*h + int32(x)
		}
	case []int8:
		for _, x := range d {
			h = 31*h + int32(x)
		}
	case []uint16:
		for _, x := range d {
			h = 31*h + int32(x)
		}
	default:
		for _, x := range a.Data {
			var eh int32
			if any(x) != nil {
				eh = HashCode(x)
			}
			h = 31*h + eh
		}
	}
	return h
}

// ArraysToString implements Arrays.toString for primitive arrays.
func ArraysToString[T any](a *Array[T]) *String {
	if a == nil {
		return Str("null")
	}
	chars := []uint16{'['}
	for i, x := range a.Data {
		if i > 0 {
			chars = append(chars, ',', ' ')
		}
		if c, ok := any(x).(uint16); ok {
			chars = append(chars, c)
		} else {
			chars = append(chars, ToString(x).chars...)
		}
	}
	return newString(append(chars, ']'))
}

// compareOrdered compares primitives as their boxes' compareTo does: floating-point numbers in
// Double.compare's total order (-0.0 < 0.0, NaN last).
func compareOrdered[T orderedPrimitive](x, y T) int {
	switch {
	case x < y:
		return -1
	case x > y:
		return 1
	case x == y && x != 0:
		return 0
	}
	switch v := any(x).(type) {
	case float64:
		return int(DoubleCompare(v, any(y).(float64)))
	case float32:
		return int(DoubleCompare(float64(v), float64(any(y).(float32))))
	}
	return 0
}

// ArraysSort sorts a primitive array in Java order (for floating point: -0.0 < 0.0, NaN last).
func ArraysSort[T orderedPrimitive](a *Array[T], from, to int32) {
	checkArrayRange(len(a.Data), from, to)
	slices.SortFunc(a.Data[from:to], compareOrdered[T])
}

// ArraysBinarySearch is the JDK's binary search (the same probes, so the same index among equal
// elements), in the order of compareOrdered.
func ArraysBinarySearch[T orderedPrimitive](a *Array[T], from, to int32, key T) int32 {
	checkArrayRange(len(a.Data), from, to)
	low, high := from, to-1
	for low <= high {
		mid := int32(uint32(low+high) >> 1)
		switch c := compareOrdered(a.Data[mid], key); {
		case c < 0:
			low = mid + 1
		case c > 0:
			high = mid - 1
		default:
			return mid
		}
	}
	return -(low + 1)
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
