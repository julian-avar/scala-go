package rt

import (
	"math"
	"math/big"
	"math/bits"
	"sync/atomic"
)

// BigDecimal is java.math.BigDecimal: an unscaled integer and a scale, whose value is
// unscaled × 10^-scale. As in the JDK, the unscaled value is kept in an int64 when it fits (the
// common case, such as money amounts, where arithmetic allocates nothing but the result), and in
// a big.Int otherwise. Values are immutable, so they may be shared between goroutines.
type BigDecimal struct {
	small int64    // the unscaled value, if big is nil
	big   *big.Int // the unscaled value when it does not fit in an int64; never mutated
	scale int32
	prec  atomic.Int32 // cached number of digits of a big unscaled value (0: not computed yet)
}

// Rounding modes: the ordinals of java.math.RoundingMode, which are also BigDecimal's legacy
// ROUND_* constants.
const (
	RoundUp int32 = iota
	RoundDown
	RoundCeiling
	RoundFloor
	RoundHalfUp
	RoundHalfDown
	RoundHalfEven
	RoundUnnecessary
)

// mathContext holds a java.math.MathContext's settings. Natives receive them packed in an int64
// (precision << 3 | rounding mode), from javalib's MathContext.goBits.
type mathContext struct {
	prec int32
	mode int32
}

func mcOf(bits int64) mathContext { return mathContext{int32(bits >> 3), int32(bits & 7)} }

var (
	BigDecimalZero = bdOf(0, 0)
	BigDecimalOne  = bdOf(1, 0)
	BigDecimalTwo  = bdOf(2, 0)
	BigDecimalTen  = bdOf(10, 0)
)

func bdOf(small int64, scale int32) *BigDecimal { return &BigDecimal{small: small, scale: scale} }

// bdOfBig takes ownership of v (or shares it, if it is never mutated).
func bdOfBig(v *big.Int, scale int32) *BigDecimal {
	if v.IsInt64() {
		return &BigDecimal{small: v.Int64(), scale: scale}
	}
	return &BigDecimal{big: v, scale: scale}
}

func (d *BigDecimal) String() string { return string(d.layout(nil, true)) }

func (d *BigDecimal) isNilRef() bool { return d == nil }

func (d *BigDecimal) sign() int {
	if d.big != nil {
		return d.big.Sign()
	}
	switch {
	case d.small > 0:
		return 1
	case d.small < 0:
		return -1
	}
	return 0
}

// unscaled returns the unscaled value as a big.Int, which callers must not mutate.
func (d *BigDecimal) unscaled() *big.Int {
	if d.big != nil {
		return d.big
	}
	return big.NewInt(d.small)
}

// precision is the number of decimal digits of the unscaled value (1 for zero).
func (d *BigDecimal) precision() int32 {
	if d.big == nil {
		return digitsU(absU(d.small))
	}
	if p := d.prec.Load(); p != 0 {
		return p
	}
	p := digitsBig(d.big)
	d.prec.Store(p)
	return p
}

// ---- Integer helpers ------------------------------------------------------------------------

var pow10u = func() (t [20]uint64) {
	p := uint64(1)
	for i := range t {
		t[i] = p
		p *= 10
	}
	return
}()

var (
	big10      = big.NewInt(10)
	pow10Table = func() (t [128]*big.Int) {
		t[0] = big.NewInt(1)
		for i := 1; i < len(t); i++ {
			t[i] = new(big.Int).Mul(t[i-1], big10)
		}
		return
	}()
)

// pow10Big returns 10^n, which callers must not mutate.
func pow10Big(n int64) *big.Int {
	if n < int64(len(pow10Table)) {
		return pow10Table[n]
	}
	if float64(n)*math.Log2(10) >= 1<<31 {
		ThrowKind(Arithmetic, "BigInteger would overflow supported range") // the JDK's limit: 2^31 bits
	}
	return new(big.Int).Exp(big10, big.NewInt(n), nil)
}

// absU is |x|, also for math.MinInt64.
func absU(x int64) uint64 {
	if x < 0 {
		return uint64(-x)
	}
	return uint64(x)
}

// digitsU is the number of decimal digits of u (1 for 0).
func digitsU(u uint64) int32 {
	if u == 0 {
		return 1
	}
	t := (bits.Len64(u) * 1233) >> 12 // ≈ log10(u), low by at most one
	if u < pow10u[t] {
		return int32(t)
	}
	return int32(t + 1)
}

// digitsBig is the number of decimal digits of |x|.
func digitsBig(x *big.Int) int32 {
	n := x.BitLen()
	if n < 64 {
		return digitsU(absU(x.Int64()))
	}
	// 2^(n-1) ≤ |x| < 2^n: the estimate is off by at most one either way.
	d := int64(float64(n-1)*math.Log10(2)) + 1
	if x.CmpAbs(pow10Big(d-1)) < 0 {
		d--
	} else if x.CmpAbs(pow10Big(d)) >= 0 {
		d++
	}
	return int32(d)
}

// mulPow10 is v × 10^n, if it fits in an int64.
func mulPow10(v int64, n int64) (int64, bool) {
	if v == 0 || n == 0 {
		return v, true
	}
	if n >= 19 {
		return 0, false
	}
	return mulAbs(v < 0, absU(v), pow10u[n])
}

