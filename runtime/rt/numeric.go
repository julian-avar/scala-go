package rt

import "math"

// Floating-point to integer conversions with JVM semantics: NaN becomes 0 and out-of-range
// values saturate (Go leaves these cases implementation-defined).

func D2I(d float64) int32 {
	switch {
	case math.IsNaN(d):
		return 0
	case d >= math.MaxInt32:
		return math.MaxInt32
	case d <= math.MinInt32:
		return math.MinInt32
	}
	return int32(d)
}

func D2L(d float64) int64 {
	switch {
	case math.IsNaN(d):
		return 0
	case d >= math.MaxInt64:
		return math.MaxInt64
	case d <= math.MinInt64:
		return math.MinInt64
	}
	return int64(d)
}

// FRem implements % on floating-point numbers (Go has no % operator for floats).
func FRem(a, b float64) float64 {
	return math.Mod(a, b)
}

// And and Or are the non-short-circuiting boolean operators (both operands already evaluated).
func And(a, b bool) bool { return a && b }
func Or(a, b bool) bool  { return a || b }

// Special floating-point values, used for literals that Go constants cannot express.
func NaN() float64         { return math.NaN() }
func Inf(sign int) float64 { return math.Inf(sign) }
func NegZero() float64     { return math.Copysign(0, -1) }
