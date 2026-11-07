//go:build !amd64 && !arm64

package rt

import "unsafe"

// Without frame pointers, stacks are captured with runtime.Callers.
const haveFramePointers = false

func framePointer() unsafe.Pointer { return nil }
