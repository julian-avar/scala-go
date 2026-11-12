// Package vals is used by the interop-go-values test case: Go values held by Scala code
// (docs/_docs/spec/interop.md, "Go values in Scala code").
package vals

import "errors"

// Pair is a comparable struct without methods.
type Pair struct{ A, B int }

func NewPair(a, b int) *Pair { return &Pair{a, b} }

// PairValue returns a Pair by value in an interface.
func PairValue(a, b int) any { return Pair{a, b} }

// Named has a String method.
type Named struct{ N string }

func (n Named) String() string { return "named " + n.N }

func NewNamed(n string) *Named { return &Named{n} }

// Level is a defined integer type.
type Level int

func (l Level) String() string { return [...]string{"debug", "info", "warn"}[l] }

func AnyLevel(l int) any { return Level(l) }

// Values of Go's predeclared types in interfaces.
func AnyInt() any     { return 7 }
func AnyInt32() any   { return int32(7) }
func AnyInt64() any   { return int64(7) }
func AnyUint8() any   { return uint8(7) }
func AnyFloat64() any { return 2.5 }
func AnyBool() any    { return true }
func AnyString() any  { return "go" }
func AnyError() any   { return errors.New("an error") }
func AnyNil() any     { return nil }

// Slices and maps compare by identity.
var Slice = []int{1, 2}
var Map = map[string]int{"a": 1}

func AnySlice() any   { return Slice }
func AnyMap() any     { return Map }
func OtherSlice() any { return []int{1, 2} }

// Tagged has a slice field, so Go cannot compare it; Boxed can be compared, but panics if its
// field holds a slice.
type Tagged struct {
	Name string
	Tags []string
}
type Boxed struct{ V any }

var tags = []string{"a"}

func AnyTagged() any       { return Tagged{"t", tags} }
func OtherTagged() any     { return Tagged{"t", []string{"a"}} }
func AnyBoxedSlice() any   { return Boxed{Slice} }
func OtherBoxedSlice() any { return Boxed{[]int{1, 2}} }
