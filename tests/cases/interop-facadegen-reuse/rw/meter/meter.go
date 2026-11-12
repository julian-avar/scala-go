// Package meter uses types of package text, of package base and of the standard library: its
// facades (facades.txt) reuse those of facades-textfacades.txt and of scalago-gostd.
package meter

import (
	"fmt"
	"io"
	"time"

	"example.com/rw/base"
	"example.com/rw/text"
)

// Measure reads r to its end, giving the number of bytes.
func Measure(r io.Reader) (int64, error) { return io.Copy(io.Discard, r) }

// Wrap upper-cases what r reads.
func Wrap(r io.Reader) *text.Upper { return text.NewUpper(r) }

// Of gives the unit of that name.
func Of(name string) base.Unit { return base.Unit(name) }

// Reading is a measurement.
type Reading struct {
	Value int64
	Unit  base.Unit
	Took  time.Duration
}

func (r *Reading) String() string { return fmt.Sprintf("%d %s in %v", r.Value, r.Unit, r.Took) }

// Take reads r to its end, measuring it in bytes.
func Take(r io.Reader, took time.Duration) (*Reading, error) {
	n, err := Measure(r)
	return &Reading{Value: n, Unit: "bytes", Took: took}, err
}
