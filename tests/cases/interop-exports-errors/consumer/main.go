// A Go program handling the errors and panics of exported Scala code.
package main

import (
	"errors"
	"fmt"
	"io"

	lib "scalagolib"
)

var errMine = errors.New("mine")

func main() {
	s, err := lib.Read(3)
	fmt.Printf("%q %v\n", s, err)
	_, err = lib.Read(0)
	fmt.Println(err, errors.Is(err, io.EOF), err == io.EOF)
	_, err = lib.Read(-1)
	fmt.Println(err, errors.Is(err, io.EOF))

	fmt.Println(lib.Validate("ok"))
	fmt.Println(lib.Validate(""))

	fmt.Println(lib.Call(func() error { return nil }))
	_, err = lib.Call(func() error { return errMine })
	fmt.Println(err, err == errMine)
	_, err = lib.Call(func() error { return fmt.Errorf("wrapped: %w", errMine) })
	fmt.Println(err, errors.Is(err, errMine))

	p := lib.NewParser(16)
	n, err := p.Parse("ff")
	fmt.Println(n, err)
	_, err = p.Parse("zz")
	fmt.Println(err != nil, err)

	func() {
		defer func() {
			r := recover()
			e, isError := r.(error)
			fmt.Println("recovered error:", isError, e)
		}()
		lib.Explode("boom")
	}()

	func() {
		defer func() { fmt.Println("recovered:", recover()) }()
		defer fmt.Println("go deferred call runs")
		lib.ExplodeWithFinally("bang")
	}()
	fmt.Println("still running")
}
