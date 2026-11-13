package rt

import "math"

// StrictMath: a port of fdlibm 5.3, whose results StrictMath's specification requires bit for
// bit (Go's math functions differ in the last bit, and math.Pow by several ulps). Math.pow uses
// it too; Math's other functions are Go's, within Math's allowance. This file has the
// exponential, logarithmic and hyperbolic functions, cbrt, hypot and pow; fdlibm_trig.go the
// trigonometric ones.
//
// Products added to or subtracted from something, directly or through a variable, are converted
// explicitly, float64(a*b): Go may otherwise fuse the two into one FMA instruction (on arm64,
// for one), which rounds once and so changes the results. Exact products (by powers of two, or
// of few significant bits) need not be.
//
// The original code, comments and constants are from fdlibm:
//
//	Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
//
//	Developed at SunSoft, a Sun Microsystems, Inc. business.
//	Permission to use, copy, modify, and distribute this
//	software is freely granted, provided that this notice
//	is preserved.

// highWord and lowWord are fdlibm's __HI and __LO: the two halves of a double's bits.
func highWord(x float64) int32 { return int32(math.Float64bits(x) >> 32) }
func lowWord(x float64) uint32 { return uint32(math.Float64bits(x)) }
func fromWords(hi int32, lo uint32) float64 {
	return math.Float64frombits(uint64(uint32(hi))<<32 | uint64(lo))
}
func withHighWord(x float64, hi int32) float64 { return fromWords(hi, lowWord(x)) }
func withLowWord(x float64, lo uint32) float64 { return fromWords(highWord(x), lo) }

const (
	fdHuge float64 = 1.0e300
	fdTiny float64 = 1.0e-300
	two54  float64 = 1.80143985094819840000e+16 // 0x43500000 00000000

	ln2Hi  float64 = 6.93147180369123816490e-01 // 0x3fe62e42 fee00000
	ln2Lo  float64 = 1.90821492927058770002e-10 // 0x3dea39ef 35793c76
	invLn2 float64 = 1.44269504088896338700e+00 // 0x3ff71547 652b82fe

	// exp's (and pow's) polynomial: P1*x^2 + ... + P5*x^10 approximates x(e^x+1)/(e^x-1) - 2.
	expP1 float64 = 1.66666666666666019037e-01  // 0x3FC55555 5555553E
	expP2 float64 = -2.77777777770155933842e-03 // 0xBF66C16C 16BEBD93
	expP3 float64 = 6.61375632143793436117e-05  // 0x3F11566A AF25DE2C
	expP4 float64 = -1.65339022054652515390e-06 // 0xBEBBBD41 C5D26BF1
	expP5 float64 = 4.13813679705723846039e-08  // 0x3E663769 72BEA4D0

	// log's (and log1p's) polynomial in s = f/(2+f): Lg1*s^2 + ... + Lg7*s^14.
	lg1 float64 = 6.666666666666735130e-01 // 3FE55555 55555593
	lg2 float64 = 3.999999999940941908e-01 // 3FD99999 9997FA04
	lg3 float64 = 2.857142874366239149e-01 // 3FD24924 94229359
	lg4 float64 = 2.222219843214978396e-01 // 3FCC71C5 1D8E78AF
	lg5 float64 = 1.818357216161805012e-01 // 3FC74664 96CB03DE
	lg6 float64 = 1.531383769920937332e-01 // 3FC39A09 D078C69F
	lg7 float64 = 1.479819860511658591e-01 // 3FC2F112 DF3E5244
)

