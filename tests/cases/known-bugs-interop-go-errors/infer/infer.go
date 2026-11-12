// Package infer is used by the known-bugs-interop-go-errors test case.
package infer

// Map's type parameter T appears in a slice and a func type.
func Map[T, U any](xs []T, f func(T) U) []U {
	out := make([]U, len(xs))
	for i, x := range xs {
		out[i] = f(x)
	}
	return out
}
