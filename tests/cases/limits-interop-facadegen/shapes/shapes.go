// Package shapes declares one member of each Go shape that generated facades leave out
// (docs/_docs/interop/generated-facades.md, "What is left out"), next to ones they keep.
package shapes

import (
	"unsafe"

	"example.com/shapes/internal/hidden"
)

// Kept.
func Add(a, b int) int { return a + b }

// Generic: a function constrained by a type set, and a generic type (both kept).
type Number interface{ ~int | ~float64 }

func Sum[T Number](xs []T) T {
	var s T
	for _, x := range xs {
		s += x
	}
	return s
}

type Box[T any] struct{ Value T }

func NewBox(v int) *Box[int] { return &Box[int]{v} }

// Unnamed struct and interface types (struct{} is kept: Unit).
func Done() <-chan struct{} {
	c := make(chan struct{}, 1)
	c <- struct{}{}
	close(c)
	return c
}

func Point() struct{ X, Y int } { return struct{ X, Y int }{1, 2} }

func Anything() interface{ M() } { return nil }

// Arrays are kept (slices of copies), but not inside Go data; complex numbers are kept.
func Digest() [4]byte { return [4]byte{1, 2, 3, 4} }

func Digests() [][4]byte { return nil }

func Complex() complex128 { return 1 + 2i }

func Complexes() []complex64 { return nil }

// unsafe.Pointer has no Scala counterpart.
func Raw() unsafe.Pointer { return nil }

// Methods with pointer receivers of non-struct types: Scala holds IP values as copies.
type IP []byte

func (ip IP) Len() int { return len(ip) }

func (ip *IP) Clear() { *ip = nil }

// Unexported types in exported APIs, and types of internal packages: kept, as Any.
type secret struct{}

func Unexported() secret { return secret{} }

func Internal() hidden.Secret { return hidden.Secret{N: 1} }

// A field of a type Scala code cannot name is read-only.
type Holder struct{ Hidden secret }

// Funcs stored in Go data, variadic func types, returned funcs with more than 3 parameters.
type Handlers struct {
	OnEvent func(string)
}

func Callbacks() []func() { return nil }

func Variadic(f func(...int) int) int { return f(1, 2) }

func Adder4() func(a, b, c, d int) int { return func(a, b, c, d int) int { return a + b + c + d } }

// Constants beyond uint64 (those beyond Long that fit a uint64 are kept, as their bits).
const Huge = 1 << 64

const MaxUint64 = 1<<64 - 1

// Fire calls h's handler.
func Fire(h *Handlers, event string) { h.OnEvent(event) }