// StrictExp is fdlibm's __ieee754_exp: x is reduced to r = x - k*ln2 with |r| <= 0.5*ln2, and
// exp(r) approximated by a rational function.
func StrictExp(x float64) float64 {
	const (
		oThreshold float64 = 7.09782712893383973096e+02  // 0x40862E42 FEFA39EF
		uThreshold float64 = -7.45133219101941108420e+02 // 0xc0874910 D52D3051
		twom1000   float64 = 9.33263618503218878990e-302 // 2**-1000
	)
	hx := highWord(x)
	negative := hx < 0
	hx &= 0x7fffffff

	// Non-finite and huge arguments.
	if hx >= 0x40862E42 { // |x| >= 709.78...
		if hx >= 0x7ff00000 {
			if hx&0xfffff|int32(lowWord(x)) != 0 {
				return x + x // NaN
			}
			if negative {
				return 0 // exp(-inf) = 0
			}
			return x
		}
		if x > oThreshold {
			return math.Inf(1)
		}
		if x < uThreshold {
			return 0
		}
	}

	// Argument reduction.
	var hi, lo float64
	var k int32
	switch {
	case hx > 0x3fd62e42: // |x| > 0.5 ln2
		if hx < 0x3FF0A2B2 { // and |x| < 1.5 ln2
			if negative {
				hi, lo, k = x+ln2Hi, -ln2Lo, -1
			} else {
				hi, lo, k = x-ln2Hi, ln2Lo, 1
			}
		} else {
			half := 0.5
			if negative {
				half = -0.5
			}
			k = int32(float64(invLn2*x) + half)
			t := float64(k)
			hi = x - float64(t*ln2Hi) // t*ln2Hi is exact here
			lo = float64(t * ln2Lo)
		}
		x = hi - lo
	case hx < 0x3e300000: // |x| < 2**-28
		return 1 + x
	}

	// x is now in the primary range.
	t := x * x
	c := x - float64(t*(expP1+float64(t*(expP2+float64(t*(expP3+float64(t*(expP4+float64(t*expP5)))))))))
	if k == 0 {
		return 1 - ((x*c)/(c-2.0) - x)
	}
	y := 1 - ((lo - (x*c)/(2.0-c)) - hi)
	if k >= -1021 {
		return withHighWord(y, highWord(y)+k<<20) // add k to y's exponent
	}
	return withHighWord(y, highWord(y)+(k+1000)<<20) * twom1000
}

// StrictLog is fdlibm's __ieee754_log: x = 2^k * (1+f) with sqrt(2)/2 < 1+f < sqrt(2), and
// log(1+f) = 2s + s*R(s^2) with s = f/(2+f).
func StrictLog(x float64) float64 {
	hx, lx := highWord(x), lowWord(x)
	var k int32
	if hx < 0x00100000 { // x < 2**-1022
		if hx&0x7fffffff|int32(lx) == 0 {
			return math.Inf(-1) // log(+-0) = -inf
		}
		if hx < 0 {
			return NaN() // log(-#) = NaN
		}
		k -= 54
		x *= two54 // subnormal: scale up x
		hx = highWord(x)
	}
	if hx >= 0x7ff00000 {
		return x + x
	}
	k += hx>>20 - 1023
	hx &= 0x000fffff
	i := (hx + 0x95f64) & 0x100000
	x = withHighWord(x, hx|(i^0x3ff00000)) // normalize x or x/2
	k += i >> 20
	f := x - 1.0
	dk := float64(k)
	if 0x000fffff&(2+hx) < 3 { // |f| < 2**-20
		if f == 0 {
			if k == 0 {
				return 0
			}
			return float64(dk*ln2Hi) + float64(dk*ln2Lo)
		}
		R := float64(f * f * (0.5 - float64(0.33333333333333333*f)))
		if k == 0 {
			return f - R
		}
		return float64(dk*ln2Hi) - ((R - float64(dk*ln2Lo)) - f)
	}
	s := f / (2.0 + f)
	z := s * s
	i = hx - 0x6147a
	w := z * z
	j := 0x6b851 - hx
	t1 := float64(w * (lg2 + float64(w*(lg4+float64(w*lg6)))))
	t2 := float64(z * (lg1 + float64(w*(lg3+float64(w*(lg5+float64(w*lg7)))))))
	i |= j
	R := t2 + t1
	if i > 0 {
		hfsq := float64(0.5 * f * f)
		if k == 0 {
			return f - (hfsq - float64(s*(hfsq+R)))
		}
		return float64(dk*ln2Hi) - ((hfsq - (float64(s*(hfsq+R)) + float64(dk*ln2Lo))) - f)
	}
	if k == 0 {
		return f - float64(s*(f-R))
	}
	return float64(dk*ln2Hi) - ((float64(s*(f-R)) - float64(dk*ln2Lo)) - f)
}

