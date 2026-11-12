// Package gen is used by the interop-generics test case: generic Go functions called through
// monomorphic facades (docs/_docs/spec/interop.md, "Generic functions").
package gen

import (
	"cmp"
	"fmt"
	"time"
)

// DurationString takes a defined numeric type of a package that no facade of the program has.
func DurationString(d time.Duration) string { return d.String() }

// Box is a generic type.
type Box[T any] struct{ Value T }

func (b *Box[T]) Get() T { return b.Value }

// Map has two type parameters, inferred from the slice and the func.
func Map[T, U any](xs []T, f func(T) U) []U {
	out := make([]U, len(xs))
	for i, x := range xs {
		out[i] = f(x)
	}
	return out
}

// First has a generic result and a second result.
func First[T any](xs []T) (T, bool) {
	var zero T
	if len(xs) == 0 {
		return zero, false
	}
	return xs[0], true
}

// Larger has a type set constraint: facades name a type of the set.
func Larger[T cmp.Ordered](a, b T) T {
	if a > b {
		return a
	}
	return b
}

// Describe has an interface constraint: facades pass the interface.
func Describe[S fmt.Stringer](s S) string { return "says " + s.String() }

// TypeOf tells the type argument that Go inferred.
func TypeOf[T any](x T) string { return fmt.Sprintf("%T", x) }

// Count counts map entries, with a comparable key.
func Count[K comparable, V any](m map[K]V) int { return len(m) }

// Ptr returns a pointer to a copy of x.
func Ptr[T any](x T) *T { return &x }

// Point is a fmt.Stringer.
type Point struct{ X, Y int }

func (p Point) String() string { return fmt.Sprintf("(%d, %d)", p.X, p.Y) }

func NewPoint(x, y int) *Point { return &Point{x, y} }
