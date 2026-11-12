package rt

import "math"

// StrictMath's trigonometric functions: fdlibm's, as the rest of fdlibm.go (whose notes apply).

// StrictSin is fdlibm's sin: the argument is reduced to [-pi/4, pi/4], then a kernel applies.
func StrictSin(x float64) float64 {
	ix := highWord(x) & 0x7fffffff
	switch {
	case ix <= 0x3fe921fb: // |x| ~< pi/4
		return kernelSin(x, 0, false)
	case ix >= 0x7ff00000: // sin(Inf or NaN) is NaN
		return x - x
	}
	n, y0, y1 := remPio2(x)
	switch n & 3 {
	case 0:
		return kernelSin(y0, y1, true)
	case 1:
		return kernelCos(y0, y1)
	case 2:
		return -kernelSin(y0, y1, true)
	default:
		return -kernelCos(y0, y1)
	}
}

// StrictCos is fdlibm's cos.
func StrictCos(x float64) float64 {
	ix := highWord(x) & 0x7fffffff
	switch {
	case ix <= 0x3fe921fb: // |x| ~< pi/4
		return kernelCos(x, 0)
	case ix >= 0x7ff00000: // cos(Inf or NaN) is NaN
		return x - x
	}
	n, y0, y1 := remPio2(x)
	switch n & 3 {
	case 0:
		return kernelCos(y0, y1)
	case 1:
		return -kernelSin(y0, y1, true)
	case 2:
		return -kernelCos(y0, y1)
	default:
		return kernelSin(y0, y1, true)
	}
}

// StrictTan is fdlibm's tan.
func StrictTan(x float64) float64 {
	ix := highWord(x) & 0x7fffffff
	switch {
	case ix <= 0x3fe921fb: // |x| ~< pi/4
		return kernelTan(x, 0, 1)
	case ix >= 0x7ff00000: // tan(Inf or NaN) is NaN
		return x - x
	}
	n, y0, y1 := remPio2(x)
	return kernelTan(y0, y1, 1-(n&1)<<1) // 1 if n is even, -1 if odd
}

// kernelSin is fdlibm's __kernel_sin: sin(x+y) on [-pi/4, pi/4], where y is the tail of x (if
// hasTail), by a polynomial of degree 13.
func kernelSin(x, y float64, hasTail bool) float64 {
	const (
		s1 float64 = -1.66666666666666324348e-01 // 0xBFC55555 55555549
		s2 float64 = 8.33333333332248946124e-03  // 0x3F811111 1110F8A6
		s3 float64 = -1.98412698298579493134e-04 // 0xBF2A01A0 19C161D5
		s4 float64 = 2.75573137070700676789e-06  // 0x3EC71DE3 57B1FE7D
		s5 float64 = -2.50507602534068634195e-08 // 0xBE5AE5E6 8A2B9CEB
		s6 float64 = 1.58969099521155010221e-10  // 0x3DE5D93A 5ACFD57C
	)
	if highWord(x)&0x7fffffff < 0x3e400000 && int32(x) == 0 { // |x| < 2**-27
		return x
	}
	z := x * x
	v := z * x
	r := s2 + float64(z*(s3+float64(z*(s4+float64(z*(s5+float64(z*s6)))))))
	if !hasTail {
		return x + float64(v*(s1+float64(z*r)))
	}
	return x - ((float64(z*(0.5*y-float64(v*r))) - y) - float64(v*s1))
}

// kernelCos is fdlibm's __kernel_cos: cos(x+y) on [-pi/4, pi/4], by a polynomial of degree 14.
func kernelCos(x, y float64) float64 {
	const (
		c1 float64 = 4.16666666666666019037e-02  // 0x3FA55555 5555554C
		c2 float64 = -1.38888888888741095749e-03 // 0xBF56C16C 16C15177
		c3 float64 = 2.48015872894767294178e-05  // 0x3EFA01A0 19CB1590
		c4 float64 = -2.75573143513906633035e-07 // 0xBE927E4F 809C52AD
		c5 float64 = 2.08757232129817482790e-09  // 0x3E21EE9E BDB4B1C4
		c6 float64 = -1.13596475577881948265e-11 // 0xBDA8FAE9 BE8838D4
	)
	ix := highWord(x) & 0x7fffffff
	if ix < 0x3e400000 && int32(x) == 0 { // |x| < 2**-27
		return 1
	}
	z := x * x
	r := z * (c1 + float64(z*(c2+float64(z*(c3+float64(z*(c4+float64(z*(c5+float64(z*c6))))))))))
	if ix < 0x3FD33333 { // |x| < 0.3
		return 1 - (0.5*z - (float64(z*r) - float64(x*y)))
	}
	var qx float64
	if ix > 0x3fe90000 { // |x| > 0.78125
		qx = 0.28125
	} else {
		qx = fromWords(ix-0x00200000, 0) // x/4
	}
	hz := 0.5*z - qx
	a := 1 - qx
	return a - (hz - (float64(z*r) - float64(x*y)))
}