// StrictLog10 is fdlibm's __ieee754_log10: log10(2^n * x) = n*log10(2) + log(x)/ln10.
func StrictLog10(x float64) float64 {
	const (
		ivln10   float64 = 4.34294481903251816668e-01 // 0x3FDBCB7B 1526E50E
		log102Hi float64 = 3.01029995663611771306e-01 // 0x3FD34413 509F6000
		log102Lo float64 = 3.69423907715893078616e-13 // 0x3D59FEF3 11F12B36
	)
	hx, lx := highWord(x), lowWord(x)
	var k int32
	if hx < 0x00100000 { // x < 2**-1022
		if hx&0x7fffffff|int32(lx) == 0 {
			return math.Inf(-1) // log(+-0) = -inf
		}
		if hx < 0 {
			return NaN() // log(-#) = NaN
		}
		k -= 54
		x *= two54 // subnormal: scale up x
		hx = highWord(x)
	}
	if hx >= 0x7ff00000 {
		return x + x
	}
	k += hx>>20 - 1023
	i := int32(uint32(k) & 0x80000000 >> 31)
	hx = hx&0x000fffff | (0x3ff-i)<<20
	y := float64(k + i)
	x = withHighWord(x, hx)
	z := float64(y*log102Lo) + float64(ivln10*StrictLog(x))
	return z + float64(y*log102Hi)
}

// StrictLog1p is fdlibm's log1p: as log, with a correction term for the rounding of 1+x.
func StrictLog1p(x float64) float64 {
	hx := highWord(x)
	ax := hx & 0x7fffffff

	k := int32(1)
	var f, c float64
	var hu int32
	if hx < 0x3FDA827A { // x < 0.41422
		if ax >= 0x3ff00000 { // x <= -1.0
			if x == -1.0 {
				return math.Inf(-1) // log1p(-1) = -inf
			}
			return NaN() // log1p(x < -1) = NaN
		}
		if ax < 0x3e200000 { // |x| < 2**-29
			if ax < 0x3c900000 { // |x| < 2**-54
				return x
			}
			return x - float64(x*x*0.5)
		}
		if hx > 0 || hx <= -0x402d413d { // -0.2929 < x < 0.41422 (0xbfd2bec3)
			k, f, hu = 0, x, 1
		}
	}
	if hx >= 0x7ff00000 {
		return x + x
	}
	if k != 0 {
		var u float64
		if hx < 0x43400000 {
			u = 1.0 + x
			hu = highWord(u)
			k = hu>>20 - 1023
			if k > 0 { // correction term
				c = 1.0 - (u - x)
			} else {
				c = x - (u - 1.0)
			}
			c /= u
		} else {
			u = x
			hu = highWord(u)
			k = hu>>20 - 1023
			c = 0
		}
		hu &= 0x000fffff
		if hu < 0x6a09e {
			u = withHighWord(u, hu|0x3ff00000) // normalize u
		} else {
			k++
			u = withHighWord(u, hu|0x3fe00000) // normalize u/2
			hu = (0x00100000 - hu) >> 2
		}
		f = u - 1.0
	}
	hfsq := float64(0.5 * f * f)
	dk := float64(k)
	if hu == 0 { // |f| < 2**-20
		if f == 0 {
			if k == 0 {
				return 0
			}
			c += float64(dk * ln2Lo)
			return float64(dk*ln2Hi) + c
		}
		R := float64(hfsq * (1.0 - float64(0.66666666666666666*f)))
		if k == 0 {
			return f - R
		}
		return float64(dk*ln2Hi) - ((R - (float64(dk*ln2Lo) + c)) - f)
	}
	s := f / (2.0 + f)
	z := s * s
	R := float64(z * (lg1 + float64(z*(lg2+float64(z*(lg3+float64(z*(lg4+float64(z*(lg5+float64(z*(lg6+float64(z*lg7)))))))))))))
	if k == 0 {
		return f - (hfsq - float64(s*(hfsq+R)))
	}
	return float64(dk*ln2Hi) - ((hfsq - (float64(s*(hfsq+R)) + (float64(dk*ln2Lo) + c))) - f)
}