// mul64 is a × b, if it fits in an int64.
func mul64(a, b int64) (int64, bool) { return mulAbs((a < 0) != (b < 0), absU(a), absU(b)) }

func mulAbs(negative bool, a, b uint64) (int64, bool) {
	hi, lo := bits.Mul64(a, b)
	if hi != 0 {
		return 0, false
	}
	if negative {
		if lo > 1<<63 {
			return 0, false
		}
		return -int64(lo), true
	}
	if lo > math.MaxInt64 {
		return 0, false
	}
	return int64(lo), true
}

func add64(a, b int64) (int64, bool) {
	s := a + b
	return s, (a^s)&(b^s) >= 0
}

func sub64(a, b int64) (int64, bool) {
	s := a - b
	return s, (a^b)&(a^s) >= 0
}

// bigTimesPow10 is a new x × 10^n.
func bigTimesPow10(x *big.Int, n int64) *big.Int {
	if n == 0 || x.Sign() == 0 {
		return new(big.Int).Set(x)
	}
	return new(big.Int).Mul(x, pow10Big(n))
}

func gcd64(a, b uint64) uint64 {
	for b != 0 {
		a, b = b, a%b
	}
	return a
}

func compare64(a, b int64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

// ---- Rounding -------------------------------------------------------------------------------

// roundsAway decides whether an inexact quotient is rounded away from zero. sign is the sign of
// the exact quotient, half compares the discarded fraction with one half (-1, 0 or 1), and odd
// tells whether the truncated quotient is odd.
func roundsAway(mode int32, sign int, half int, odd bool) bool {
	switch mode {
	case RoundUp:
		return true
	case RoundDown:
		return false
	case RoundCeiling:
		return sign > 0
	case RoundFloor:
		return sign < 0
	case RoundHalfUp:
		return half >= 0
	case RoundHalfDown:
		return half > 0
	case RoundHalfEven:
		return half > 0 || half == 0 && odd
	}
	ThrowKind(Arithmetic, "Rounding necessary")
	return false
}

// divRound64 is x / y rounded with mode (y != 0, and not math.MinInt64 / -1).
func divRound64(x, y int64, mode int32) int64 {
	q := x / y
	r := x - q*y
	if r == 0 {
		return q
	}
	sign := 1
	if (x < 0) != (y < 0) {
		sign = -1
	}
	// |r| < |y| ≤ 2^63, so 2|r| does not overflow.
	half := 0
	if r2, ay := 2*absU(r), absU(y); r2 < ay {
		half = -1
	} else if r2 > ay {
		half = 1
	}
	if roundsAway(mode, sign, half, q&1 != 0) {
		q += int64(sign)
	}
	return q
}

// divRoundBig is a new x / y rounded with mode (y != 0).
func divRoundBig(x, y *big.Int, mode int32) *big.Int {
	q, r := new(big.Int).QuoRem(x, y, new(big.Int))
	if r.Sign() == 0 {
		return q
	}
	sign := x.Sign() * y.Sign()
	half := r.Lsh(r, 1).CmpAbs(y)
	if roundsAway(mode, sign, half, q.Bit(0) == 1) {
		q.Add(q, big.NewInt(int64(sign)))
	}
	return q
}

// dropDigits divides the unscaled value by 10^n (n > 0) with rounding, giving the result scale.
func (d *BigDecimal) dropDigits(n int64, mode int32, scale int32) *BigDecimal {
	if d.big == nil && n < 19 {
		return bdOf(divRound64(d.small, int64(pow10u[n]), mode), scale)
	}
	sign := d.sign()
	if sign == 0 {
		return bdOf(0, scale)
	}
	if int64(d.precision()) < n {
		// |u| < 10^(n-1): the quotient is 0, and the remainder less than half of 10^n.
		if roundsAway(mode, sign, -1, false) {
			return bdOf(int64(sign), scale)
		}
		return bdOf(0, scale)
	}
	return bdOfBig(divRoundBig(d.unscaled(), pow10Big(n), mode), scale)
}

// round is the JDK's doRound: rounds to mc's precision.
func (d *BigDecimal) round(mc mathContext) *BigDecimal {
	if mc.prec == 0 {
		return d
	}
	p := d.precision()
	if p <= mc.prec {
		return d
	}
	drop := int64(p - mc.prec)
	r := d.dropDigits(drop, mc.mode, checkScaleNonZero(int64(d.scale)-drop))
	if r.precision() > mc.prec { // rounding carried into a new digit (99.9 → 100)
		r = r.dropDigits(1, mc.mode, checkScaleNonZero(int64(r.scale)-1))
	}
	return r
}

// ---- Scales ---------------------------------------------------------------------------------

// checkScale checks a computed scale: out of range, it saturates for zero values and throws
// otherwise, as the JDK does.
func checkScale(s int64, zero bool) int32 {
	if s > math.MaxInt32 {
		if !zero {
			ThrowKind(Arithmetic, "Underflow")
		}
		return math.MaxInt32
	}
	if s < math.MinInt32 {
		if !zero {
			ThrowKind(Arithmetic, "Overflow")
		}
		return math.MinInt32
	}
	return int32(s)
}

// checkScaleNonZero is the JDK's variant that names the error by the sign of the truncated value.
func checkScaleNonZero(s int64) int32 {
	if int64(int32(s)) != s {
		if int32(s) > 0 {
			ThrowKind(Arithmetic, "Underflow")
		}
		ThrowKind(Arithmetic, "Overflow")
	}
	return int32(s)
}

func saturateScale(s int64) int32 { return int32(max(math.MinInt32, min(math.MaxInt32, s))) }

// setScale is setScale(newScale, mode).
func (d *BigDecimal) setScale(newScale int32, mode int32) *BigDecimal {
	if newScale == d.scale {
		return d
	}
	if d.sign() == 0 {
		return bdOf(0, newScale)
	}
	if newScale > d.scale {
		raise := int64(checkScale(int64(newScale)-int64(d.scale), false))
		if d.big == nil {
			if v, ok := mulPow10(d.small, raise); ok {
				return bdOf(v, newScale)
			}
		}
		return bdOfBig(bigTimesPow10(d.unscaled(), raise), newScale)
	}
	return d.dropDigits(int64(d.scale)-int64(newScale), mode, newScale)
}

// stripZeros removes trailing zeros of the unscaled value while the scale is above preferred.
func (d *BigDecimal) stripZeros(preferred int64) *BigDecimal {
	scale := int64(d.scale)
	if d.big == nil {
		v := d.small
		for scale > preferred && v != 0 && v%10 == 0 {
			v /= 10
			scale--
		}
		if scale == int64(d.scale) {
			return d
		}
		return bdOf(v, checkScale(scale, false))
	}
	v := new(big.Int).Set(d.big)
	q, r := new(big.Int), new(big.Int)
	for scale > preferred {
		q.QuoRem(v, big10, r)
		if r.Sign() != 0 {
			break
		}
		v, q = q, v
		scale--
	}
	return bdOfBig(v, checkScale(scale, false))
}

// ---- Comparison -----------------------------------------------------------------------------

// compareMagnitude compares |x| and |y|.
func compareMagnitude(x, y *BigDecimal) int {
	xs, ys := x.sign(), y.sign()
	if xs == 0 || ys == 0 {
		return compare64(int64(xs*xs), int64(ys*ys))
	}
	if x.scale == y.scale && x.big == nil && y.big == nil {
		return compareU(absU(x.small), absU(y.small))
	}
	// Compare the positions of the leading digits first.
	if c := compare64(int64(x.precision())-int64(x.scale), int64(y.precision())-int64(y.scale)); c != 0 {
		return c
	}
	// Same leading position: the scales differ by the difference in precision, so aligning is cheap.
	diff := int64(x.scale) - int64(y.scale)
	if x.big == nil && y.big == nil {
		if diff >= 0 {
			if v, ok := mulPow10(y.small, diff); ok {
				return compareU(absU(x.small), absU(v))
			}
		} else if v, ok := mulPow10(x.small, -diff); ok {
			return compareU(absU(v), absU(y.small))
		}
	}
	if diff >= 0 {
		return x.unscaled().CmpAbs(bigTimesPow10(y.unscaled(), diff))
	}
	return bigTimesPow10(x.unscaled(), -diff).CmpAbs(y.unscaled())
}

func compareU(a, b uint64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

func BigDecimalCompareTo(x, y *BigDecimal) int32 {
	if x.scale == y.scale && x.big == nil && y.big == nil {
		return int32(compare64(x.small, y.small))
	}
	xs, ys := x.sign(), y.sign()
	if xs != ys {
		return int32(compare64(int64(xs), int64(ys)))
	}
	return int32(xs * compareMagnitude(x, y))
}

func BigDecimalEquals(x *BigDecimal, o any) bool {
	y, ok := o.(*BigDecimal)
	if !ok || y == nil || x.scale != y.scale {
		return false
	}
	if x.big == nil || y.big == nil {
		return x.big == nil && y.big == nil && x.small == y.small
	}
	return x.big.Cmp(y.big) == 0
}

// BigDecimalHashCode is 31 × unscaledValue().hashCode() + scale, as in the JDK.
func BigDecimalHashCode(d *BigDecimal) int32 {
	var h int32
	if d.big == nil {
		u := absU(d.small)
		h = int32(uint32(u>>32))*31 + int32(uint32(u))
		if d.small < 0 {
			h = -h
		}
	} else {
		h = bigIntHashCode(d.big)
	}
	return 31*h + d.scale
}

func BigDecimalMin(x, y *BigDecimal) *BigDecimal {
	if BigDecimalCompareTo(x, y) <= 0 {
		return x
	}
	return y
}

func BigDecimalMax(x, y *BigDecimal) *BigDecimal {
	if BigDecimalCompareTo(x, y) >= 0 {
		return x
	}
	return y
}

// ---- Addition and multiplication ------------------------------------------------------------

// add is x + y, or x - y, exactly.
func add(x, y *BigDecimal, subtract bool) *BigDecimal {
	scale := max(x.scale, y.scale)
	if x.big == nil && y.big == nil {
		a, okA := mulPow10(x.small, int64(scale)-int64(x.scale))
		b, okB := mulPow10(y.small, int64(scale)-int64(y.scale))
		if okA && okB {
			var s int64
			var ok bool
			if subtract {
				s, ok = sub64(a, b)
			} else {
				s, ok = add64(a, b)
			}
			if ok {
				return bdOf(s, scale)
			}
		}
	}
	// The JDK rejects aligning a nonzero operand by more than an int's worth of digits.
	if raise := int64(x.scale) - int64(y.scale); raise > math.MaxInt32 && y.sign() != 0 ||
		raise < -math.MaxInt32 && x.sign() != 0 {
		ThrowKind(Arithmetic, "Underflow")
	}
	a := bigTimesPow10(x.unscaled(), int64(scale)-int64(x.scale))
	b := bigTimesPow10(y.unscaled(), int64(scale)-int64(y.scale))
	if subtract {
		return bdOfBig(a.Sub(a, b), scale)
	}
	return bdOfBig(a.Add(a, b), scale)
}

// addMC is x + y (or x - y) rounded to mc: the exact sum, rounded, computed without aligning
// operands whose digit positions are far apart.
func addMC(x, y *BigDecimal, subtract bool, mc mathContext) *BigDecimal {
	if mc.prec == 0 {
		return add(x, y, subtract)
	}
	if x.big == nil && y.big == nil {
		if d := int64(x.scale) - int64(y.scale); d > -19 && d < 19 {
			r := add(x, y, subtract)
			if mc.prec >= 19 && r.big == nil {
				return r // at most 19 digits: nothing to round
			}
			return r.round(mc)
		}
	}
	if subtract {
		y = y.negate()
	}
	if x.sign() == 0 || y.sign() == 0 {
		// The other operand, rounded, with a scale as close to the preferred one as the precision allows.
		preferred := max(x.scale, y.scale)
		if x.sign() == 0 && y.sign() == 0 {
			return bdOf(0, preferred)
		}
		r := x
		if x.sign() == 0 {
			r = y
		}
		r = r.round(mc)
		if r.scale >= preferred {
			return r.stripZeros(int64(preferred))
		}
		// In int arithmetic, as in the JDK (whose overflows make extreme scales fail in setScale).
		precisionDiff := mc.prec - r.precision()
		if scaleDiff := preferred - r.scale; precisionDiff >= scaleDiff {
			return r.setScale(preferred, RoundUnnecessary)
		}
		return r.setScale(r.scale+precisionDiff, RoundUnnecessary)
	}
	if x.scale != y.scale {
		x, y = preAlign(x, y, mc)
	}
	return add(x, y, false).round(mc)
}

// preAlign replaces the operand whose digits are all below the result's precision by a sticky
// digit just below it, which rounds the same.
func preAlign(x, y *BigDecimal, mc mathContext) (*BigDecimal, *BigDecimal) {
	large, small := x, y
	if x.scale > y.scale {
		large, small = y, x
	}
	estResultUlpScale := int64(large.scale) - int64(large.precision()) + int64(mc.prec)
	smallHighDigitPos := int64(small.scale) - int64(small.precision()) + 1
	if smallHighDigitPos > int64(large.scale)+2 && smallHighDigitPos > estResultUlpScale+2 {
		small = bdOf(int64(small.sign()), checkScale(max(int64(large.scale), estResultUlpScale)+3, false))
	}
	return large, small
}

func (d *BigDecimal) negate() *BigDecimal {
	if d.big == nil && d.small != math.MinInt64 {
		return bdOf(-d.small, d.scale)
	}
	return bdOfBig(new(big.Int).Neg(d.unscaled()), d.scale)
}

func multiply(x, y *BigDecimal) *BigDecimal {
	scale := checkScale(int64(x.scale)+int64(y.scale), x.sign() == 0)
	if x.big == nil && y.big == nil {
		if p, ok := mul64(x.small, y.small); ok {
			return bdOf(p, scale)
		}
	}
	return bdOfBig(new(big.Int).Mul(x.unscaled(), y.unscaled()), scale)
}

func multiplyMC(x, y *BigDecimal, mc mathContext) *BigDecimal {
	return multiply(x, y).round(mc)
}

// ---- Division -------------------------------------------------------------------------------

// checkDecimalDivisor throws the exceptions of divisions that round to a precision or are exact.
func checkDecimalDivisor(x, y *BigDecimal) {
	if y.sign() == 0 {
		if x.sign() == 0 {
			ThrowKind(Arithmetic, "Division undefined")
		}
		ThrowKind(Arithmetic, "Division by zero")
	}
}

// divideExact is divide(divisor): the exact quotient, with the preferred scale
// x.scale - y.scale or the smallest scale that represents it.
func divideExact(x, y *BigDecimal) *BigDecimal {
	checkDecimalDivisor(x, y)
	if x.sign() == 0 {
		return bdOf(0, saturateScale(int64(x.scale)-int64(y.scale)))
	}
	checkExactDivisionScales(x, y)
	// x/y = (a/b) × 10^(y.scale - x.scale). With a/b in lowest terms, the expansion terminates
	// iff b = 2^i × 5^j, and then a/b = a × 2^(k-i) × 5^(k-j) / 10^k with k = max(i, j).
	if x.big == nil && y.big == nil && x.small != math.MinInt64 && y.small != math.MinInt64 {
		a, b := x.small, y.small
		g := int64(gcd64(absU(a), absU(b)))
		a, b = a/g, b/g
		if b < 0 {
			a, b = -a, -b
		}
		i := bits.TrailingZeros64(uint64(b))
		b >>= i
		j := 0
		for b%5 == 0 {
			b /= 5
			j++
		}
		if b != 1 {
			nonTerminating()
		}
		k := max(i, j)
		if k-j < 28 && k-i < 63 {
			f := pow5(k-j) << (k - i)
			if q, ok := mulAbs(a < 0, absU(a), f); ok {
				return bdOf(q, checkScale(int64(x.scale)-int64(y.scale)+int64(k), false))
			}
		}
	}
	a, b := new(big.Int).Set(x.unscaled()), new(big.Int).Set(y.unscaled())
	g := new(big.Int).GCD(nil, nil, new(big.Int).Abs(a), new(big.Int).Abs(b))
	a.Quo(a, g)
	b.Quo(b, g)
	if b.Sign() < 0 {
		a.Neg(a)
		b.Neg(b)
	}
	i := int(b.TrailingZeroBits())
	b.Rsh(b, uint(i))
	j := 0
	five, q, r := big.NewInt(5), new(big.Int), new(big.Int)
	for {
		q.QuoRem(b, five, r)
		if r.Sign() != 0 {
			break
		}
		b, q = q, b
		j++
	}
	if b.Cmp(big.NewInt(1)) != 0 {
		nonTerminating()
	}
	k := max(i, j)
	a.Lsh(a, uint(k-i))
	if k > j {
		a.Mul(a, new(big.Int).Exp(five, big.NewInt(int64(k-j)), nil))
	}
	return bdOfBig(a, checkScale(int64(x.scale)-int64(y.scale)+int64(k), false))
}

// checkExactDivisionScales throws where the JDK's exact division would overflow a scale: it
// divides to the precision x.precision + ⌈10 × y.precision / 3⌉, and reports any failure as a
// non-terminating expansion.
func checkExactDivisionScales(x, y *BigDecimal) {
	preferred := int64(x.scale) - int64(y.scale)
	xp, yp := int64(x.precision()), int64(y.precision())
	t := min(xp+(10*yp+2)/3, math.MaxInt32) + yp - xp // see divideMC
	fits := func(s int64) bool { return s == int64(int32(s)) }
	if !fits(preferred) {
		nonTerminating()
	}
	if !fits(preferred+t) || !fits(preferred+t-1) {
		if compareSignificands(x.unscaled(), xp, y.unscaled(), yp) > 0 {
			t--
		}
		if !fits(preferred + t) {
			nonTerminating()
		}
	}
}

func nonTerminating() {
	ThrowKind(Arithmetic, "Non-terminating decimal expansion; no exact representable decimal result.")
}

func pow5(n int) uint64 {
	p := uint64(1)
	for range n {
		p *= 5
	}
	return p
}

// divideMC is divide(divisor, mc): the quotient rounded to mc.prec digits. An exact quotient
// has the preferred scale x.scale - y.scale, if that many digits are enough.
func divideMC(x, y *BigDecimal, mc mathContext) *BigDecimal {
	if mc.prec == 0 {
		return divideExact(x, y)
	}
	checkDecimalDivisor(x, y)
	preferred := int64(x.scale) - int64(y.scale)
	if x.sign() == 0 {
		return bdOf(0, saturateScale(preferred))
	}
	a, b := x.unscaled(), y.unscaled()
	xp, yp := int64(x.precision()), int64(y.precision())
	// q = a × 10^t / b has mc.prec digits.
	t := int64(mc.prec) + yp - xp
	cmp := compareSignificands(a, xp, b, yp)
	if cmp > 0 {
		t--
	}
	// The JDK's checks of the quotient's scale before rounding and of the preferred scale.
	checkScaleNonZero(preferred + t)
	checkScaleNonZero(preferred)
	if cmp == 0 {
		t--
	}
	var num, den *big.Int
	if t >= 0 {
		num, den = bigTimesPow10(a, t), b
	} else {
		num, den = a, bigTimesPow10(b, -t)
	}
	q, r := new(big.Int).QuoRem(num, den, new(big.Int))
	scale := preferred + t
	if r.Sign() != 0 {
		sign := a.Sign() * b.Sign()
		if roundsAway(mc.mode, sign, r.Lsh(r, 1).CmpAbs(den), q.Bit(0) == 1) {
			q.Add(q, big.NewInt(int64(sign)))
			if q.CmpAbs(pow10Big(int64(mc.prec))) == 0 {
				q.Quo(q, big10)
				scale--
			}
		}
		return bdOfBig(q, checkScaleNonZero(scale))
	}
	// Exact: remove trailing zeros down to the preferred scale.
	rem := new(big.Int)
	quo := new(big.Int)
	for scale > preferred {
		quo.QuoRem(q, big10, rem)
		if rem.Sign() != 0 {
			break
		}
		q, quo = quo, q
		scale--
	}
	return bdOfBig(q, checkScaleNonZero(scale))
}

// compareSignificands compares |a| / 10^ap and |b| / 10^bp.
func compareSignificands(a *big.Int, ap int64, b *big.Int, bp int64) int {
	if ap >= bp {
		return a.CmpAbs(bigTimesPow10(b, ap-bp))
	}
	return bigTimesPow10(a, bp-ap).CmpAbs(b)
}

// divideToScale is divide(divisor, scale, mode).
func divideToScale(x, y *BigDecimal, scale int32, mode int32) *BigDecimal {
	// x/y = (a × 10^raise / b) × 10^-scale
	raise := int64(scale) - int64(x.scale) + int64(y.scale)
	if y.sign() == 0 {
		divideToScaleByZero(x, y, raise)
	}
	if x.big == nil && y.big == nil {
		if raise >= 0 {
			if a, ok := mulPow10(x.small, raise); ok && !(a == math.MinInt64 && y.small == -1) {
				return bdOf(divRound64(a, y.small, mode), scale)
			}
		} else if b, ok := mulPow10(y.small, -raise); ok && !(x.small == math.MinInt64 && b == -1) {
			return bdOf(divRound64(x.small, b, mode), scale)
		}
	}
	if raise >= 0 {
		return bdOfBig(divRoundBig(bigTimesPow10(x.unscaled(), raise), y.unscaled(), mode), scale)
	}
	return bdOfBig(divRoundBig(x.unscaled(), bigTimesPow10(y.unscaled(), -raise), mode), scale)
}

// divideToScaleByZero throws the JDK's exception: it divides longs when both operands and the
// scaled one fit in a long (the JDK keeps math.MinInt64 in a BigInteger), and BigIntegers otherwise.
func divideToScaleByZero(x, y *BigDecimal, raise int64) {
	if x.big == nil && y.big == nil && x.small != math.MinInt64 && y.small != math.MinInt64 && raise < 19 && raise > -19 {
		if raise <= 0 {
			ThrowKind(Arithmetic, "/ by zero")
		}
		if _, ok := mulPow10(x.small, raise); ok {
			ThrowKind(Arithmetic, "/ by zero")
		}
	}
	ThrowKind(Arithmetic, "BigInteger divide by zero")
}

func checkLegacyRoundingMode(mode int32) int32 {
	if mode < RoundUp || mode > RoundUnnecessary {
		ThrowKind(IllegalArgument, "Invalid rounding mode")
	}
	return mode
}

// divideToIntegralValue is the integer part of x/y, with the preferred scale x.scale - y.scale
// or the smallest scale that represents it, whichever is greater.
func divideToIntegralValue(x, y *BigDecimal) *BigDecimal {
	preferred := int64(x.scale) - int64(y.scale)
	if compareMagnitude(x, y) < 0 {
		return bdOf(0, saturateScale(preferred))
	}
	checkDecimalDivisor(x, y)
	var q *big.Int
	if diff := int64(y.scale) - int64(x.scale); diff >= 0 {
		q = new(big.Int).Quo(bigTimesPow10(x.unscaled(), diff), y.unscaled())
	} else {
		q = new(big.Int).Quo(x.unscaled(), bigTimesPow10(y.unscaled(), -diff))
	}
	r := bdOfBig(q, 0)
	if preferred < 0 {
		return r.stripZeros(preferred)
	}
	return r.setScale(saturateScale(preferred), RoundUnnecessary)
}

func divideToIntegralValueMC(x, y *BigDecimal, mc mathContext) *BigDecimal {
	if mc.prec == 0 || compareMagnitude(x, y) < 0 {
		return divideToIntegralValue(x, y)
	}
	preferred := int64(x.scale) - int64(y.scale)
	// Divide to mc.prec digits; if the remainder is less than the divisor, the integer part fits.
	r := divideMC(x, y, mathContext{mc.prec, RoundDown})
	if r.scale < 0 {
		if compareMagnitude(add(x, multiply(r, y), true), y) >= 0 {
			ThrowKind(Arithmetic, "Division impossible")
		}
	} else if r.scale > 0 {
		r = r.setScale(0, RoundDown)
	}
	if precisionDiff := int64(mc.prec) - int64(r.precision()); preferred > int64(r.scale) && precisionDiff > 0 {
		return r.setScale(int32(int64(r.scale)+min(precisionDiff, preferred-int64(r.scale))), RoundUnnecessary)
	}
	return r.stripZeros(preferred)
}

// ---- Powers and square roots ----------------------------------------------------------------

func pow(x *BigDecimal, n int32) *BigDecimal {
	if n < 0 || n > 999999999 {
		ThrowKind(Arithmetic, "Invalid operation")
	}
	scale := checkScale(int64(x.scale)*int64(n), x.sign() == 0)
	if x.big == nil {
		if p, ok := pow64(x.small, n); ok {
			return bdOf(p, scale)
		}
	}
	return bdOfBig(new(big.Int).Exp(x.unscaled(), big.NewInt(int64(n)), nil), scale)
}

// pow64 is x^n, if it fits in an int64.
func pow64(x int64, n int32) (int64, bool) {
	result := int64(1)
	for ; n > 0; n >>= 1 {
		var ok bool
		if n&1 != 0 {
			if result, ok = mul64(result, x); !ok {
				return 0, false
			}
		}
		if n > 1 {
			if x, ok = mul64(x, x); !ok {
				return 0, false
			}
		}
	}
	return result, true
}

// powMC is pow(n, mc), with the X3.274 algorithm the JDK specifies: binary exponentiation at a
// working precision of mc.prec + digits(n) + 1, then rounding.
func powMC(x *BigDecimal, n int32, mc mathContext) *BigDecimal {
	if mc.prec == 0 {
		return pow(x, n)
	}
	if n < -999999999 || n > 999999999 {
		ThrowKind(Arithmetic, "Invalid operation")
	}
	if n == 0 {
		return BigDecimalOne
	}
	mag := n
	if mag < 0 {
		mag = -mag
	}
	elength := digitsU(uint64(mag))
	if elength > mc.prec {
		ThrowKind(Arithmetic, "Invalid operation")
	}
	work := mathContext{mc.prec + elength + 1, mc.mode}
	acc := BigDecimalOne
	seen := false
	for i := 1; ; i++ {
		mag += mag // the top bit goes into the sign
		if mag < 0 {
			seen = true
			acc = multiplyMC(acc, x, work)
		}
		if i == 31 {
			break
		}
		if seen {
			acc = multiplyMC(acc, acc, work)
		}
	}
	if n < 0 {
		acc = divideMC(BigDecimalOne, acc, work)
	}
	return acc.round(mc)
}

// sqrt is sqrt(mc): correctly rounded to mc.prec digits (exact for precision 0), with the scale
// closest to the preferred scale scale/2 that the digits and the precision allow.
func sqrt(x *BigDecimal, mc mathContext) *BigDecimal {
	switch x.sign() {
	case -1:
		ThrowKind(Arithmetic, "Attempted square root of negative BigDecimal")
	case 0:
		return bdOf(0, x.scale/2)
	}
	preferred := int64(x.scale / 2)
	// x = u × 10^-s with s even.
	u, s := x.unscaled(), int64(x.scale)
	if s%2 != 0 {
		u, s = bigTimesPow10(u, 1), s+1
	}
	var root *BigDecimal
	if mc.prec == 0 {
		r := new(big.Int).Sqrt(u)
		if new(big.Int).Mul(r, r).Cmp(u) != 0 {
			ThrowKind(Arithmetic, "Computed square root not exact.")
		}
		root = bdOfBig(r, checkScale(s/2, false))
	} else {
		// sqrt(u × 10^2k) has at least mc.prec digits; the digits beyond are rounded off.
		k := max(0, (2*int64(mc.prec)-int64(digitsBig(u))+1)/2)
		n := bigTimesPow10(u, 2*k)
		r := new(big.Int).Sqrt(n)
		inexactRoot := new(big.Int).Mul(r, r).Cmp(n) != 0
		e := int64(digitsBig(r)) - int64(mc.prec)
		scale := s/2 + k - e
		if e > 0 {
			q, m := new(big.Int).QuoRem(r, pow10Big(e), new(big.Int))
			if m.Sign() != 0 || inexactRoot {
				sqrtInexact(mc)
				// Compare the discarded part (m plus the root's own fraction) with half of 10^e.
				half := m.Lsh(m, 1).Cmp(pow10Big(e))
				if half == 0 && inexactRoot {
					half = 1
				}
				if roundsAway(mc.mode, 1, half, q.Bit(0) == 1) {
					q.Add(q, big.NewInt(1))
					if q.Cmp(pow10Big(int64(mc.prec))) == 0 {
						q.Quo(q, big10)
						scale--
					}
				}
			}
			r = q
		} else if inexactRoot {
			sqrtInexact(mc)
			// The fraction is never exactly one half: compare 4n with (2r + 1)².
			r2 := new(big.Int).Lsh(r, 1)
			r2.Add(r2, big.NewInt(1))
			half := new(big.Int).Lsh(n, 2).Cmp(r2.Mul(r2, r2))
			if roundsAway(mc.mode, 1, half, r.Bit(0) == 1) {
				r.Add(r, big.NewInt(1))
				if r.Cmp(pow10Big(int64(mc.prec))) == 0 {
					r.Quo(r, big10)
					scale--
				}
			}
		}
		root = bdOfBig(r, checkScaleNonZero(scale))
	}
	if int64(root.scale) > preferred {
		return root.stripZeros(preferred)
	}
	if int64(root.scale) < preferred {
		target := preferred
		if mc.prec > 0 {
			target = min(preferred, int64(root.scale)+int64(mc.prec)-int64(root.precision()))
		}
		return root.setScale(int32(target), RoundUnnecessary)
	}
	return root
}

func sqrtInexact(mc mathContext) {
	if mc.mode == RoundUnnecessary {
		ThrowKind(Arithmetic, "Computed square root not exact.")
	}
}

// ---- Natives: arithmetic --------------------------------------------------------------------

func BigDecimalAdd(x, y *BigDecimal) *BigDecimal                  { return add(x, y, false) }
func BigDecimalSubtract(x, y *BigDecimal) *BigDecimal             { return add(x, y, true) }
func BigDecimalMultiply(x, y *BigDecimal) *BigDecimal             { return multiply(x, y) }
func BigDecimalAddMC(x, y *BigDecimal, mc int64) *BigDecimal      { return addMC(x, y, false, mcOf(mc)) }
func BigDecimalSubtractMC(x, y *BigDecimal, mc int64) *BigDecimal { return addMC(x, y, true, mcOf(mc)) }
func BigDecimalMultiplyMC(x, y *BigDecimal, mc int64) *BigDecimal {
	return multiplyMC(x, y, mcOf(mc))
}

func BigDecimalDivide(x, y *BigDecimal) *BigDecimal             { return divideExact(x, y) }
func BigDecimalDivideMC(x, y *BigDecimal, mc int64) *BigDecimal { return divideMC(x, y, mcOf(mc)) }

func BigDecimalDivideToScale(x, y *BigDecimal, scale int32, mode int32) *BigDecimal {
	return divideToScale(x, y, scale, mode)
}

func BigDecimalDivideRounding(x, y *BigDecimal, mode int32) *BigDecimal {
	return divideToScale(x, y, x.scale, mode)
}

func BigDecimalLegacyRoundingMode(mode int32) int32 { return checkLegacyRoundingMode(mode) }

func BigDecimalDivideToIntegralValue(x, y *BigDecimal) *BigDecimal {
	return divideToIntegralValue(x, y)
}

func BigDecimalDivideToIntegralValueMC(x, y *BigDecimal, mc int64) *BigDecimal {
	return divideToIntegralValueMC(x, y, mcOf(mc))
}

func BigDecimalRemainder(x, y *BigDecimal) *BigDecimal {
	return add(x, multiply(divideToIntegralValue(x, y), y), true)
}

func BigDecimalRemainderMC(x, y *BigDecimal, mc int64) *BigDecimal {
	return add(x, multiply(divideToIntegralValueMC(x, y, mcOf(mc)), y), true)
}

func BigDecimalDivideAndRemainder(x, y *BigDecimal) *Array[any] {
	q := divideToIntegralValue(x, y)
	return &Array[any]{Data: []any{q, add(x, multiply(q, y), true)}}
}

func BigDecimalDivideAndRemainderMC(x, y *BigDecimal, mc int64) *Array[any] {
	q := divideToIntegralValueMC(x, y, mcOf(mc))
	return &Array[any]{Data: []any{q, add(x, multiply(q, y), true)}}
}

func BigDecimalPow(x *BigDecimal, n int32) *BigDecimal             { return pow(x, n) }
func BigDecimalPowMC(x *BigDecimal, n int32, mc int64) *BigDecimal { return powMC(x, n, mcOf(mc)) }
func BigDecimalSqrt(x *BigDecimal, mc int64) *BigDecimal           { return sqrt(x, mcOf(mc)) }
func BigDecimalNegate(x *BigDecimal) *BigDecimal                   { return x.negate() }
func BigDecimalNegateMC(x *BigDecimal, mc int64) *BigDecimal       { return x.negate().round(mcOf(mc)) }
func BigDecimalRound(x *BigDecimal, mc int64) *BigDecimal          { return x.round(mcOf(mc)) }
func BigDecimalSetScale(x *BigDecimal, scale int32, mode int32) *BigDecimal {
	return x.setScale(scale, mode)
}

func BigDecimalAbs(x *BigDecimal) *BigDecimal {
	if x.sign() < 0 {
		return x.negate()
	}
	return x
}

func BigDecimalAbsMC(x *BigDecimal, mc int64) *BigDecimal { return BigDecimalAbs(x).round(mcOf(mc)) }

func BigDecimalSignum(x *BigDecimal) int32    { return int32(x.sign()) }
func BigDecimalScale(x *BigDecimal) int32     { return x.scale }
func BigDecimalPrecision(x *BigDecimal) int32 { return x.precision() }
func BigDecimalUlp(x *BigDecimal) *BigDecimal { return bdOf(1, x.scale) }

func BigDecimalUnscaledValue(x *BigDecimal) *BigInteger {
	if x.big == nil {
		return BigIntegerValueOf(x.small)
	}
	return bigOf(x.big)
}

// movePoint moves the decimal point; the result's scale is not negative.
func movePoint(x *BigDecimal, newScale int64) *BigDecimal {
	r := &BigDecimal{small: x.small, big: x.big, scale: checkScale(newScale, x.sign() == 0)}
	if r.scale < 0 {
		return r.setScale(0, RoundUnnecessary)
	}
	return r
}

func BigDecimalMovePointLeft(x *BigDecimal, n int32) *BigDecimal {
	return movePoint(x, int64(x.scale)+int64(n))
}

func BigDecimalMovePointRight(x *BigDecimal, n int32) *BigDecimal {
	return movePoint(x, int64(x.scale)-int64(n))
}

func BigDecimalScaleByPowerOfTen(x *BigDecimal, n int32) *BigDecimal {
	return &BigDecimal{small: x.small, big: x.big, scale: checkScale(int64(x.scale)-int64(n), x.sign() == 0)}
}

func BigDecimalStripTrailingZeros(x *BigDecimal) *BigDecimal {
	if x.sign() == 0 {
		return BigDecimalZero
	}
	return x.stripZeros(math.MinInt64)
}

func BigDecimalCompareToObject(x *BigDecimal, o any) int32 {
	return BigDecimalCompareTo(x, Cast[*BigDecimal](o))
}