// kernelTan is fdlibm's __kernel_tan: tan(x+y) on [-pi/4, pi/4] if k is 1, -1/tan(x+y) if k is
// -1.
func kernelTan(x, y float64, k int32) float64 {
	const (
		pio4   float64 = 7.85398163397448278999e-01 // 3FE921FB 54442D18
		pio4lo float64 = 3.06161699786838301793e-17 // 3C81A626 33145C07
	)
	t := [...]float64{
		3.33333333333334091986e-01,  // 3FD55555 55555563
		1.33333333333201242699e-01,  // 3FC11111 1110FE7A
		5.39682539762260521377e-02,  // 3FABA1BA 1BB341FE
		2.18694882948595424599e-02,  // 3F9664F4 8406D637
		8.86323982359930005737e-03,  // 3F8226E3 E96E8493
		3.59207910759131235356e-03,  // 3F6D6D22 C9560328
		1.45620945432529025516e-03,  // 3F57DBC8 FEE08315
		5.88041240820264096874e-04,  // 3F4344D8 F2F26501
		2.46463134818469906812e-04,  // 3F3026F7 1A8D1068
		7.81794442939557092300e-05,  // 3F147E88 A03792A6
		7.14072491382608190305e-05,  // 3F12B80F 32F0A7E9
		-1.85586374855275456654e-05, // BEF375CB DB605373
		2.59073051863633712884e-05,  // 3EFB2A70 74BF7AD4
	}
	hx := highWord(x)
	ix := hx & 0x7fffffff
	if ix < 0x3e300000 && int32(x) == 0 { // |x| < 2**-28
		switch {
		case ix|int32(lowWord(x))|(k+1) == 0:
			return 1 / math.Abs(x)
		case k == 1:
			return x
		}
		// -1/(x+y), computed carefully.
		w := x + y
		z := withLowWord(w, 0)
		v := y - (z - x)
		a := -1 / w
		t := withLowWord(a, 0)
		s := 1 + float64(t*z)
		return t + float64(a*(s+float64(t*v)))
	}
	big := ix >= 0x3FE59428 // |x| >= 0.6744
	if big {
		if hx < 0 {
			x, y = -x, -y
		}
		x = (pio4 - x) + (pio4lo - y)
		y = 0
	}
	z := x * x
	w := z * z
	// Break x^5*(T[1]+x^2*T[2]+...) into x^5(T[1]+x^4*T[3]+...+x^20*T[11]) +
	// x^5(x^2*(T[2]+x^4*T[4]+...+x^22*[T12])).
	r := t[1] + float64(w*(t[3]+float64(w*(t[5]+float64(w*(t[7]+float64(w*(t[9]+float64(w*t[11])))))))))
	v := float64(z * (t[2] + float64(w*(t[4]+float64(w*(t[6]+float64(w*(t[8]+float64(w*(t[10]+float64(w*t[12])))))))))))
	s := z * x
	r = y + float64(z*(float64(s*(r+v))+y))
	r += float64(t[0] * s)
	w = x + r
	if big {
		v = float64(k)
		return float64(1-(hx>>30)&2) * (v - 2.0*(x-(w*w/(w+v)-r)))
	}
	if k == 1 {
		return w
	}
	// -1/(x+r), computed accurately.
	z = withLowWord(w, 0)
	v = r - (z - x) // z+v = r+x
	a := -1.0 / w   // a = -1.0/w
	tt := withLowWord(a, 0)
	s = 1.0 + float64(tt*z)
	return tt + float64(a*(s+float64(tt*v)))
}

// twoOverPi is 2/pi in 24-bit chunks, for remPio2 (1584 bits).
var twoOverPi = [...]int32{
	0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62,
	0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A,
	0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129,
	0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41,
	0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8,
	0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF,
	0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5,
	0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08,
	0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3,
	0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880,
	0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B,
}

