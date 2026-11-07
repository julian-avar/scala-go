//go:build amd64 || arm64

package rt

import "unsafe"

// Go keeps frame pointers on amd64 and arm64: stacks can be captured by following them (see
// CaptureStackTrace), as the Go execution tracer does.
const haveFramePointers = true

// framePointer returns its caller's frame pointer (framepointer_*.s).
func framePointer() unsafe.Pointer
