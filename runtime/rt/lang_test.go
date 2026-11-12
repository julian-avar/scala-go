package rt

import (
	"testing"
	"unicode"
)

// decimalDigitValue relies on each range of unicode.Nd being whole runs of ten digits.
func TestDecimalDigitRanges(t *testing.T) {
	for _, r := range unicode.Nd.R16 {
		if r.Stride != 1 || (r.Hi-r.Lo+1)%10 != 0 {
			t.Errorf("Nd range %#x-%#x (stride %d) is not whole runs of ten", r.Lo, r.Hi, r.Stride)
		}
	}
	for _, r := range unicode.Nd.R32 {
		if r.Stride != 1 || (r.Hi-r.Lo+1)%10 != 0 {
			t.Errorf("Nd range %#x-%#x (stride %d) is not whole runs of ten", r.Lo, r.Hi, r.Stride)
		}
	}
	for c, want := range map[int32]int32{'7': 7, 0x669: 9, 0xff10: 0, 0x1d7d9: 1, 0x1d7e3: 1, 0xff5a: 35, 0x2160: -1} {
		if got := CharDigit(c, 36); got != want {
			t.Errorf("CharDigit(%#x, 36) = %d, want %d", c, got, want)
		}
	}
}
