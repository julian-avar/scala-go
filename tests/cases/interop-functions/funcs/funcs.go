// Package funcs is used by the interop-functions test case: Scala functions passed to Go, Go
// funcs returned to Scala, and the order of evaluation and output around calls
// (docs/_docs/spec/interop.md, "Functions" and "Calls").
package funcs

import (
	"fmt"
	"strings"
)

// Op is a defined func type.
type Op func(int, int) int

func Apply(op Op, a, b int) int { return op(a, b) }

func Map(s string, f func(string) string) string {
	parts := strings.Split(s, ",")
	for i, p := range parts {
		parts[i] = f(p)
	}
	return strings.Join(parts, ",")
}

// IsNil tells whether Scala passed null.
func IsNil(f func()) bool { return f == nil }

// Go funcs returned to Scala, of 0 to 3 parameters.
func Zero() func() string                      { return func() string { return "zero" } }
func Two() func(int, int) int                  { return func(a, b int) int { return a*10 + b } }
func Three() func(a, b, c string) string       { return func(a, b, c string) string { return a + b + c } }
func Nil() func() int                          { return nil }
func Compose(f, g func(int) int) func(int) int { return func(x int) int { return g(f(x)) } }

// Identity returns the func it was given.
func Identity(f func(int) int) func(int) int { return f }

// Checked calls a func whose error result comes from a Scala exception.
func Checked(f func(int) (int, error), x int) string {
	n, err := f(x)
	if err != nil {
		return "error: " + err.Error()
	}
	return fmt.Sprint("ok: ", n)
}

// Results calls a func with two results, which Scala returns as a tuple.
func Results(f func(string) (string, int)) string {
	s, n := f("go")
	return fmt.Sprintf("%s/%d", s, n)
}

// Each calls f for each element; f prints, and so does Go.
func Each(xs []string, f func(int, string)) {
	for i, x := range xs {
		f(i, x)
		fmt.Println("go after", i)
	}
}

// Say prints its arguments, in the order Scala evaluated them.
func Say(a, b, c int) { fmt.Println("go says", a, b, c) }

// Box is a struct whose field and method are used with side effects in the receiver and arguments.
type Box struct{ N int }

func NewBox() *Box { return &Box{} }

func (b *Box) Add(x, y int) int { b.N += x*10 + y; return b.N }
