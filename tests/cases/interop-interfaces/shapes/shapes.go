// Package shapes is used by the interop-interfaces test case: values passed to Go interface
// types, and Go interfaces implemented in Scala (docs/_docs/spec/interop.md, "Interfaces" and
// "Go interfaces implemented in Scala").
package shapes

import (
	"errors"
	"fmt"
	"strings"
)

type Shape interface{ Area() float64 }

// Square has a value receiver: it goes to interfaces by value.
type Square struct{ S float64 }

func (s Square) Area() float64 { return s.S * s.S }

func NewSquare(s float64) *Square { return &Square{s} }

// Circle has a pointer receiver: it goes to interfaces as a pointer.
type Circle struct{ R float64 }

func (c *Circle) Area() float64 { return 3 * c.R * c.R }

func NewCircle(r float64) *Circle { return &Circle{r} }

// Tag has no methods: it goes to any by value.
type Tag struct{ Name string }

func NewTag(n string) *Tag { return &Tag{n} }

func Kind(s Shape) string { return kind(s) }

func KindAny(v any) string { return kind(v) }

func kind(v any) string {
	switch v := v.(type) {
	case nil:
		return "nil"
	case Square:
		return fmt.Sprintf("Square value %.0f", v.Area())
	case *Square:
		return "Square pointer"
	case Circle:
		return "Circle value"
	case *Circle:
		return fmt.Sprintf("Circle pointer %.0f", v.Area())
	case Tag:
		return "Tag value " + v.Name
	case *Tag:
		return "Tag pointer"
	case string:
		return "string " + v
	case Shape:
		return fmt.Sprintf("another Shape %.0f", v.Area())
	}
	return "other"
}

// Same compares interface values with Go's ==.
func Same(a, b any) bool { return a == b }

// Keep returns what it was given.
func Keep(v any) any { return v }

var kept []Shape

// Store keeps a shape for later, and Stored gives it back.
func Store(s Shape)      { kept = append(kept, s) }
func Stored(i int) Shape { return kept[i] }

// Total calls the Scala implementations.
func Total(shapes ...Shape) float64 {
	t := 0.0
	for _, s := range shapes {
		t += s.Area()
	}
	return t
}

// Named is implemented in Scala; Rename has several results and an error.
type Named interface {
	Name() string
	Rename(to string) (old string, n int, err error)
}

func Describe(n Named) string { return "named " + n.Name() }

func RenameAll(to string, ns ...Named) string {
	var out []string
	for _, n := range ns {
		old, count, err := n.Rename(to)
		if err != nil {
			out = append(out, "error: "+err.Error())
			continue
		}
		out = append(out, fmt.Sprintf("%s->%s (%d)", old, n.Name(), count))
	}
	return strings.Join(out, "; ")
}

// Validator's method returns only an error.
type Validator interface{ Validate(s string) error }

var ErrEmpty = errors.New("empty")

func Validate(v Validator, inputs ...string) string {
	var out []string
	for _, s := range inputs {
		if err := v.Validate(s); err != nil {
			out = append(out, fmt.Sprintf("%q: %s (empty: %v)", s, err, errors.Is(err, ErrEmpty)))
		} else {
			out = append(out, fmt.Sprintf("%q: ok", s))
		}
	}
	return strings.Join(out, "; ")
}

// Labeled embeds Named.
type Labeled interface {
	Named
	Label() string
}

func Show(l Labeled) string { return l.Label() + ":" + l.Name() }

// Implements tells whether v implements the interfaces.
func Implements(v any) string {
	_, shape := v.(Shape)
	_, named := v.(Named)
	_, stringer := v.(fmt.Stringer)
	return fmt.Sprintf("Shape %v, Named %v, Stringer %v", shape, named, stringer)
}
