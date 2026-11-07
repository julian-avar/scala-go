package rt

import (
	"testing"
	"unicode"
)

// The end-to-end case tests/cases/bigdecimal compares BigDecimal with the JVM's; these tests
// cover the runtime's own assumptions.

// unicodeDigit relies on decimal digits coming in runs of ten, from zero to nine.
func TestDecimalDigitRuns(t *testing.T) {
	for _, r := range unicode.Nd.R16 {
		if r.Stride != 1 || (r.Hi-r.Lo+1)%10 != 0 {
			t.Errorf("Nd range %#x-%#x (stride %d) is not made of runs of ten", r.Lo, r.Hi, r.Stride)
		}
	}
	for c, want := range map[uint16]int{'7': 7, 0x0663: 3, 0xff19: 9, 0x0be6: 0, 'x': -1, 0x00b2: -1} {
		if got := unicodeDigit(c); got != want {
			t.Errorf("unicodeDigit(%#x) = %d, want %d", c, got, want)
		}
	}
}

func TestBigDecimalLayout(t *testing.T) {
	for _, c := range []struct{ in, str, eng, plain string }{
		{"0E-8", "0E-8", "0.00E-6", "0.00000000"},
		{"-1.2E-7", "-1.2E-7", "-120E-9", "-0.00000012"},
		{"12E+3", "1.2E+4", "12E+3", "12000"},
		{"123456789012345678901234.5", "123456789012345678901234.5", "123456789012345678901234.5",
			"123456789012345678901234.5"},
	} {
		d := NewBigDecimalFromString(Str(c.in))
		got := []string{BigDecimalToString(d).GoString(), BigDecimalToEngineeringString(d).GoString(),
			BigDecimalToPlainString(d).GoString()}
		if got[0] != c.str || got[1] != c.eng || got[2] != c.plain {
			t.Errorf("%s: got %v", c.in, got)
		}
	}
}

func TestBigDecimalRounding(t *testing.T) {
	want := map[int32]string{RoundUp: "-3", RoundDown: "-2", RoundCeiling: "-2", RoundFloor: "-3",
		RoundHalfUp: "-3", RoundHalfDown: "-2", RoundHalfEven: "-2"}
	for mode, w := range want {
		if got := BigDecimalSetScale(NewBigDecimalFromString(Str("-2.5")), 0, mode).String(); got != w {
			t.Errorf("mode %d: got %s, want %s", mode, got, w)
		}
	}
	if got := BigDecimalDivideMC(BigDecimalOne, NewBigDecimalFromLong(3), decimal128).String(); got != "0.3333333333333333333333333333333333" {
		t.Errorf("1/3 = %s", got)
	}
	// An exact quotient gets the preferred scale.
	if got := BigDecimalDivideMC(NewBigDecimalFromString(Str("1.00")), NewBigDecimalFromLong(4), decimal128).String(); got != "0.25" {
		t.Errorf("1.00/4 = %s", got)
	}
}
