package rt

import "os"

// Array is a JVM array: a nullable reference to a fixed-length sequence of elements. Arrays of
// primitives use the Go primitive type; arrays of references all use Array[any].
type Array[T any] struct {
	Data []T
}

func NewArray[T any](length int32) *Array[T] {
	if length < 0 {
		ThrowKind(NegativeArraySize, itoa(length))
	}
	return &Array[T]{Data: make([]T, length)}
}

func ArrayOf[T any](elems ...T) *Array[T] {
	return &Array[T]{Data: elems}
}

// Args returns the program arguments as an Array[String].
func Args() *Array[any] {
	args := make([]any, len(os.Args)-1)
	for i, a := range os.Args[1:] {
		args[i] = Str(a)
	}
	return &Array[any]{Data: args}
}

// NewArrayOf implements java.lang.reflect.Array.newInstance(componentType, length).
func NewArrayOf(component *Class, length int32) any {
	switch component.name {
	case "boolean":
		return NewArray[bool](length)
	case "char":
		return NewArray[uint16](length)
	case "byte":
		return NewArray[int8](length)
	case "short":
		return NewArray[int16](length)
	case "int":
		return NewArray[int32](length)
	case "long":
		return NewArray[int64](length)
	case "float":
		return NewArray[float32](length)
	case "double":
		return NewArray[float64](length)
	}
	return NewArray[any](length)
}

// arrayOps gives untyped access to any array, for reflection-like helpers.
type arrayOps interface {
	length() int32
	get(i int32) any
	set(i int32, v any)
	copyTo(srcPos int32, dst any, dstPos int32, n int32)
	clone() any
}

func (a *Array[T]) length() int32      { return int32(len(a.Data)) }
func (a *Array[T]) get(i int32) any    { return a.Data[i] }
func (a *Array[T]) set(i int32, v any) { a.Data[i] = Cast[T](v) }
func (a *Array[T]) clone() any         { return &Array[T]{Data: append([]T(nil), a.Data...)} }

func (a *Array[T]) copyTo(srcPos int32, dst any, dstPos int32, n int32) {
	d, ok := dst.(*Array[T])
	if !ok {
		ThrowKind(ArrayStore, "arraycopy: type mismatch")
	}
	if n < 0 || srcPos < 0 || dstPos < 0 || int(srcPos+n) > len(a.Data) || int(dstPos+n) > len(d.Data) {
		ThrowKind(ArrayIndexOutOfBounds, "arraycopy: last source index "+itoa(srcPos+n)+" out of bounds for length "+itoa(int32(len(a.Data))))
	}
	copy(d.Data[dstPos:dstPos+n], a.Data[srcPos:srcPos+n])
}

func asArray(a any) arrayOps {
	if a == nil {
		ThrowKind(NullPointer, "")
	}
	ops, ok := a.(arrayOps)
	if !ok {
		ThrowKind(IllegalArgument, "argument is not an array")
	}
	return ops
}

func ArrayLengthOf(a any) int32      { return asArray(a).length() }
func ArrayGet(a any, i int32) any    { return asArray(a).get(i) }
func ArraySet(a any, i int32, v any) { asArray(a).set(i, v) }
func ArrayClone(a any) any           { return asArray(a).clone() }
func ArrayCopy(src any, srcPos int32, dst any, dstPos int32, n int32) {
	asArray(dst)
	asArray(src).copyTo(srcPos, dst, dstPos, n)
}

// NewMultiArrayOf implements java.lang.reflect.Array.newInstance(componentType, dimensions...).
func NewMultiArrayOf(component *Class, dims *Array[int32]) any {
	if len(dims.Data) == 0 {
		ThrowKind(IllegalArgument, "empty dimensions array")
	}
	if len(dims.Data) == 1 {
		return NewArrayOf(component, dims.Data[0])
	}
	outer := NewArray[any](dims.Data[0])
	inner := &Array[int32]{Data: dims.Data[1:]}
	for i := range outer.Data {
		outer.Data[i] = NewMultiArrayOf(component, inner)
	}
	return outer
}

// IsArray reports whether x is an array (arrays are Cloneable and Serializable).
func IsArray(x any) bool {
	switch x.(type) {
	case *Array[any], *Array[bool], *Array[uint16], *Array[int8], *Array[int16], *Array[int32],
		*Array[int64], *Array[float32], *Array[float64]:
		return x != nil && !IsNull(x)
	}
	return false
}