// npio2HighWords are the high words of n*pi/2, n = 1 to 32.
var npio2HighWords = [...]int32{
	0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
	0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
	0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
	0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
	0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
	0x404858EB, 0x404921FB,
}

// remPio2 is fdlibm's __ieee754_rem_pio2: x - n*pi/2 = y0 + y1, |y0+y1| <= pi/4, with pi/2 in
// pieces of 33 bits for medium x, and __kernel_rem_pio2 for large x.
func remPio2(x float64) (n int32, y0, y1 float64) {
	const (
		invpio2 float64 = 6.36619772367581382433e-01 // 0x3FE45F30 6DC9C883
		pio2_1  float64 = 1.57079632673412561417e+00 // 0x3FF921FB 54400000
		pio2_1t float64 = 6.07710050650619224932e-11 // 0x3DD0B461 1A626331
		pio2_2  float64 = 6.07710050630396597660e-11 // 0x3DD0B461 1A600000
		pio2_2t float64 = 2.02226624879595063154e-21 // 0x3BA3198A 2E037073
		pio2_3  float64 = 2.02226624871116645580e-21 // 0x3BA3198A 2E000000
		pio2_3t float64 = 8.47842766036889956997e-32 // 0x397B839A 252049C1
		two24   float64 = 1.67772160000000000000e+07 // 0x41700000 00000000
	)
	hx := highWord(x)
	ix := hx & 0x7fffffff
	if ix <= 0x3fe921fb { // |x| ~<= pi/4: no reduction
		return 0, x, 0
	}
	if ix < 0x4002d97c { // |x| < 3pi/4: n = +-1
		if hx > 0 {
			z := x - pio2_1
			if ix != 0x3ff921fb { // 33+53 bit pi is good enough
				y0 = z - pio2_1t
				return 1, y0, (z - y0) - pio2_1t
			}
			z -= pio2_2 // near pi/2: 33+33+53 bit pi
			y0 = z - pio2_2t
			return 1, y0, (z - y0) - pio2_2t
		}
		z := x + pio2_1
		if ix != 0x3ff921fb {
			y0 = z + pio2_1t
			return -1, y0, (z - y0) + pio2_1t
		}
		z += pio2_2
		y0 = z + pio2_2t
		return -1, y0, (z - y0) + pio2_2t
	}
	if ix <= 0x413921fb { // |x| ~<= 2^19*(pi/2): medium size
		t := math.Abs(x)
		n = int32(float64(t*invpio2) + 0.5)
		fn := float64(n)
		r := t - float64(fn*pio2_1)
		w := float64(fn * pio2_1t) // first round good to 85 bits
		y0 = r - w
		if n >= 32 || ix == npio2HighWords[n-1] { // possible cancellation
			j := ix >> 20
			if i := j - (highWord(y0)>>20)&0x7ff; i > 16 { // second iteration, good to 118 bits
				t = r
				w = float64(fn * pio2_2)
				r = t - w
				w = float64(fn*pio2_2t) - ((t - r) - w)
				y0 = r - w
				if i = j - (highWord(y0)>>20)&0x7ff; i > 49 { // third iteration, 151 bits
					t = r
					w = float64(fn * pio2_3)
					r = t - w
					w = float64(fn*pio2_3t) - ((t - r) - w)
					y0 = r - w
				}
			}
		}
		y1 = (r - y0) - w
		if hx < 0 {
			return -n, -y0, -y1
		}
		return n, y0, y1
	}
	// Large arguments.
	if ix >= 0x7ff00000 { // Inf or NaN
		return 0, x - x, x - x
	}
	// z = scalbn(|x|, ilogb(x)-23), split into 24-bit chunks.
	e0 := ix>>20 - 1046 // ilogb(z)-23
	z := fromWords(ix-e0<<20, lowWord(x))
	var tx [3]float64
	for i := range 2 {
		tx[i] = float64(int32(z))
		z = (z - tx[i]) * two24
	}
	tx[2] = z
	nx := 3
	for tx[nx-1] == 0 { // skip zero terms
		nx--
	}
	n, y0, y1 = kernelRemPio2(tx[:nx], e0)
	if hx < 0 {
		return -n, -y0, -y1
	}
	return n, y0, y1
}

