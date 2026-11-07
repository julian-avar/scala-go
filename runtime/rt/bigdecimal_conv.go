package rt

import (
	"math"
	"math/big"
	"math/bits"
	"strconv"
	"unicode"
)

// Constructors, conversions and the string forms of java.math.BigDecimal (see bigdecimal.go).

// ---- Parsing --------------------------------------------------------------------------------

func decimalFormatError(message string) { ThrowKind(NumberFormat, message) }

// parseBigDecimal parses in[offset : offset+length] like the JDK, with its error messages. Like
// the JDK, it may read past length (not past the array) when an exponent is incomplete.
func parseBigDecimal(in []uint16, offset, length int) *BigDecimal {
	at := func(i int) uint16 {
		if i >= len(in) { // the JDK's ArrayIndexOutOfBoundsException becomes a bare NumberFormatException
			decimalFormatError("")
		}
		return in[i]
	}
	i := offset
	negative := false
	switch at(i) {
	case '-':
		negative = true
		i, length = i+1, length-1
	case '+':
		i, length = i+1, length-1
	}
	compact := length <= 18 // the JDK parses longer inputs separately, with other messages
	var buf [32]byte
	digits := buf[:0]
	dot := false
	var fractionDigits, exp int64
	for ; length > 0; i, length = i+1, length-1 {
		c := in[i]
		d := -1
		switch {
		case c >= '0' && c <= '9':
			d = int(c - '0')
		case c == '.':
			if dot {
				decimalFormatError("Character array contains more than one decimal point.")
			}
			dot = true
			continue
		case c == 'e' || c == 'E':
			exp = parseExponent(in, i, length, at)
			length = 1 // ends the loop
			continue
		default:
			if d = unicodeDigit(c); d < 0 {
				if !compact {
					decimalFormatError("Character array is missing \"e\" notation exponential mark.")
				}
				decimalFormatError("Character " + string(rune(c)) +
					" is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark.")
			}
		}
		digits = append(digits, byte('0'+d))
		if dot {
			fractionDigits++
		}
	}
	if len(digits) == 0 {
		decimalFormatError("No digits found.")
	}
	scale := fractionDigits - exp
	if scale != int64(int32(scale)) {
		decimalFormatError("Exponent overflow.")
	}
	if len(digits) <= 18 {
		var v int64
		for _, c := range digits {
			v = v*10 + int64(c-'0')
		}
		if negative {
			v = -v
		}
		return bdOf(v, int32(scale))
	}
	v, _ := new(big.Int).SetString(string(digits), 10)
	if negative {
		v.Neg(v)
	}
	return bdOfBig(v, int32(scale))
}

// parseExponent parses the exponent starting at in[i] ('e' or 'E'), length chars remaining.
func parseExponent(in []uint16, i, length int, at func(int) uint16) int64 {
	i++
	c := at(i)
	length--
	negative := c == '-'
	if negative || c == '+' {
		i++
		c = at(i)
		length--
	}
	if length <= 0 {
		decimalFormatError("No exponent digits.")
	}
	for length > 10 && (c == '0' || unicodeDigit(c) == 0) { // leading zeros
		i++
		c = at(i)
		length--
	}
	if length > 10 {
		decimalFormatError("Too many nonzero exponent digits.")
	}
	var exp int64
	for ; ; length-- {
		d := unicodeDigit(c)
		if d < 0 {
			decimalFormatError("Not a digit.")
		}
		exp = exp*10 + int64(d)
		if length == 1 {
			break
		}
		i++
		c = at(i)
	}
	if negative {
		return -exp
	}
	return exp
}

// unicodeDigit is Character.digit(c, 10): the value of a decimal digit of any script, or -1.
func unicodeDigit(c uint16) int {
	if c >= '0' && c <= '9' {
		return int(c - '0')
	}
	if c < 0x80 {
		return -1
	}
	// Decimal digits (category Nd) come in runs of ten, from zero to nine.
	for _, r := range unicode.Nd.R16 {
		if c >= r.Lo && c <= r.Hi {
			return int(c-r.Lo) % 10
		}
	}
	return -1
}

func NewBigDecimalFromString(s *String) *BigDecimal {
	return parseBigDecimal(s.chars, 0, len(s.chars))
}

func NewBigDecimalFromStringMC(s *String, mc int64) *BigDecimal {
	return roundParsed(NewBigDecimalFromString(s), mcOf(mc))
}

