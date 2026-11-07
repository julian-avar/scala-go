#include "textflag.h"

// func framePointer() unsafe.Pointer
// Without a frame of its own, R29 is still the caller's frame pointer.
TEXT ·framePointer(SB),NOSPLIT|NOFRAME,$0-8
	MOVD R29, ret+0(FP)
	RET