// StrictExpm1 is fdlibm's expm1: as exp, with a rational approximation precise near 0.
func StrictExpm1(x float64) float64 {
	const (
		oThreshold float64 = 7.09782712893383973096e+02 // 0x40862E42 FEFA39EF
		// Scaled coefficients.
		q1 float64 = -3.33333333333331316428e-02 // BFA11111 111110F4
		q2 float64 = 1.58730158725481460165e-03  // 3F5A01A0 19FE5585
		q3 float64 = -7.93650757867487942473e-05 // BF14CE19 9EAADBB7
		q4 float64 = 4.00821782732936239552e-06  // 3ED0CFCA 86E65239
		q5 float64 = -2.01099218183624371326e-07 // BE8AFDB7 6E09C32D
	)
	hx := highWord(x)
	negative := hx < 0
	hx &= 0x7fffffff

	// Huge and non-finite arguments.
	if hx >= 0x4043687A { // |x| >= 56*ln2
		if hx >= 0x40862E42 { // |x| >= 709.78...
			if hx >= 0x7ff00000 {
				if hx&0xfffff|int32(lowWord(x)) != 0 {
					return x + x // NaN
				}
				if negative {
					return -1 // expm1(-inf) = -1
				}
				return x
			}
			if x > oThreshold {
				return math.Inf(1)
			}
		}
		if negative { // x < -56*ln2
			return -1
		}
	}

	// Argument reduction.
	var c float64
	var k int32
	switch {
	case hx > 0x3fd62e42: // |x| > 0.5 ln2
		var hi, lo float64
		if hx < 0x3FF0A2B2 { // and |x| < 1.5 ln2
			if negative {
				hi, lo, k = x+ln2Hi, -ln2Lo, -1
			} else {
				hi, lo, k = x-ln2Hi, ln2Lo, 1
			}
		} else {
			half := 0.5
			if negative {
				half = -0.5
			}
			k = int32(float64(invLn2*x) + half)
			t := float64(k)
			hi = x - float64(t*ln2Hi) // t*ln2Hi is exact here
			lo = float64(t * ln2Lo)
		}
		x = hi - lo
		c = (hi - x) - lo
	case hx < 0x3c900000: // |x| < 2**-54
		return x
	}

	// x is now in the primary range.
	hfx := 0.5 * x
	hxs := float64(x * hfx)
	r1 := 1 + float64(hxs*(q1+float64(hxs*(q2+float64(hxs*(q3+float64(hxs*(q4+float64(hxs*q5)))))))))
	t := 3.0 - float64(r1*hfx)
	e := float64(hxs * ((r1 - t) / (6.0 - float64(x*t))))
	if k == 0 {
		return x - (float64(x*e) - hxs) // c is 0
	}
	e = float64(x*(e-c)) - c
	e -= hxs
	switch {
	case k == -1:
		return 0.5*(x-e) - 0.5
	case k == 1:
		if x < -0.25 {
			return -2.0 * (e - (x + 0.5))
		}
		return 1 + 2.0*(x-e)
	case k <= -2 || k > 56: // exp(x)-1 suffices
		y := 1 - (e - x)
		return withHighWord(y, highWord(y)+k<<20) - 1 // add k to y's exponent
	}
	var y float64
	if k < 20 {
		t = fromWords(0x3ff00000-0x200000>>k, 0) // 1-2^-k
		y = t - (e - x)
	} else {
		t = fromWords((0x3ff-k)<<20, 0) // 2^-k
		y = x - (e + t)
		y += 1
	}
	return withHighWord(y, highWord(y)+k<<20) // add k to y's exponent
}

// StrictSinh is fdlibm's __ieee754_sinh, from expm1 and exp.
func StrictSinh(x float64) float64 {
	jx := highWord(x)
	ix := jx & 0x7fffffff
	if ix >= 0x7ff00000 { // INF or NaN
		return x + x
	}
	h := 0.5
	if jx < 0 {
		h = -h
	}
	if ix < 0x40360000 { // |x| < 22: sign(x)*0.5*(E+E/(E+1))
		if ix < 0x3e300000 { // |x| < 2**-28
			return x
		}
		t := StrictExpm1(math.Abs(x))
		if ix < 0x3ff00000 {
			return h * (2.0*t - t*t/(t+1))
		}
		return h * (t + t/(t+1))
	}
	if ix < 0x40862E42 { // |x| in [22, log(maxdouble)]: 0.5*exp(|x|)
		return h * StrictExp(math.Abs(x))
	}
	if lx := lowWord(x); ix < 0x408633CE || ix == 0x408633ce && lx <= 0x8fb9f87d {
		// |x| in [log(maxdouble), overflow threshold]
		w := StrictExp(0.5 * math.Abs(x))
		t := h * w
		return t * w
	}
	return x * 1.0e307 // overflow
}