// roundParsed rounds a parsed value; a scale out of range is a NumberFormatException then.
func roundParsed(d *BigDecimal, mc mathContext) *BigDecimal {
	if mc.prec > 0 && d.precision() > mc.prec && int64(d.scale)-int64(d.precision()-mc.prec) < math.MinInt32 {
		decimalFormatError("Exponent overflow.")
	}
	return d.round(mc)
}

func NewBigDecimalFromChars(a *Array[uint16]) *BigDecimal {
	return parseBigDecimal(a.Data, 0, len(a.Data))
}

func NewBigDecimalFromCharsMC(a *Array[uint16], mc int64) *BigDecimal {
	return roundParsed(NewBigDecimalFromChars(a), mcOf(mc))
}

func NewBigDecimalFromCharRange(a *Array[uint16], offset, length int32) *BigDecimal {
	n := int64(len(a.Data))
	if offset < 0 || length < 0 || int64(offset)+int64(length) > n {
		decimalFormatError("Bad offset or len arguments for char[] input.")
	}
	return parseBigDecimal(a.Data, int(offset), int(length))
}

func NewBigDecimalFromCharRangeMC(a *Array[uint16], offset, length int32, mc int64) *BigDecimal {
	return roundParsed(NewBigDecimalFromCharRange(a, offset, length), mcOf(mc))
}

// ---- Other constructors ---------------------------------------------------------------------

func NewBigDecimalFromLong(v int64) *BigDecimal             { return bdOf(v, 0) }
func NewBigDecimalFromLongMC(v int64, mc int64) *BigDecimal { return bdOf(v, 0).round(mcOf(mc)) }
func BigDecimalValueOf(v int64, scale int32) *BigDecimal    { return bdOf(v, scale) }
func NewBigDecimalFromBigInteger(b *BigInteger) *BigDecimal { return bdOfBig(&b.v, 0) }
func NewBigDecimalFromUnscaled(b *BigInteger, scale int32) *BigDecimal {
	return bdOfBig(&b.v, scale) // BigIntegers are immutable too, so the value is shared
}

func NewBigDecimalFromBigIntegerMC(b *BigInteger, mc int64) *BigDecimal {
	return bdOfBig(&b.v, 0).round(mcOf(mc))
}

func NewBigDecimalFromUnscaledMC(b *BigInteger, scale int32, mc int64) *BigDecimal {
	return bdOfBig(&b.v, scale).round(mcOf(mc))
}

// NewBigDecimalFromDouble is new BigDecimal(double): the exact binary value.
func NewBigDecimalFromDouble(d float64) *BigDecimal {
	if math.IsNaN(d) || math.IsInf(d, 0) {
		decimalFormatError("Infinite or NaN")
	}
	if d == 0 {
		return bdOf(0, 0)
	}
	b := math.Float64bits(d)
	exp := int(b>>52) & 0x7ff
	mantissa := b & (1<<52 - 1)
	if exp == 0 {
		exp = 1 // subnormal
	} else {
		mantissa |= 1 << 52
	}
	exp -= 1075 // d = ±mantissa × 2^exp
	tz := bits.TrailingZeros64(mantissa)
	mantissa >>= tz
	exp += tz
	v := new(big.Int).SetUint64(mantissa)
	if d < 0 {
		v.Neg(v)
	}
	if exp >= 0 {
		return bdOfBig(v.Lsh(v, uint(exp)), 0)
	}
	// mantissa / 2^-exp = mantissa × 5^-exp / 10^-exp
	return bdOfBig(v.Mul(v, new(big.Int).Exp(big.NewInt(5), big.NewInt(int64(-exp)), nil)), int32(-exp))
}

func NewBigDecimalFromDoubleMC(d float64, mc int64) *BigDecimal {
	return NewBigDecimalFromDouble(d).round(mcOf(mc))
}

// BigDecimalValueOfDouble is BigDecimal.valueOf(double): the value of Double.toString(d).
func BigDecimalValueOfDouble(d float64) *BigDecimal {
	if math.IsNaN(d) || math.IsInf(d, 0) {
		decimalFormatError("Infinite or NaN")
	}
	chars := asciiString(formatFloating(d, 64)).chars
	return parseBigDecimal(chars, 0, len(chars))
}

// ---- Conversions ----------------------------------------------------------------------------

