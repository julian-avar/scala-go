//go:build amd64 || arm64

package rt

import "unsafe"

// getg returns the current goroutine's runtime g (assembly: the TLS slot on amd64, the g
// register on arm64).
func getg() unsafe.Pointer
