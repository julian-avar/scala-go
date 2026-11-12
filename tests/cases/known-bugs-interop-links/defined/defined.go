// Package defined is used by the known-bugs-interop-links test case: Go data passed for defined
// slice and map types, and a number for a defined type of a package without facades.
package defined

import "time"

// Names is a defined slice type.
type Names []string

func Count(n Names) int { return len(n) }

// Table is a defined map type.
type Table map[string]int

func Size(t Table) int { return len(t) }

// Duration takes a defined numeric type of another package.
func Duration(d time.Duration) string { return d.String() }