// toBigInt is the integer part, as a new big.Int.
func (d *BigDecimal) toBigInt() *big.Int {
	switch {
	case d.scale <= 0:
		return bigTimesPow10(d.unscaled(), -int64(d.scale))
	case int64(d.scale) > int64(d.precision()): // |d| < 1
		return new(big.Int)
	}
	return new(big.Int).Quo(d.unscaled(), pow10Big(int64(d.scale)))
}

func BigDecimalToBigInteger(d *BigDecimal) *BigInteger {
	if d.big == nil && d.scale == 0 {
		return BigIntegerValueOf(d.small)
	}
	r := &BigInteger{}
	r.v.Set(d.toBigInt())
	return r
}

func BigDecimalToBigIntegerExact(d *BigDecimal) *BigInteger {
	return BigDecimalToBigInteger(d.setScale(0, RoundUnnecessary))
}

// BigDecimalLongValue is the integer part's low 64 bits.
func BigDecimalLongValue(d *BigDecimal) int64 {
	switch {
	case d.big == nil && d.scale == 0:
		return d.small
	case d.sign() == 0 || int64(d.precision())-int64(d.scale) <= 0:
		return 0
	case d.scale <= -64: // a multiple of 10^64, hence of 2^64
		return 0
	case d.big == nil && d.scale < 0 && d.scale > -19:
		return d.small * int64(pow10u[-d.scale]) // wraps, keeping the low bits
	case d.big == nil && d.scale > 0 && d.scale < 19:
		return d.small / int64(pow10u[d.scale])
	}
	return low64(d.toBigInt())
}

// low64 is the two's-complement low 64 bits of x.
func low64(x *big.Int) int64 {
	var u uint64
	words := x.Bits()
	if bits.UintSize == 64 {
		if len(words) > 0 {
			u = uint64(words[0])
		}
	} else {
		for i := 0; i < len(words) && i < 2; i++ {
			u |= uint64(words[i]) << (32 * i)
		}
	}
	if x.Sign() < 0 {
		u = -u
	}
	return int64(u)
}

func BigDecimalIntValue(d *BigDecimal) int32 { return int32(BigDecimalLongValue(d)) }

func BigDecimalLongValueExact(d *BigDecimal) int64 {
	if d.big == nil && d.scale == 0 {
		return d.small
	}
	if d.sign() == 0 {
		return 0
	}
	integerDigits := int64(d.precision()) - int64(d.scale)
	if integerDigits <= 0 {
		ThrowKind(Arithmetic, "Rounding necessary")
	}
	if integerDigits > 19 {
		ThrowKind(Arithmetic, "Overflow")
	}
	n := d.setScale(0, RoundUnnecessary)
	if n.big != nil {
		ThrowKind(Arithmetic, "Overflow")
	}
	return n.small
}

func BigDecimalIntValueExact(d *BigDecimal) int32 {
	n := BigDecimalLongValueExact(d)
	if int64(int32(n)) != n {
		ThrowKind(Arithmetic, "Overflow")
	}
	return int32(n)
}

func BigDecimalShortValueExact(d *BigDecimal) int16 {
	n := BigDecimalLongValueExact(d)
	if int64(int16(n)) != n {
		ThrowKind(Arithmetic, "Overflow")
	}
	return int16(n)
}

func BigDecimalByteValueExact(d *BigDecimal) int8 {
	n := BigDecimalLongValueExact(d)
	if int64(int8(n)) != n {
		ThrowKind(Arithmetic, "Overflow")
	}
	return int8(n)
}

var pow10f = [...]float64{1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14,
	1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22}

// BigDecimalDoubleValue is the nearest double (ties to even), as in the JDK.
func BigDecimalDoubleValue(d *BigDecimal) float64 {
	if d.big == nil {
		// Exact operands give a correctly rounded product or quotient.
		if u := absU(d.small); u < 1<<53 {
			switch {
			case d.scale == 0:
				return float64(d.small)
			case d.scale > 0 && d.scale < int32(len(pow10f)):
				return float64(d.small) / pow10f[d.scale]
			case d.scale < 0 && d.scale > -int32(len(pow10f)):
				return float64(d.small) * pow10f[-d.scale]
			}
		}
	}
	f, _ := strconv.ParseFloat(d.scientific(), 64) // correctly rounded; ±Inf or 0 out of range
	return f
}

