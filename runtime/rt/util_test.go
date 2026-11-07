package rt

import (
	"math"
	"testing"
)

func TestStringFormatFloatingRoundsLikeJava(t *testing.T) {
	cases := []struct {
		format string
		arg    any
		want   string
	}{
		{"%.2f", 0.125, "0.13"}, // half up on the decimal digits (Go: 0.12)
		{"%.0f", 2.5, "3"},
		{"%f", 1099511627774.0 / 3, "366503875924.666700"}, // Double.toString's digits, then zeros
		{"%.1f", 0.05, "0.1"},
		{"%.2f", 0.0004, "0.00"},
		{"%.2f", 0.005, "0.01"},
		{"%.2f", 99.996, "100.00"},
		{"%08.2f", -3.14159, "-0003.14"},
		{"%+.1f", 2.0, "+2.0"},
		{"%-7.1f|", 2.0, "2.0    |"},
		{"%(.1f", -2.0, "(2.0)"},
		{"%f", 0.0, "0.000000"},
		{"%f", math.Copysign(0, -1), "-0.000000"},
		{"%f", math.NaN(), "NaN"},
		{"%f", math.Inf(-1), "-Infinity"},
		{"%e", 12345.678, "1.234568e+04"},
		{"%.3e", 0.0, "0.000e+00"},
		{"%.1e", 9.96, "1.0e+01"},
		{"%.2e", 1.5e-7, "1.50e-07"},
		{"%.1f", float32(0.15), "0.2"}, // Float.toString(0.15f) is 0.15
	}
	for _, c := range cases {
		got := StringFormat(Str(c.format), ArrayOf[any](c.arg)).GoString()
		if got != c.want {
			t.Errorf("String.format(%q, %v) = %q, want %q", c.format, c.arg, got, c.want)
		}
	}
}

func TestArraysBinarySearchProbesLikeTheJDK(t *testing.T) {
	a := ArrayOf[int32](1, 2, 2, 2, 2, 2, 3)
	// The JDK's first probe is index 3.
	if got := ArraysBinarySearch(a, 0, 7, int32(2)); got != 3 {
		t.Errorf("binarySearch found %d, want 3", got)
	}
	if got := ArraysBinarySearch(a, 0, 7, int32(4)); got != -8 {
		t.Errorf("binarySearch(4) = %d, want -8", got)
	}
	d := ArrayOf(-1.0, math.Copysign(0, -1), 0.0, math.NaN())
	if got := ArraysBinarySearch(d, 0, 4, 0.0); got != 2 {
		t.Errorf("binarySearch(0.0) = %d, want 2", got)
	}
	if got := ArraysBinarySearch(d, 0, 4, math.NaN()); got != 3 {
		t.Errorf("binarySearch(NaN) = %d, want 3", got)
	}
}

func TestArraysEqualsAndHashCodeOfFloats(t *testing.T) {
	nan := ArrayOf(math.NaN())
	if !ArraysEquals(nan, ArrayOf(math.NaN())) {
		t.Error("[NaN] should equal [NaN], as in Arrays.equals")
	}
	if ArraysEquals(ArrayOf(0.0), ArrayOf(math.Copysign(0, -1))) {
		t.Error("[0.0] should not equal [-0.0]")
	}
	if ArraysEquals(ArrayOf[float32](0), ArrayOf(float32(math.Copysign(0, -1)))) {
		t.Error("[0.0f] should not equal [-0.0f]")
	}
	// Arrays.hashCode(new double[]{Double.NaN}) == 31 + Double.hashCode(NaN) == 31 + 2146959360
	if got := ArraysHashCode(nan); got != 31+2146959360 {
		t.Errorf("hashCode([NaN]) = %d", got)
	}
}

func TestArraysToStringOfChars(t *testing.T) {
	if got := ArraysToString(ArrayOf[uint16]('a', 0xd800, 'b')); !StringEquals(got, newString([]uint16{'[', 'a', ',', ' ', 0xd800, ',', ' ', 'b', ']'})) {
		t.Errorf("toString(char[]) = %q", got.GoString())
	}
}
