package rt

import (
	"math"
	"reflect"
	"strconv"
	"sync/atomic"
)

// @volatile fields: sequentially consistent loads, stores and read-modify-write operations, as
// the Java memory model requires of volatiles (and as javalib's atomics need). Fields of
// primitive types are sync/atomic values, which never allocate; fields of reference types are
// Volatile. The linker picks the type (LinkContext.fieldGoType); all have the same methods.

// Volatile is a @volatile field of a reference type: an atomic pointer to a box holding the
// value, since Go cannot load or store an interface atomically. Boxes are immutable once stored,
// so the last one is kept and reused when the same object is stored again: a lock's owner
// alternating between a thread and null, or a flag object set repeatedly, does not allocate.
type Volatile[T any] struct {
	p    atomic.Pointer[T]
	last atomic.Pointer[T] // the box last allocated, for reuse
}

func (v *Volatile[T]) Load() T {
	if p := v.p.Load(); p != nil {
		return *p
	}
	var zero T
	return zero
}

// box points to a box holding x, or is nil for null (Load reads a nil pointer as the zero value).
func (v *Volatile[T]) box(x T) *T {
	ref, isObject := any(x).(hasObjectHeader)
	if !isObject {
		if any(x) == nil {
			return nil
		}
		return newBox(x)
	}
	// Scala objects compare by identity (==, which cannot panic on them).
	if last := v.last.Load(); last != nil {
		if r, ok := any(*last).(hasObjectHeader); ok && r == ref {
			return last
		}
	}
	b := newBox(x)
	v.last.Store(b)
	return b
}

// newBox allocates a box for x. (Taking &x in box would move x to the heap on every call.)
func newBox[T any](x T) *T {
	b := new(T)
	*b = x
	return b
}

func (v *Volatile[T]) Store(x T) { v.p.Store(v.box(x)) }

// CompareAndSet replaces the value with update if it is expected (reference identity for
// objects, value equality for primitives).
func (v *Volatile[T]) CompareAndSet(expected, update T) bool {
	var up *T
	boxed := false
	for {
		old := v.p.Load()
		var current T
		if old != nil {
			current = *old
		}
		if !sameValue(current, expected) {
			return false
		}
		if !boxed {
			up, boxed = v.box(update), true
		}
		if v.p.CompareAndSwap(old, up) {
			return true
		}
	}
}

// GetAndSet stores x and returns the previous value.
func (v *Volatile[T]) GetAndSet(x T) T {
	old := v.p.Swap(v.box(x))
	if old == nil {
		var zero T
		return zero
	}
	return *old
}

func sameValue[T any](a, b T) bool {
	x, y := any(a), any(b)
	if x == nil || y == nil {
		return x == y
	}
	if xo, ok := x.(hasObjectHeader); ok { // the common case: Scala objects, by identity
		yo, ok := y.(hasObjectHeader)
		return ok && xo == yo
	}
	if t := reflect.TypeOf(x); t != reflect.TypeOf(y) {
		return false
	} else if !t.Comparable() {
		return sameRef(x, y)
	}
	return x == y
}

// sameRef is identity for Go values that == cannot compare (slices, maps, funcs).
func sameRef(x, y any) bool {
	vx, vy := reflect.ValueOf(x), reflect.ValueOf(y)
	switch vx.Kind() {
	case reflect.Slice, reflect.Map, reflect.Func, reflect.Pointer, reflect.Chan, reflect.UnsafePointer:
		return vx.Pointer() == vy.Pointer()
	}
	return false
}

// VolatileBool is a @volatile Boolean field.
type VolatileBool struct{ v atomic.Bool }

func (v *VolatileBool) Load() bool   { return v.v.Load() }
func (v *VolatileBool) Store(x bool) { v.v.Store(x) }
func (v *VolatileBool) CompareAndSet(expected, update bool) bool {
	return v.v.CompareAndSwap(expected, update)
}
func (v *VolatileBool) GetAndSet(x bool) bool { return v.v.Swap(x) }

