// Package greet is a local Go module used by the interop-go-module test case.
package greet

import (
	"errors"
	"fmt"
	"strings"
)

// Greeter is a struct with an exported field, an unexported one, and pointer methods.
type Greeter struct {
	Greeting string
	count    int
}

func New(greeting string) *Greeter { return &Greeter{Greeting: greeting} }

func (g *Greeter) Greet(name string) string {
	g.count++
	return fmt.Sprintf("%s, %s!", g.Greeting, name)
}

func (g *Greeter) Count() int { return g.count }

// Shape is implemented in Scala.
type Shape interface {
	Area() float64
	Name() string
}

func Describe(shapes []Shape) []string {
	out := make([]string, len(shapes))
	for i, s := range shapes {
		out[i] = fmt.Sprintf("%s: %.1f", s.Name(), s.Area())
	}
	return out
}

// Largest returns the shape with the largest area, and whether there was one.
func Largest(shapes ...Shape) (Shape, bool) {
	var best Shape
	for _, s := range shapes {
		if best == nil || s.Area() > best.Area() {
			best = s
		}
	}
	return best, best != nil
}

func Divide(a, b int) (q, r int, err error) {
	if b == 0 {
		return 0, 0, errors.New("division by zero")
	}
	return a / b, a % b, nil
}

// Scale multiplies xs in place and returns it.
func Scale(xs []int64, f func(int64) int64) []int64 {
	for i, x := range xs {
		xs[i] = f(x)
	}
	return xs
}

// Celsius is a named non-struct type with a method.
type Celsius float64

func (c Celsius) Fahrenheit() float64 { return float64(c)*9/5 + 32 }

func Boiling() Celsius { return 100 }

// Point is used by value.
type Point struct{ X, Y int }

func Origin() Point { return Point{} }

func (p Point) Dist2() int { return p.X*p.X + p.Y*p.Y }

func Move(p Point, dx, dy int) Point { return Point{p.X + dx, p.Y + dy} }

func Shout(s string) string { return strings.ToUpper(s) + "!" }

func Panic(msg string) { panic(msg) }

// Counts counts the words: a map returned to Scala.
func Counts(words []string) map[string]int {
	m := map[string]int{}
	for _, w := range words {
		m[w]++
	}
	return m
}

// Adder returns a func: Go funcs returned to Scala become Scala functions.
func Adder(n int) func(int) int { return func(x int) int { return x + n } }

// Checker returns a func whose error result becomes an exception in Scala.
func Checker() func(string) error {
	return func(s string) error {
		if s == "" {
			return errors.New("empty")
		}
		return nil
	}
}

// Squares sends the first n squares on a receive-only channel, then closes it.
func Squares(n int) <-chan int {
	c := make(chan int)
	go func() {
		for i := 1; i <= n; i++ {
			c <- i * i
		}
		close(c)
	}()
	return c
}

// Sum receives from a channel Scala made until it is closed.
func Sum(c <-chan int64) int64 {
	var total int64
	for x := range c {
		total += x
	}
	return total
}

// Increment increments through a pointer.
func Increment(p *int) { *p++ }

// Reverse reverses a slice in place.
func Reverse(xs []int) {
	for i, j := 0, len(xs)-1; i < j; i, j = i+1, j-1 {
		xs[i], xs[j] = xs[j], xs[i]
	}
}

// Total sums the map's values.
func Total(m map[string]int) int {
	t := 0
	for _, v := range m {
		t += v
	}
	return t
}