// kernelRemPio2 is fdlibm's __kernel_rem_pio2, for double precision (prec 2): x*2^e0 - n*pi/2
// = y0 + y1 for x in 24-bit chunks, using as many chunks of 2/pi as the precision needs.
func kernelRemPio2(x []float64, e0 int32) (n int32, y0, y1 float64) {
	const (
		two24  float64 = 1.67772160000000000000e+07 // 0x41700000 00000000
		twon24 float64 = 5.96046447753906250000e-08 // 0x3E700000 00000000
	)
	pio2 := [...]float64{ // pi/2 in 24-bit chunks
		1.57079625129699707031e+00, // 0x3FF921FB 40000000
		7.54978941586159635335e-08, // 0x3E74442D 00000000
		5.39030252995776476554e-15, // 0x3CF84698 80000000
		3.28200341580791294123e-22, // 0x3B78CC51 60000000
		1.27065575308067607349e-29, // 0x39F01B83 80000000
		1.22933308981111328932e-36, // 0x387A2520 40000000
		2.73370053816464559624e-44, // 0x36E38222 80000000
		2.16741683877804819444e-51, // 0x3569F31D 00000000
	}
	const jk = 4 // initial number of terms, for double precision
	jp := jk
	var iq [20]int32
	var f, fq, q [20]float64

	// jx, jv and q0 (q0 < 3).
	jx := len(x) - 1
	jv := max((e0-3)/24, 0)
	q0 := e0 - 24*(jv+1)

	// f[0] to f[jx+jk], where f[jx+jk] = twoOverPi[jv+jk].
	j := int(jv) - jx
	for i := 0; i <= jx+jk; i, j = i+1, j+1 {
		if j >= 0 {
			f[i] = float64(twoOverPi[j])
		}
	}

	// q[0] to q[jk].
	for i := 0; i <= jk; i++ {
		fw := 0.0
		for j := 0; j <= jx; j++ {
			fw += float64(x[j] * f[jx+i-j])
		}
		q[i] = fw
	}

	jz := jk
	var z float64
	var ih int32
	for {
		// Distill q into iq, reversed.
		z = q[jz]
		for i, j := 0, jz; j > 0; i, j = i+1, j-1 {
			fw := float64(int32(twon24 * z))
			iq[i] = int32(z - float64(two24*fw))
			z = q[j-1] + fw
		}

		// n.
		z = math.Ldexp(z, int(q0))
		z -= 8.0 * math.Floor(z*0.125) // trim off the integer >= 8
		n = int32(z)
		z -= float64(n)
		ih = 0
		switch {
		case q0 > 0: // iq[jz-1] is needed to determine n
			i := iq[jz-1] >> (24 - q0)
			n += i
			iq[jz-1] -= i << (24 - q0)
			ih = iq[jz-1] >> (23 - q0)
		case q0 == 0:
			ih = iq[jz-1] >> 23
		case z >= 0.5:
			ih = 2
		}

		if ih > 0 { // q > 0.5
			n++
			carry := int32(0)
			for i := range jz { // 1-q
				j := iq[i]
				if carry == 0 {
					if j != 0 {
						carry = 1
						iq[i] = 0x1000000 - j
					}
				} else {
					iq[i] = 0xffffff - j
				}
			}
			switch q0 { // rare case: chance is 1 in 12
			case 1:
				iq[jz-1] &= 0x7fffff
			case 2:
				iq[jz-1] &= 0x3fffff
			}
			if ih == 2 {
				z = 1 - z
				if carry != 0 {
					z -= math.Ldexp(1, int(q0))
				}
			}
		}

		// Recompute if needed.
		if z != 0 {
			break
		}
		j := int32(0)
		for i := jz - 1; i >= jk; i-- {
			j |= iq[i]
		}
		if j != 0 {
			break
		}
		k := 1
		for iq[jk-k] == 0 { // k = number of terms needed
			k++
		}
		for i := jz + 1; i <= jz+k; i++ { // add q[jz+1] to q[jz+k]
			f[jx+i] = float64(twoOverPi[int(jv)+i])
			fw := 0.0
			for j := 0; j <= jx; j++ {
				fw += float64(x[j] * f[jx+i-j])
			}
			q[i] = fw
		}
		jz += k
	}

	// Chop off zero terms.
	if z == 0 {
		jz--
		q0 -= 24
		for iq[jz] == 0 {
			jz--
			q0 -= 24
		}
	} else { // break z into 24 bits if necessary
		z = math.Ldexp(z, -int(q0))
		if z >= two24 {
			fw := float64(int32(twon24 * z))
			iq[jz] = int32(z - float64(two24*fw))
			jz++
			q0 += 24
			iq[jz] = int32(fw)
		} else {
			iq[jz] = int32(z)
		}
	}

	// Convert the integer chunks to floating point.
	fw := math.Ldexp(1, int(q0))
	for i := jz; i >= 0; i-- {
		q[i] = fw * float64(iq[i])
		fw *= twon24
	}

	// pio2[0..jp] * q[jz..0].
	for i := jz; i >= 0; i-- {
		fw := 0.0
		for k := 0; k <= jp && k <= jz-i; k++ {
			fw += float64(pio2[k] * q[i+k])
		}
		fq[jz-i] = fw
	}

	// Compress fq into y0 and y1.
	fw = 0.0
	for i := jz; i >= 0; i-- {
		fw += fq[i]
	}
	if ih == 0 {
		y0 = fw
	} else {
		y0 = -fw
	}
	fw = fq[0] - fw
	for i := 1; i <= jz; i++ {
		fw += fq[i]
	}
	if ih == 0 {
		y1 = fw
	} else {
		y1 = -fw
	}
	return n & 7, y0, y1
}

