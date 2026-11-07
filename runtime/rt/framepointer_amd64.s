#include "textflag.h"

// func framePointer() unsafe.Pointer
// Without a frame of its own, BP is still the caller's frame pointer.
TEXT ·framePointer(SB),NOSPLIT,$0-8
	MOVQ BP, ret+0(FP)
	RET