// StrictCosh is fdlibm's __ieee754_cosh, from expm1 and exp.
func StrictCosh(x float64) float64 {
	ix := highWord(x) & 0x7fffffff
	if ix >= 0x7ff00000 { // INF or NaN
		return x * x
	}
	if ix < 0x3fd62e43 { // |x| in [0, 0.5*ln2]: 1+expm1(|x|)^2/(2*exp(|x|))
		t := StrictExpm1(math.Abs(x))
		w := 1 + t
		if ix < 0x3c800000 {
			return w // cosh(tiny) = 1
		}
		return 1 + (t*t)/(w+w)
	}
	if ix < 0x40360000 { // |x| in [0.5*ln2, 22]: (exp(|x|)+1/exp(|x|))/2
		t := StrictExp(math.Abs(x))
		return 0.5*t + 0.5/t
	}
	if ix < 0x40862E42 { // |x| in [22, log(maxdouble)]: half*exp(|x|)
		return 0.5 * StrictExp(math.Abs(x))
	}
	if lx := lowWord(x); ix < 0x408633CE || ix == 0x408633ce && lx <= 0x8fb9f87d {
		// |x| in [log(maxdouble), overflow threshold]
		w := StrictExp(0.5 * math.Abs(x))
		t := 0.5 * w
		return t * w
	}
	return math.Inf(1) // overflow
}

// StrictTanh is fdlibm's tanh, from expm1.
func StrictTanh(x float64) float64 {
	jx := highWord(x)
	ix := jx & 0x7fffffff
	if ix >= 0x7ff00000 { // INF or NaN
		if jx >= 0 {
			return 1/x + 1 // tanh(+inf) = 1, tanh(NaN) = NaN
		}
		return 1/x - 1 // tanh(-inf) = -1
	}
	var z float64
	switch {
	case ix >= 0x40360000: // |x| >= 22
		z = 1
	case ix < 0x3c800000: // |x| < 2**-55
		return x * (1 + x)
	case ix >= 0x3ff00000: // |x| >= 1
		t := StrictExpm1(2 * math.Abs(x))
		z = 1 - 2/(t+2)
	default:
		t := StrictExpm1(-2 * math.Abs(x))
		z = -t / (t + 2)
	}
	if jx >= 0 {
		return z
	}
	return -z
}

// StrictCbrt is fdlibm's cbrt: a 5-bit estimate from the exponent, refined to 23 bits by a
// rational approximation, then by a Newton iteration.
func StrictCbrt(x float64) float64 {
	const (
		b1 = 715094163 // (682-0.03306235651)*2**20
		b2 = 696219795 // (664-0.03306235651)*2**20

		c float64 = 5.42857142857142815906e-01  // 19/35     = 0x3FE15F15 F15F15F1
		d float64 = -7.05306122448979611050e-01 // -864/1225 = 0xBFE691DE 2532C834
		e float64 = 1.41428571428571436819e+00  // 99/70     = 0x3FF6A0EA 0EA0EA0F
		f float64 = 1.60714285714285720630e+00  // 45/28     = 0x3FF9B6DB 6DB6DB6E
		g float64 = 3.57142857142857150787e-01  // 5/14      = 0x3FD6DB6D B6DB6DB7
	)
	hx := highWord(x)
	sign := hx & math.MinInt32
	hx ^= sign
	if hx >= 0x7ff00000 {
		return x + x // cbrt(NaN, INF) is itself
	}
	if hx|int32(lowWord(x)) == 0 {
		return x // cbrt(0) is itself
	}
	x = withHighWord(x, hx) // x <- |x|

	// Rough cbrt to 5 bits.
	var t float64
	if hx < 0x00100000 { // subnormal
		t = fromWords(0x43500000, 0) * x // 2**54 * x
		t = withHighWord(t, highWord(t)/3+b2)
	} else {
		t = fromWords(hx/3+b1, 0)
	}

	// New cbrt to 23 bits.
	r := t * t / x
	s := c + float64(r*t)
	t *= g + f/(s+e+d/s)

	// Chopped to 20 bits and made larger than cbrt(x).
	t = fromWords(highWord(t)+1, 0)

	// One Newton step to 53 bits, with an error less than 0.667 ulps.
	s = t * t // exact
	r = x / s
	w := t + t
	r = (r - t) / (w + r) // r-s is exact
	t = t + float64(t*r)

	return withHighWord(t, highWord(t)|sign)
}