// VolatileInt32 is a @volatile Int field.
type VolatileInt32 struct{ v atomic.Int32 }

func (v *VolatileInt32) Load() int32   { return v.v.Load() }
func (v *VolatileInt32) Store(x int32) { v.v.Store(x) }
func (v *VolatileInt32) CompareAndSet(expected, update int32) bool {
	return v.v.CompareAndSwap(expected, update)
}
func (v *VolatileInt32) GetAndSet(x int32) int32     { return v.v.Swap(x) }
func (v *VolatileInt32) GetAndAdd(delta int32) int32 { return v.v.Add(delta) - delta }

// VolatileInt64 is a @volatile Long field.
type VolatileInt64 struct{ v atomic.Int64 }

func (v *VolatileInt64) Load() int64   { return v.v.Load() }
func (v *VolatileInt64) Store(x int64) { v.v.Store(x) }
func (v *VolatileInt64) CompareAndSet(expected, update int64) bool {
	return v.v.CompareAndSwap(expected, update)
}
func (v *VolatileInt64) GetAndSet(x int64) int64     { return v.v.Swap(x) }
func (v *VolatileInt64) GetAndAdd(delta int64) int64 { return v.v.Add(delta) - delta }

// VolatileInt8, VolatileInt16 and VolatileUint16 are @volatile Byte, Short and Char fields,
// stored in 32 bits.
type VolatileInt8 struct{ v atomic.Int32 }

func (v *VolatileInt8) Load() int8   { return int8(v.v.Load()) }
func (v *VolatileInt8) Store(x int8) { v.v.Store(int32(x)) }
func (v *VolatileInt8) CompareAndSet(expected, update int8) bool {
	return v.v.CompareAndSwap(int32(expected), int32(update))
}
func (v *VolatileInt8) GetAndSet(x int8) int8 { return int8(v.v.Swap(int32(x))) }

type VolatileInt16 struct{ v atomic.Int32 }

func (v *VolatileInt16) Load() int16   { return int16(v.v.Load()) }
func (v *VolatileInt16) Store(x int16) { v.v.Store(int32(x)) }
func (v *VolatileInt16) CompareAndSet(expected, update int16) bool {
	return v.v.CompareAndSwap(int32(expected), int32(update))
}
func (v *VolatileInt16) GetAndSet(x int16) int16 { return int16(v.v.Swap(int32(x))) }

type VolatileUint16 struct{ v atomic.Uint32 }

func (v *VolatileUint16) Load() uint16   { return uint16(v.v.Load()) }
func (v *VolatileUint16) Store(x uint16) { v.v.Store(uint32(x)) }
func (v *VolatileUint16) CompareAndSet(expected, update uint16) bool {
	return v.v.CompareAndSwap(uint32(expected), uint32(update))
}
func (v *VolatileUint16) GetAndSet(x uint16) uint16 { return uint16(v.v.Swap(uint32(x))) }

// VolatileFloat32 and VolatileFloat64 are @volatile Float and Double fields, stored as their
// bits. Like the JVM's VarHandles, compare-and-set compares the bits.
type VolatileFloat32 struct{ v atomic.Uint32 }

func (v *VolatileFloat32) Load() float32   { return math.Float32frombits(v.v.Load()) }
func (v *VolatileFloat32) Store(x float32) { v.v.Store(math.Float32bits(x)) }
func (v *VolatileFloat32) CompareAndSet(expected, update float32) bool {
	return v.v.CompareAndSwap(math.Float32bits(expected), math.Float32bits(update))
}
func (v *VolatileFloat32) GetAndSet(x float32) float32 {
	return math.Float32frombits(v.v.Swap(math.Float32bits(x)))
}

type VolatileFloat64 struct{ v atomic.Uint64 }

