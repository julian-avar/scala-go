// Package defined is used by the known-bugs-interop-links test case: Go data passed for defined
// slice and map types.
package defined

// Names is a defined slice type.
type Names []string

func Count(n Names) int { return len(n) }

// Table is a defined map type.
type Table map[string]int

func Size(t Table) int { return len(t) }
