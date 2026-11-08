// Package base has types that the other packages of the module share.
package base

// Named is anything with a name.
type Named interface {
	Name() string
}

// Unit is a unit of measure.
type Unit string

func (u Unit) Name() string { return "unit " + string(u) }