// StrictHypot is fdlibm's __ieee754_hypot: sqrt(x*x+y*y) without undue overflow or underflow,
// to less than 1 ulp.
func StrictHypot(x, y float64) float64 {
	ha := highWord(x) & 0x7fffffff
	hb := highWord(y) & 0x7fffffff
	a, b := x, y
	if hb > ha {
		a, b, ha, hb = y, x, hb, ha
	}
	a = withHighWord(a, ha) // a <- |a|
	b = withHighWord(b, hb) // b <- |b|
	if ha-hb > 0x3c00000 {  // a/b > 2**60
		return a + b
	}
	k := int32(0)
	if ha > 0x5f300000 { // a > 2**500
		if ha >= 0x7ff00000 { // Inf or NaN
			w := a + b
			if ha&0xfffff|int32(lowWord(a)) == 0 {
				w = a
			}
			if hb^0x7ff00000|int32(lowWord(b)) == 0 {
				w = b
			}
			return w
		}
		// Scale a and b by 2**-600.
		ha -= 0x25800000
		hb -= 0x25800000
		k += 600
		a = withHighWord(a, ha)
		b = withHighWord(b, hb)
	}
	if hb < 0x20b00000 { // b < 2**-500
		if hb <= 0x000fffff { // subnormal b or 0
			if hb|int32(lowWord(b)) == 0 {
				return a
			}
			t1 := fromWords(0x7fd00000, 0) // 2^1022
			b *= t1
			a *= t1
			k -= 1022
		} else { // scale a and b by 2^600
			ha += 0x25800000
			hb += 0x25800000
			k -= 600
			a = withHighWord(a, ha)
			b = withHighWord(b, hb)
		}
	}
	// Medium size a and b.
	w := a - b
	if w > b {
		t1 := fromWords(ha, 0)
		t2 := a - t1
		w = math.Sqrt(float64(t1*t1) - (float64(b*(-b)) - float64(t2*(a+t1))))
	} else {
		a = a + a
		y1 := fromWords(hb, 0)
		y2 := b - y1
		t1 := fromWords(ha+0x00100000, 0)
		t2 := a - t1
		w = math.Sqrt(float64(t1*y1) - (float64(w*(-w)) - (float64(t1*y2) + float64(t2*b))))
	}
	if k != 0 {
		return fromWords(0x3ff00000+k<<20, 0) * w
	}
	return w
}