// Polynomial for asin and acos: R(x^2) = p/q.
const (
	pS0 float64 = 1.66666666666666657415e-01  // 0x3FC55555 55555555
	pS1 float64 = -3.25565818622400915405e-01 // 0xBFD4D612 03EB6F7D
	pS2 float64 = 2.01212532134862925881e-01  // 0x3FC9C155 0E884455
	pS3 float64 = -4.00555345006794114027e-02 // 0xBFA48228 B5688F3B
	pS4 float64 = 7.91534994289814532176e-04  // 0x3F49EFE0 7501B288
	pS5 float64 = 3.47933107596021167570e-05  // 0x3F023DE1 0DFDF709
	qS1 float64 = -2.40339491173441421878e+00 // 0xC0033A27 1C8A2D4B
	qS2 float64 = 2.02094576023350569471e+00  // 0x40002AE5 9C598AC8
	qS3 float64 = -6.88283971605453293030e-01 // 0xBFE6066C 1B8D0159
	qS4 float64 = 7.70381505559019352791e-02  // 0x3FB3B8C5 B12E9282

	pio2Hi float64 = 1.57079632679489655800e+00 // 0x3FF921FB 54442D18
	pio2Lo float64 = 6.12323399573676603587e-17 // 0x3C91A626 33145C07
)

// asinR is p/q, p = t*(pS0+...) and q = 1+t*(qS1+...): asin(x) = x + x^3*R(x^2).
func asinR(t float64) float64 {
	p := t * (pS0 + float64(t*(pS1+float64(t*(pS2+float64(t*(pS3+float64(t*(pS4+float64(t*pS5))))))))))
	q := 1 + float64(t*(qS1+float64(t*(qS2+float64(t*(qS3+float64(t*qS4)))))))
	return p / q
}

// StrictAsin is fdlibm's __ieee754_asin: x + x^3*R(x^2) for |x| < 0.5; otherwise from
// asin(x) = pi/2 - 2*asin(sqrt((1-x)/2)).
func StrictAsin(x float64) float64 {
	const pio4Hi float64 = 7.85398163397448278999e-01 // 0x3FE921FB 54442D18
	hx := highWord(x)
	ix := hx & 0x7fffffff
	if ix >= 0x3ff00000 { // |x| >= 1
		if (ix-0x3ff00000)|int32(lowWord(x)) == 0 {
			return float64(x*pio2Hi) + float64(x*pio2Lo) // asin(+-1) = +-pi/2
		}
		return NaN() // asin(|x| > 1) is NaN
	}
	if ix < 0x3fe00000 { // |x| < 0.5
		if ix < 0x3e400000 { // |x| < 2**-27
			return x
		}
		return x + float64(x*asinR(x*x))
	}
	// 1 > |x| >= 0.5
	w := 1 - math.Abs(x)
	t := w * 0.5
	r := asinR(t)
	s := math.Sqrt(t)
	if ix >= 0x3FEF3333 { // |x| > 0.975
		t = pio2Hi - (2.0*(s+float64(s*r)) - pio2Lo)
	} else {
		w = withLowWord(s, 0)
		c := (t - float64(w*w)) / (s + w)
		p := 2.0*float64(s*r) - (pio2Lo - 2.0*c)
		q := pio4Hi - 2.0*w
		t = pio4Hi - (p - q)
	}
	if hx > 0 {
		return t
	}
	return -t
}