func BigDecimalFloatValue(d *BigDecimal) float32 {
	if d.big == nil {
		if u := absU(d.small); u < 1<<24 {
			switch {
			case d.scale == 0:
				return float32(d.small)
			case d.scale > 0 && d.scale <= 10:
				return float32(d.small) / float32(pow10f[d.scale])
			case d.scale < 0 && d.scale >= -10:
				return float32(d.small) * float32(pow10f[-d.scale])
			}
		}
	}
	f, _ := strconv.ParseFloat(d.scientific(), 32)
	return float32(f)
}

// ---- String forms ---------------------------------------------------------------------------

// coefficient appends the decimal digits of the unscaled value's magnitude.
func (d *BigDecimal) coefficient(buf []byte) []byte {
	if d.big == nil {
		return strconv.AppendUint(buf, absU(d.small), 10)
	}
	return new(big.Int).Abs(d.big).Append(buf, 10)
}

// scientific is "<unscaled>e<-scale>", for strconv.ParseFloat.
func (d *BigDecimal) scientific() string {
	var buf [40]byte
	b := buf[:0]
	if d.sign() < 0 {
		b = append(b, '-')
	}
	b = d.coefficient(b)
	b = append(b, 'e')
	return string(strconv.AppendInt(b, -int64(d.scale), 10))
}

// layout appends toString (sci) or toEngineeringString to b.
func (d *BigDecimal) layout(b []byte, sci bool) []byte {
	if d.sign() < 0 {
		b = append(b, '-')
	}
	start := len(b)
	b = d.coefficient(b)
	coeff := b[start:]
	if d.scale == 0 {
		return b
	}
	n := int64(len(coeff))
	adjusted := -int64(d.scale) + n - 1
	if d.scale > 0 && adjusted >= -6 { // plain notation
		return insertPoint(b[:start], coeff, int64(d.scale))
	}
	digits := append([]byte(nil), coeff...)
	out := b[:start]
	if sci {
		out = append(out, digits[0])
		if n > 1 {
			out = append(out, '.')
			out = append(out, digits[1:]...)
		}
	} else {
		sig := int(adjusted % 3)
		if sig < 0 {
			sig += 3
		}
		adjusted -= int64(sig)
		sig++
		switch {
		case d.sign() == 0:
			switch sig {
			case 1:
				out = append(out, '0')
			case 2:
				out = append(out, "0.00"...)
				adjusted += 3
			case 3:
				out = append(out, "0.0"...)
				adjusted += 3
			}
		case int64(sig) >= n:
			out = append(out, digits...)
			for range int64(sig) - n {
				out = append(out, '0')
			}
		default:
			out = append(out, digits[:sig]...)
			out = append(out, '.')
			out = append(out, digits[sig:]...)
		}
	}
	if adjusted != 0 {
		out = append(out, 'E')
		if adjusted > 0 {
			out = append(out, '+')
		}
		out = strconv.AppendInt(out, adjusted, 10)
	}
	return out
}

// insertPoint appends coeff (which may share out's storage) with a decimal point scale (> 0)
// digits from the right.
func insertPoint(out, coeff []byte, scale int64) []byte {
	var buf [40]byte
	digits := append(buf[:0], coeff...)
	n := int64(len(digits))
	if pad := scale - n; pad >= 0 {
		out = append(out, '0', '.')
		for range pad {
			out = append(out, '0')
		}
		return append(out, digits...)
	}
	out = append(out, digits[:n-scale]...)
	out = append(out, '.')
	return append(out, digits[n-scale:]...)
}

func BigDecimalToString(d *BigDecimal) *String {
	var buf [40]byte
	return asciiString(d.layout(buf[:0], true))
}

func BigDecimalToEngineeringString(d *BigDecimal) *String {
	var buf [40]byte
	return asciiString(d.layout(buf[:0], false))
}

func BigDecimalToPlainString(d *BigDecimal) *String {
	var buf [40]byte
	b := buf[:0]
	if d.sign() < 0 {
		b = append(b, '-')
	}
	start := len(b)
	b = d.coefficient(b)
	switch {
	case d.scale == 0:
	case d.scale < 0:
		if d.sign() == 0 {
			return asciiString("0")
		}
		for range -int64(d.scale) {
			b = append(b, '0')
		}
	default:
		b = insertPoint(b[:start], b[start:], int64(d.scale))
	}
	return asciiString(b)
}

// asciiString converts ASCII text.
func asciiString[T string | []byte](s T) *String {
	chars := make([]uint16, len(s))
	for i := range len(s) {
		chars[i] = uint16(s[i])
	}
	return newString(chars)
}
