// Package kit is used by the interop-facadegen test case: Go API shapes that
// interop-facadegen-elm does not use (docs/_docs/interop/generated-facades.md, "How Go types map").
package kit

import (
	"errors"
	"fmt"
	"strings"
)

// Errors as values and as failures.
var ErrClosed = errors.New("closed")

// NewError returns an error as its value.
func NewError(msg string) error { return errors.New(msg) }

// Validate's error is a failure.
func Validate(s string) error {
	if s == "" {
		return errors.New("empty input")
	}
	return nil
}

// Base is embedded in Conn: its field and method are promoted.
type Base struct{ ID int }

func (b Base) Describe() string { return fmt.Sprintf("base %d", b.ID) }

// Conn has a pointer-receiver method returning only an error, and one returning it as a value.
type Conn struct {
	Base
	Name   string
	closed bool
}

func Open(name string) (*Conn, error) {
	if name == "" {
		return nil, errors.New("no name")
	}
	return &Conn{Base: Base{ID: len(name)}, Name: name}, nil
}

func (c *Conn) Close() error {
	if c.closed {
		return ErrClosed
	}
	c.closed = true
	return nil
}

func (c *Conn) Err() error {
	if c.closed {
		return ErrClosed
	}
	return nil
}

// Variables and constants of several types.
var Default = "default"
var Count int

const (
	Pi       = 3.14
	Answer   = 42
	Greeting = "hi"
	Big      = 1 << 62
	Yes      = true
)

type Color int

const Red Color = 1

func (c Color) String() string { return [...]string{"none", "red"}[c] }

// Square implements two interfaces of the package.
type Shape interface{ Area() float64 }
type Named interface{ Name() string }

type Square struct{ S float64 }

func (s Square) Area() float64 { return s.S * s.S }
func (s Square) Name() string  { return "square" }

func NewSquare(s float64) Square { return Square{s} }

func Total(shapes ...Shape) float64 {
	t := 0.0
	for _, s := range shapes {
		t += s.Area()
	}
	return t
}

// Visitor is a func type returning only an error.
type Visitor func(name string) error

// Visit calls v for each name until it fails.
func Visit(names []string, v Visitor) (int, error) {
	for i, n := range names {
		if err := v(n); err != nil {
			return i, fmt.Errorf("visiting %s: %w", n, err)
		}
	}
	return len(names), nil
}

// Join takes a slice of strings and returns a map.
func Join(parts []string, sep string) map[string]int {
	return map[string]int{strings.Join(parts, sep): len(parts)}
}