// StrictAcos is fdlibm's __ieee754_acos: pi/2 - (x + x^3*R(x^2)) for |x| < 0.5; otherwise from
// acos(x) = 2*asin(sqrt((1-x)/2)) or pi - 2*asin(sqrt((1+x)/2)).
func StrictAcos(x float64) float64 {
	const pi float64 = 3.14159265358979311600e+00 // 0x400921FB 54442D18
	hx := highWord(x)
	ix := hx & 0x7fffffff
	if ix >= 0x3ff00000 { // |x| >= 1
		if (ix-0x3ff00000)|int32(lowWord(x)) == 0 {
			if hx > 0 {
				return 0 // acos(1) = 0
			}
			return pi + 2.0*pio2Lo // acos(-1) = pi
		}
		return NaN() // acos(|x| > 1) is NaN
	}
	if ix < 0x3fe00000 { // |x| < 0.5
		if ix <= 0x3c600000 { // |x| < 2**-57
			return pio2Hi + pio2Lo
		}
		return pio2Hi - (x - (pio2Lo - float64(x*asinR(x*x))))
	}
	if hx < 0 { // x < -0.5
		z := (1 + x) * 0.5
		s := math.Sqrt(z)
		w := float64(asinR(z)*s) - pio2Lo
		return pi - 2.0*(s+w)
	}
	// x > 0.5
	z := (1 - x) * 0.5
	s := math.Sqrt(z)
	df := withLowWord(s, 0)
	c := (z - float64(df*df)) / (s + df)
	w := float64(asinR(z)*s) + c
	return 2.0 * (df + w)
}

// StrictAtan is fdlibm's atan: the argument is reduced to [0, 7/16] using
// atan(x) = atan(c) + atan((x-c)/(1+x*c)), then a polynomial of degree 22 applies.
func StrictAtan(x float64) float64 {
	atanHi := [...]float64{
		4.63647609000806093515e-01, // atan(0.5)hi 0x3FDDAC67 0561BB4F
		7.85398163397448278999e-01, // atan(1.0)hi 0x3FE921FB 54442D18
		9.82793723247329054082e-01, // atan(1.5)hi 0x3FEF730B D281F69B
		1.57079632679489655800e+00, // atan(inf)hi 0x3FF921FB 54442D18
	}
	atanLo := [...]float64{
		2.26987774529616870924e-17, // atan(0.5)lo 0x3C7A2B7F 222F65E2
		3.06161699786838301793e-17, // atan(1.0)lo 0x3C81A626 33145C07
		1.39033110312309984516e-17, // atan(1.5)lo 0x3C700788 7AF0CBBD
		6.12323399573676603587e-17, // atan(inf)lo 0x3C91A626 33145C07
	}
	aT := [...]float64{
		3.33333333333329318027e-01,  // 0x3FD55555 5555550D
		-1.99999999998764832476e-01, // 0xBFC99999 9998EBC4
		1.42857142725034663711e-01,  // 0x3FC24924 920083FF
		-1.11111104054623557880e-01, // 0xBFBC71C6 FE231671
		9.09088713343650656196e-02,  // 0x3FB745CD C54C206E
		-7.69187620504482999495e-02, // 0xBFB3B0F2 AF749A6D
		6.66107313738753120669e-02,  // 0x3FB10D66 A0D03D51
		-5.83357013379057348645e-02, // 0xBFADDE2D 52DEFD9A
		4.97687799461593236017e-02,  // 0x3FA97B4B 24760DEB
		-3.65315727442169155270e-02, // 0xBFA2B444 2C6A6C2F
		1.62858201153657823623e-02,  // 0x3F90AD3A E322DA11
	}
	hx := highWord(x)
	ix := hx & 0x7fffffff
	var id int
	switch {
	case ix >= 0x44100000: // |x| >= 2^66
		if ix > 0x7ff00000 || ix == 0x7ff00000 && lowWord(x) != 0 {
			return x + x // NaN
		}
		if hx > 0 {
			return atanHi[3] + atanLo[3]
		}
		return -atanHi[3] - atanLo[3]
	case ix < 0x3fdc0000: // |x| < 0.4375
		if ix < 0x3e200000 { // |x| < 2^-29
			return x
		}
		id = -1
	default:
		x = math.Abs(x)
		switch {
		case ix < 0x3fe60000: // 7/16 <= |x| < 11/16
			id, x = 0, (2.0*x-1)/(2.0+x)
		case ix < 0x3ff30000: // 11/16 <= |x| < 19/16
			id, x = 1, (x-1)/(x+1)
		case ix < 0x40038000: // |x| < 2.4375
			id, x = 2, (x-1.5)/(1+float64(1.5*x))
		default: // 2.4375 <= |x| < 2^66
			id, x = 3, -1.0/x
		}
	}
	z := x * x
	w := z * z
	// Break the sum of aT[i]z**(i+1), i = 0 to 10, into odd and even polynomials.
	s1 := float64(z * (aT[0] + float64(w*(aT[2]+float64(w*(aT[4]+float64(w*(aT[6]+float64(w*(aT[8]+float64(w*aT[10])))))))))))
	s2 := float64(w * (aT[1] + float64(w*(aT[3]+float64(w*(aT[5]+float64(w*(aT[7]+float64(w*aT[9])))))))))
	if id < 0 {
		return x - float64(x*(s1+s2))
	}
	z = atanHi[id] - ((float64(x*(s1+s2)) - atanLo[id]) - x)
	if hx < 0 {
		return -z
	}
	return z
}

