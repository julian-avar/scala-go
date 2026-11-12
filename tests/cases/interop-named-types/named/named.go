// Package named is used by the interop-named-types test case: defined types of Go
// (docs/_docs/spec/interop.md, "Facade declarations" and "Named types").
package named

import (
	"fmt"
	"sort"
	"strings"
)

// Level is a defined integer type with constants and a method.
type Level int

const (
	Debug Level = iota
	Info
	Warn
)

func (l Level) String() string { return [...]string{"debug", "info", "warn"}[l] }

func (l Level) Above(other Level) bool { return l > other }

func Parse(s string) Level {
	switch s {
	case "info":
		return Info
	case "warn":
		return Warn
	}
	return Debug
}

func Describe(l Level) string { return fmt.Sprintf("%T(%d) %v", l, int(l), l) }

// Name is a defined string type.
type Name string

func (n Name) Greeting() string { return "hello, " + string(n) }

// Tags is a defined slice type.
type Tags []string

func (t Tags) Joined() string { return strings.Join(t, "|") }

// Attrs is a defined map type.
type Attrs map[string]int

func (a Attrs) Sorted() string {
	keys := make([]string, 0, len(a))
	for k := range a {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	for _, k := range keys {
		fmt.Fprintf(&b, "%s=%d ", k, a[k])
	}
	return strings.TrimSpace(b.String())
}

// Handler is a defined func type.
type Handler func(string) string

func Run(h Handler, s string) string { return h(s) }

func Upper() Handler { return strings.ToUpper }

// Msg is an interface without methods.
type Msg interface{}

func Kind(m Msg) string { return fmt.Sprintf("%T", m) }

// Config is a struct used through a top-level facade class.
type Config struct {
	Name  string
	Level Level
	Tags  Tags
}

func (c *Config) String() string {
	return fmt.Sprintf("%s at %v with %s", c.Name, c.Level, c.Tags.Joined())
}

// Stringer is an interface used through a top-level facade trait.
type Stringer interface{ String() string }

func Show(s Stringer) string { return "<" + s.String() + ">" }
