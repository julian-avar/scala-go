package rt

import (
	"math"
	"testing"
)

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