// StrictAtan2 is fdlibm's __ieee754_atan2: atan(y/x), with the quadrant from the signs.
func StrictAtan2(y, x float64) float64 {
	const (
		pio4 float64 = 7.8539816339744827900e-01 // 0x3FE921FB 54442D18
		pi   float64 = 3.1415926535897931160e+00 // 0x400921FB 54442D18
		piLo float64 = 1.2246467991473531772e-16 // 0x3CA1A626 33145C07
	)
	hx, lx := highWord(x), lowWord(x)
	hy, ly := highWord(y), lowWord(y)
	ix, iy := hx&0x7fffffff, hy&0x7fffffff
	if uint32(ix)|(lx|-lx)>>31 > 0x7ff00000 || uint32(iy)|(ly|-ly)>>31 > 0x7ff00000 { // NaN
		return x + y
	}
	if (hx-0x3ff00000)|int32(lx) == 0 { // x = 1.0
		return StrictAtan(y)
	}
	m := (hy>>31)&1 | (hx>>30)&2 // 2*sign(x)+sign(y)

	// y = 0.
	if iy|int32(ly) == 0 {
		switch m {
		case 0, 1:
			return y // atan(+-0, +anything) = +-0
		case 2:
			return pi // atan(+0, -anything) = pi
		default:
			return -pi // atan(-0, -anything) = -pi
		}
	}
	// x = 0.
	if ix|int32(lx) == 0 {
		if hy < 0 {
			return -pio2Hi
		}
		return pio2Hi
	}
	// x is INF.
	if ix == 0x7ff00000 {
		if iy == 0x7ff00000 {
			switch m {
			case 0:
				return pio4 // atan(+INF, +INF)
			case 1:
				return -pio4 // atan(-INF, +INF)
			case 2:
				return 3.0 * pio4 // atan(+INF, -INF)
			default:
				return -3.0 * pio4 // atan(-INF, -INF)
			}
		}
		switch m {
		case 0:
			return 0 // atan(+..., +INF)
		case 1:
			return math.Copysign(0, -1) // atan(-..., +INF)
		case 2:
			return pi // atan(+..., -INF)
		default:
			return -pi // atan(-..., -INF)
		}
	}
	// y is INF.
	if iy == 0x7ff00000 {
		if hy < 0 {
			return -pio2Hi
		}
		return pio2Hi
	}

	// y/x.
	var z float64
	switch k := (iy - ix) >> 20; {
	case k > 60: // |y/x| > 2**60
		z = pio2Hi + 0.5*piLo
	case hx < 0 && k < -60: // |y|/x < -2**60
		z = 0
	default:
		z = StrictAtan(math.Abs(y / x))
	}
	switch m {
	case 0:
		return z // atan(+, +)
	case 1:
		return -z // atan(-, +)
	case 2:
		return pi - (z - piLo) // atan(+, -)
	default:
		return (z - piLo) - pi // atan(-, -)
	}
}
