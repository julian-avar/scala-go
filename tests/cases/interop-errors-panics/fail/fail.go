// Package fail is used by the interop-errors-panics test case: Go errors and panics in Scala, and
// Scala exceptions in Go (docs/_docs/spec/interop.md, "Errors and panics").
package fail

import (
	"errors"
	"fmt"
)

var ErrBase = errors.New("base failure")

// MyError is an error type with a pointer receiver.
type MyError struct{ Code int }

func (e *MyError) Error() string { return fmt.Sprintf("my error %d", e.Code) }

// Parse returns a value and an error.
func Parse(s string) (int, error) {
	if s == "" {
		return 0, &MyError{Code: 400}
	}
	return len(s), nil
}

// Check returns only an error.
func Check(ok bool) error {
	if ok {
		return nil
	}
	return fmt.Errorf("check: %w", ErrBase)
}

// NilMyError returns an error holding a nil *MyError: not a nil error.
func NilMyError() error {
	var e *MyError
	return e
}

func Is(err error) bool { return errors.Is(err, ErrBase) }

func Code(err error) int {
	var m *MyError
	if errors.As(err, &m) {
		return m.Code
	}
	return -1
}

func Type(err error) string { return fmt.Sprintf("%T", err) }

// Panics of several kinds.
func Panic(v any) { panic(v) }

func Index(xs []int, i int) int { return xs[i] }

type Point struct{ X int }

func NilX() int {
	var p *Point
	return p.X
}

func Div(a, b int) int { return a / b }

func Assert(v any) string { return v.(string) }

// Deferred runs f and prints when its deferred call runs.
func Deferred(f func()) {
	defer fmt.Println("go: deferred call runs")
	f()
}

// Recover calls f and recovers what it panics with.
func Recover(f func()) (result string) {
	defer func() {
		if r := recover(); r != nil {
			err, isError := r.(error)
			if isError {
				result = fmt.Sprintf("recovered error %q", err.Error())
			} else {
				result = fmt.Sprintf("recovered %v", r)
			}
		}
	}()
	f()
	return "no panic"
}

// CallErr calls f, whose error result comes from a Scala exception.
func CallErr(f func() error) string {
	err := f()
	if err == nil {
		return "nil error"
	}
	return fmt.Sprintf("%q is base: %v", err.Error(), errors.Is(err, ErrBase))
}