// StrictPow is fdlibm's __ieee754_pow: 2^(y*log2(x)), with log2(x) and the product computed in
// extra precision.
func StrictPow(x, y float64) float64 {
	const (
		two53 float64 = 9007199254740992.0 // 0x43400000 00000000
		// Polynomial for (3/2)*(log(x)-2s-2/3*s**3).
		l1 float64 = 5.99999999999994648725e-01 // 0x3FE33333 33333303
		l2 float64 = 4.28571428578550184252e-01 // 0x3FDB6DB6 DB6FABFF
		l3 float64 = 3.33333329818377432918e-01 // 0x3FD55555 518F264D
		l4 float64 = 2.72728123808534006489e-01 // 0x3FD17460 A91D4101
		l5 float64 = 2.30660745775561754067e-01 // 0x3FCD864A 93C9DB65
		l6 float64 = 2.06975017800338417784e-01 // 0x3FCA7E28 4A454EEF

		lg2    float64 = 6.93147180559945286227e-01  // 0x3FE62E42 FEFA39EF
		lg2H   float64 = 6.93147182464599609375e-01  // 0x3FE62E43 00000000
		lg2L   float64 = -1.90465429995776804525e-09 // 0xBE205C61 0CA86C39
		ovt    float64 = 8.0085662595372944372e-17   // -(1024-log2(ovfl+.5ulp))
		cp     float64 = 9.61796693925975554329e-01  // 0x3FEEC709 DC3A03FD = 2/(3ln2)
		cpH    float64 = 9.61796700954437255859e-01  // 0x3FEEC709 E0000000 = (float)cp
		cpL    float64 = -7.02846165095275826516e-09 // 0xBE3E2FE0 145B01F5 = tail of cpH
		ivln2H float64 = 1.44269502162933349609e+00  // 0x3FF71547 60000000 = 24b 1/ln2
		ivln2L float64 = 1.92596299112661746887e-08  // 0x3E54AE0B F85DDF44 = 1/ln2 tail
	)
	bp := [2]float64{1.0, 1.5}
	dpH := [2]float64{0.0, 5.84962487220764160156e-01} // 0x3FE2B803 40000000
	dpL := [2]float64{0.0, 1.35003920212974897128e-08} // 0x3E4CFDEB 43CFD006

	hx, lx := highWord(x), lowWord(x)
	hy, ly := highWord(y), lowWord(y)
	ix, iy := hx&0x7fffffff, hy&0x7fffffff

	// y == 0: x**0 = 1.
	if iy|int32(ly) == 0 {
		return 1
	}

	// NaN: x+y.
	if ix > 0x7ff00000 || ix == 0x7ff00000 && lx != 0 || iy > 0x7ff00000 || iy == 0x7ff00000 && ly != 0 {
		return x + y
	}

	// Whether y is an odd (1) or even (2) integer when x < 0, or not one (0).
	yIsInt := int32(0)
	if hx < 0 {
		if iy >= 0x43400000 {
			yIsInt = 2 // even integer y
		} else if iy >= 0x3ff00000 {
			k := iy>>20 - 0x3ff // exponent
			if k > 20 {
				j := ly >> (52 - k)
				if j<<(52-k) == ly {
					yIsInt = 2 - int32(j&1)
				}
			} else if ly == 0 {
				j := iy >> (20 - k)
				if j<<(20-k) == iy {
					yIsInt = 2 - j&1
				}
			}
		}
	}

	// Special values of y.
	if ly == 0 {
		if iy == 0x7ff00000 { // y is +-inf
			switch {
			case (ix-0x3ff00000)|int32(lx) == 0:
				return y - y // (+-1)**+-inf is NaN
			case ix >= 0x3ff00000: // (|x|>1)**+-inf = inf, 0
				if hy >= 0 {
					return y
				}
				return 0
			default: // (|x|<1)**-,+inf = inf, 0
				if hy < 0 {
					return -y
				}
				return 0
			}
		}
		if iy == 0x3ff00000 { // y is +-1
			if hy < 0 {
				return 1 / x
			}
			return x
		}
		if hy == 0x40000000 { // y is 2
			return x * x
		}
		if hy == 0x3fe00000 && hx >= 0 { // y is 0.5, x >= +0
			return math.Sqrt(x)
		}
	}

	ax := math.Abs(x)
	// Special values of x.
	if lx == 0 && (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000) { // +-0, +-inf, +-1
		z := ax
		if hy < 0 {
			z = 1 / z // 1/|x|
		}
		if hx < 0 {
			if (ix-0x3ff00000)|yIsInt == 0 {
				z = NaN() // (-1)**non-int is NaN
			} else if yIsInt == 1 {
				z = -z // (x<0)**odd = -(|x|**odd)
			}
		}
		return z
	}

	n := hx>>31 + 1
	// (x<0)**(non-int) is NaN.
	if n|yIsInt == 0 {
		return NaN()
	}
	s := 1.0 // sign of the result
	if n|(yIsInt-1) == 0 {
		s = -1 // (-ve)**(odd int)
	}

	var t1, t2 float64
	if iy > 0x41e00000 { // |y| > 2**31
		if iy > 0x43f00000 { // |y| > 2**64: must overflow or underflow
			if ix <= 0x3fefffff {
				if hy < 0 {
					return math.Inf(1)
				}
				return 0
			}
			if ix >= 0x3ff00000 {
				if hy > 0 {
					return math.Inf(1)
				}
				return 0
			}
		}
		// Overflow or underflow if x is not close to one.
		if ix < 0x3fefffff {
			if hy < 0 {
				return s * math.Inf(1)
			}
			return s * 0
		}
		if ix > 0x3ff00000 {
			if hy > 0 {
				return s * math.Inf(1)
			}
			return s * 0
		}
		// Now |1-x| is tiny <= 2**-20: log(x) = x-x^2/2+x^3/3-x^4/4 suffices.
		t := ax - 1 // t has 20 trailing zeros
		w := (t * t) * (0.5 - float64(t*(0.3333333333333333333333-float64(t*0.25))))
		u := float64(ivln2H * t) // ivln2H has 21 significant bits
		v := float64(t*ivln2L) - float64(w*invLn2)
		t1 = withLowWord(u+v, 0)
		t2 = v - (t1 - u)
	} else {
		n = 0
		// Subnormal x.
		if ix < 0x00100000 {
			ax *= two53
			n -= 53
			ix = highWord(ax)
		}
		n += ix>>20 - 0x3ff
		j := ix & 0x000fffff
		// The interval.
		ix = j | 0x3ff00000 // normalize ix
		var k int32
		switch {
		case j <= 0x3988E: // |x| < sqrt(3/2)
			k = 0
		case j < 0xBB67A: // |x| < sqrt(3)
			k = 1
		default:
			k = 0
			n++
			ix -= 0x00100000
		}
		ax = withHighWord(ax, ix)

		// ss = sH+sL = (x-1)/(x+1) or (x-1.5)/(x+1.5).
		u := ax - bp[k]
		v := 1 / (ax + bp[k])
		ss := float64(u * v)
		sH := withLowWord(ss, 0)
		// tH = ax+bp[k], high.
		tH := fromWords((ix>>1|0x20000000)+0x00080000+k<<18, 0)
		tL := ax - (tH - bp[k])
		sL := v * ((u - float64(sH*tH)) - float64(sH*tL))
		// log(ax).
		s2 := ss * ss
		r := float64(s2 * s2 * (l1 + float64(s2*(l2+float64(s2*(l3+float64(s2*(l4+float64(s2*(l5+float64(s2*l6)))))))))))
		r += float64(sL * (sH + ss))
		s2 = float64(sH * sH)
		tH = withLowWord(3.0+s2+r, 0)
		tL = r - ((tH - 3.0) - s2)
		// u+v = ss*(1+...).
		u = float64(sH * tH)
		v = float64(sL*tH) + float64(tL*ss)
		// 2/(3log2)*(ss+...).
		pH := withLowWord(u+v, 0)
		pL := v - (pH - u)
		zH := float64(cpH * pH) // cpH+cpL = 2/(3*log2)
		zL := float64(cpL*pH) + float64(pL*cp) + dpL[k]
		// log2(ax) = (ss+..)*2/(3*log2) = n + dpH + zH + zL.
		t := float64(n)
		t1 = withLowWord(((zH+zL)+dpH[k])+t, 0)
		t2 = zL - (((t1 - t) - dpH[k]) - zH)
	}

	// Split y into y1+y2 and compute (y1+y2)*(t1+t2).
	y1 := withLowWord(y, 0)
	pL := float64((y-y1)*t1) + float64(y*t2)
	pH := float64(y1 * t1)
	z := pL + pH
	j := highWord(z)
	i := int32(lowWord(z))
	if j >= 0x40900000 { // z >= 1024
		if (j-0x40900000)|i != 0 || pL+ovt > z-pH { // z > 1024
			return s * math.Inf(1) // overflow
		}
	} else if j&0x7fffffff >= 0x4090cc00 { // z <= -1075
		if (uint32(j)-0xc090cc00)|uint32(i) != 0 || pL <= z-pH { // z < -1075
			return s * 0 // underflow
		}
	}

	// 2**(pH+pL).
	i = j & 0x7fffffff
	k := i>>20 - 0x3ff
	n = 0
	if i > 0x3fe00000 { // |z| > 0.5: n = [z+0.5]
		n = j + 0x00100000>>(k+1)
		k = (n&0x7fffffff)>>20 - 0x3ff // new k for n
		t := fromWords(n&^(0x000fffff>>k), 0)
		n = (n&0x000fffff | 0x00100000) >> (20 - k)
		if j < 0 {
			n = -n
		}
		pH -= t
	}
	t := withLowWord(pL+pH, 0)
	u := float64(t * lg2H)
	v := float64((pL-(t-pH))*lg2) + float64(t*lg2L)
	z = u + v
	w := v - (z - u)
	t = z * z
	t1 = z - float64(t*(expP1+float64(t*(expP2+float64(t*(expP3+float64(t*(expP4+float64(t*expP5)))))))))
	r := (z*t1)/(t1-2) - (w + float64(z*w))
	z = 1 - (r - z)
	j = highWord(z) + n<<20
	if j>>20 <= 0 {
		z = math.Ldexp(z, int(n)) // subnormal output
	} else {
		z = withHighWord(z, j)
	}
	return s * z
}
