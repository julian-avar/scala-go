//go:build !amd64 && !arm64

package rt

import "unsafe"

// getg is not available on this architecture: GoID uses the slow path.
func getg() unsafe.Pointer { return nil }
