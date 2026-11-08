// Package elm is bubbletea's architecture in miniature, for the go-facadegen test case: Go APIs
// in the style of popular Go libraries, used from Scala through generated facades.
package elm

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"example.com/elm/style"
)

// Msg is any message: an interface without methods.
type Msg interface{}

// Cmd is a named func type.
type Cmd func() Msg

// Model is implemented in Scala; Update has several results.
type Model interface {
	Init() Cmd
	Update(Msg) (Model, Cmd)
	View() string
}

// KeyMsg is a struct passed by value in a Msg, which code receiving it tests for.
type KeyMsg struct {
	Key string
	Alt bool
}

func (k KeyMsg) String() string {
	if k.Alt {
		return "alt+" + k.Key
	}
	return k.Key
}

type QuitMsg struct{}

func Quit() Msg { return QuitMsg{} }

type BatchMsg []Cmd

// Batch combines commands, leaving out nil ones.
func Batch(cmds ...Cmd) Cmd {
	var valid []Cmd
	for _, c := range cmds {
		if c != nil {
			valid = append(valid, c)
		}
	}
	if len(valid) == 0 {
		return nil
	}
	return func() Msg { return BatchMsg(valid) }
}

// Run gives the model its init command's message, then the keys, until a QuitMsg. The messages
// of the commands that Update returns come before the remaining keys.
func Run(m Model, keys ...string) (Model, error) {
	var queue []Msg
	run := func(c Cmd) {
		if c != nil {
			queue = append([]Msg{c()}, queue...)
		}
	}
	run(m.Init())
	for _, k := range keys {
		queue = append(queue, KeyMsg{Key: k})
	}
	for len(queue) > 0 {
		msg := queue[0]
		queue = queue[1:]
		switch msg := msg.(type) {
		case QuitMsg:
			return m, nil
		case BatchMsg:
			for i := len(msg) - 1; i >= 0; i-- {
				run(msg[i])
			}
			continue
		}
		var cmd Cmd
		m, cmd = m.Update(msg)
		run(cmd)
	}
	return m, errors.New("the model did not quit")
}

// Counter is a Go component, like bubbles' text input: Update has a value receiver and returns
// the updated copy; Reset has a pointer receiver.
type Counter struct {
	N     int
	Label string
}

func (c Counter) Update(msg Msg) (Counter, Cmd) {
	if k, ok := msg.(KeyMsg); ok && k.Key == "+" {
		c.N++
	}
	return c, nil
}

func (c Counter) View() string { return fmt.Sprintf("%s=%d", c.Label, c.N) }

func (c *Counter) Reset() { c.N = 0 }

// Option is a named func type taking a pointer, like spinner.Option.
type Option func(*Counter)

func WithStart(n int) Option { return func(c *Counter) { c.N = n } }

func NewCounter(label string, opts ...Option) Counter {
	c := Counter{Label: label}
	for _, o := range opts {
		o(&c)
	}
	return c
}

// Describe tells how a value arrived: Go type switches tell values from pointers.
func Describe(v Msg) string {
	switch v := v.(type) {
	case nil:
		return "nil"
	case KeyMsg:
		return "KeyMsg value " + v.String()
	case *KeyMsg:
		return "KeyMsg pointer"
	case Counter:
		return "Counter value"
	case *Counter:
		return "Counter pointer " + v.View()
	case string:
		return "string " + v
	}
	return fmt.Sprintf("%T", v)
}

// Color is a named basic type, like lipgloss.Color.
type Color string

func (c Color) Hex() string { return strings.ToUpper(strings.TrimPrefix(string(c), "#")) }

// Paint takes a fmt.Stringer, which Go structs held by Scala implement by value.
func Paint(c Color, s fmt.Stringer) string { return "[" + c.Hex() + "]" + s.String() }

// Matches is generic, with an interface constraint, like bubbles' key.Matches.
func Matches[K fmt.Stringer](k K, keys ...string) bool {
	for _, key := range keys {
		if k.String() == key {
			return true
		}
	}
	return false
}

// Every calls f with a fixed time (types of another standard package in the API).
func Every(d time.Duration, f func(time.Time) Msg) Cmd {
	return func() Msg { return f(time.Date(2024, 1, 2, 3, 4, 5, 0, time.UTC).Add(d)) }
}

// DefaultStyle has the type of another package of the module.
var DefaultStyle = style.Style{Upper: true, Prefix: "> "}

// ErrDone ends a Source.
var ErrDone = errors.New("done")

// Source is implemented in Scala: its error result comes from a Scala exception.
type Source interface {
	Next() (string, error)
}

func Drain(s Source) (string, error) {
	var parts []string
	for {
		p, err := s.Next()
		if errors.Is(err, ErrDone) {
			return strings.Join(parts, ","), nil
		}
		if err != nil {
			return "", err
		}
		parts = append(parts, p)
	}
}

// Pairs is implemented in Scala, with several results and no error.
type Pairs interface {
	Pair(i int) (string, int)
}

func Collect(p Pairs, n int) string {
	var sb strings.Builder
	for i := 0; i < n; i++ {
		k, v := p.Pair(i)
		fmt.Fprintf(&sb, "%s:%d ", k, v)
	}
	return strings.TrimSpace(sb.String())
}

// Sealed has only unexported methods: it is not an interface without methods, and Scala code
// can only pass Go's implementations.
type Sealed interface{ sealed() }

type mark struct{ name string }

func (mark) sealed() {}

func Mark(name string) Sealed { return mark{name} }

func Name(s Sealed) string { return s.(mark).name }
