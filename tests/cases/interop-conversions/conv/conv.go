// Package conv is used by the interop-conversions test case: values crossing between Scala and
// Go in both directions (docs/_docs/spec/interop.md, "Values across the boundary").
package conv

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

// Numbers: Go's numeric conversions apply to the Scala values passed.
func Uint8(x uint8) uint8       { return x }
func Int8(x int8) int8          { return x }
func Uint32(x uint32) uint32    { return x }
func Uint64(x uint64) uint64    { return x }
func Int(x int) int             { return x }
func Float32(x float32) float32 { return x }
func Not(b bool) bool           { return !b }

// Show tells the dynamic Go type and value of what Scala passed as any.
func Show(x any) string { return fmt.Sprintf("%T %v", x, x) }

// Bytes shows a string's UTF-8 bytes.
func Bytes(s string) string { return fmt.Sprintf("% x", []byte(s)) }

// Invalid returns a string that is not valid UTF-8.
func Invalid() string { return "a\xffb" }

// Duration converts a Scala number to a defined numeric type.
func Duration(d time.Duration) string { return d.String() }

// Celsius is a defined numeric type.
type Celsius float64

func Body() Celsius { return 37.5 }

// Point is a struct, passed by value and by pointer.
type Point struct{ X, Y int }

func NewPoint(x, y int) *Point { return &Point{x, y} }

// Shift changes its copy.
func Shift(p Point) Point { p.X += 100; return p }

// ShiftPtr changes the caller's struct.
func ShiftPtr(p *Point) { p.X += 100 }

// Origin is a variable; GetOrigin returns a copy of it, OriginPtr a pointer to it.
var Origin = Point{1, 2}

func GetOrigin() Point  { return Origin }
func OriginPtr() *Point { return &Origin }
func OriginX() int      { return Origin.X }

// Slices: shared with Array[Long], copied for other element types.
func DoubleAll(xs []int64) {
	for i := range xs {
		xs[i] *= 2
	}
}

func DoubleInts(xs []int) []int {
	for i := range xs {
		xs[i] *= 2
	}
	return xs
}

func IsNil(xs []string) bool { return xs == nil }

func Join(xs []string) string { return strings.Join(xs, "+") }

// Shared is returned to Scala, which shares its memory.
var Shared = []int64{1, 2, 3}

func GetShared() []int64 { return Shared }

func SharedSum() int64 {
	var s int64
	for _, x := range Shared {
		s += x
	}
	return s
}

// Pairs shows a map built from Scala pairs.
func Pairs(m map[string]int) string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	for _, k := range keys {
		fmt.Fprintf(&b, "%s=%d ", k, m[k])
	}
	return strings.TrimSpace(b.String())
}

// Sum is variadic.
func Sum(label string, xs ...int) string { return fmt.Sprintf("%s %d %v", label, len(xs), xs) }

// Triple has three results.
func Triple() (int, string, bool) { return 1, "two", true }

// Constants and variables.
const (
	Big        = 1 << 40
	Small      = 7
	Name       = "conv"
	Ratio      = 0.25
	Typed int8 = -3
)

var Counter int

func Bump() int { Counter++; return Counter }

// Directional channels.
func Fill(c chan<- int64, n int) {
	for i := 1; i <= n; i++ {
		c <- int64(i)
	}
	close(c)
}

func Drain(c <-chan int64) int64 {
	var s int64
	for x := range c {
		s += x
	}
	return s
}
