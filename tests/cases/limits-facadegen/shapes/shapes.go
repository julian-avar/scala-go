// Package shapes declares one member of each Go shape that generated facades leave out
// (docs/_docs/interop/generated-facades.md, "What is left out"), next to ones they keep.
package shapes

import "example.com/shapes/internal/hidden"

// Kept.
func Add(a, b int) int { return a + b }

// Generic: a function constrained by a type set, and a generic type.
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

// Unnamed struct and interface types.
func Done() <-chan struct{} { return nil }

func Anything() interface{ M() } { return nil }

// Arrays and complex numbers.
func Digest() [4]byte { return [4]byte{1, 2, 3, 4} }

func Complex() complex128 { return 1 + 2i }

// Unexported types in exported APIs, and types of internal packages.
type secret struct{}

func Unexported() secret { return secret{} }

func Internal() hidden.Secret { return hidden.Secret{N: 1} }

// Funcs stored in Go data, variadic func types, returned funcs with more than 3 parameters.
type Handlers struct {
	OnEvent func(string)
}

func Callbacks() []func() { return nil }

func Variadic(f func(...int) int) int { return f(1, 2) }

func Adder4() func(a, b, c, d int) int { return func(a, b, c, d int) int { return a + b + c + d } }

// Constants beyond Long.
const Huge = 1 << 64

// Fire calls h's handler.
func Fire(h *Handlers, event string) { h.OnEvent(event) }