func (v *VolatileFloat64) Load() float64   { return math.Float64frombits(v.v.Load()) }
func (v *VolatileFloat64) Store(x float64) { v.v.Store(math.Float64bits(x)) }
func (v *VolatileFloat64) CompareAndSet(expected, update float64) bool {
	return v.v.CompareAndSwap(math.Float64bits(expected), math.Float64bits(update))
}
func (v *VolatileFloat64) GetAndSet(x float64) float64 {
	return math.Float64frombits(v.v.Swap(math.Float64bits(x)))
}

// ---- Atomic array elements --------------------------------------------------------------------

// AtomicIntegerArray and AtomicLongArray are Scala arrays whose elements are only accessed
// through these functions (javalib's scalago.runtime.AtomicArrays).

func ArrayLoadInt32(a *Array[int32], i int32) int32     { return atomic.LoadInt32(&a.Data[i]) }
func ArrayStoreInt32(a *Array[int32], i int32, x int32) { atomic.StoreInt32(&a.Data[i], x) }
func ArrayCompareAndSetInt32(a *Array[int32], i int32, expected, update int32) bool {
	return atomic.CompareAndSwapInt32(&a.Data[i], expected, update)
}
func ArrayGetAndSetInt32(a *Array[int32], i int32, x int32) int32 {
	return atomic.SwapInt32(&a.Data[i], x)
}
func ArrayGetAndAddInt32(a *Array[int32], i int32, delta int32) int32 {
	return atomic.AddInt32(&a.Data[i], delta) - delta
}

func ArrayLoadInt64(a *Array[int64], i int32) int64     { return atomic.LoadInt64(&a.Data[i]) }
func ArrayStoreInt64(a *Array[int64], i int32, x int64) { atomic.StoreInt64(&a.Data[i], x) }
func ArrayCompareAndSetInt64(a *Array[int64], i int32, expected, update int64) bool {
	return atomic.CompareAndSwapInt64(&a.Data[i], expected, update)
}
func ArrayGetAndSetInt64(a *Array[int64], i int32, x int64) int64 {
	return atomic.SwapInt64(&a.Data[i], x)
}
func ArrayGetAndAddInt64(a *Array[int64], i int32, delta int64) int64 {
	return atomic.AddInt64(&a.Data[i], delta) - delta
}

// atomicRefs is an array of volatile references (AtomicReferenceArray, and the tables of
// javalib's concurrent collections). Go cannot access an interface atomically, so each element
// is an atomic pointer to a box (nil for null). Unlike Volatile, slots keep no box for reuse:
// tables are large, and their slots rarely hold the same object twice.
type atomicRefs []atomic.Pointer[any]

func refBox(x any) *any {
	if x == nil {
		return nil
	}
	return newBox(x)
}

func NewAtomicRefs(n int32) any {
	if n < 0 {
		ThrowKind(NegativeArraySize, strconv.Itoa(int(n)))
	}
	return make(atomicRefs, n)
}

func AtomicRefsLength(a any) int32 { return int32(len(a.(atomicRefs))) }

func AtomicRefsGet(a any, i int32) any {
	if p := a.(atomicRefs)[i].Load(); p != nil {
		return *p
	}
	return nil
}

func AtomicRefsSet(a any, i int32, x any) { a.(atomicRefs)[i].Store(refBox(x)) }

func AtomicRefsGetAndSet(a any, i int32, x any) any {
	if p := a.(atomicRefs)[i].Swap(refBox(x)); p != nil {
		return *p
	}
	return nil
}

func AtomicRefsCompareAndSet(a any, i int32, expected, update any) bool {
	slot := &a.(atomicRefs)[i]
	var up *any
	boxed := false
	for {
		old := slot.Load()
		var current any
		if old != nil {
			current = *old
		}
		if !sameValue(current, expected) {
			return false
		}
		if !boxed {
			up, boxed = refBox(update), true
		}
		if slot.CompareAndSwap(old, up) {
			return true
		}
	}
}
