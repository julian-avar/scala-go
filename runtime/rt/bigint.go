package rt

import "math/big"

// BigInteger is java.math.BigInteger, backed by math/big. Values are immutable.
type BigInteger struct {
	v big.Int
}

func bigOf(v *big.Int) *BigInteger {
	b := &BigInteger{}
	b.v.Set(v)
	return b
}

var (
	BigZero = BigIntegerValueOf(0)
	BigOne  = BigIntegerValueOf(1)
	BigTwo  = BigIntegerValueOf(2)
	BigTen  = BigIntegerValueOf(10)
)

func (b *BigInteger) String() string { return b.v.String() }

func BigIntegerValueOf(x int64) *BigInteger { return bigOf(big.NewInt(x)) }

// bigDigitsPerInt[radix] is the length of the groups of digits the JDK's BigInteger(String, int)
// parses with Integer.parseInt (the last ones; the first is shorter): its exceptions name the
// group with the bad digit.
var bigDigitsPerInt = [maxRadix + 1]int{0, 0, 30, 19, 15, 13, 11, 11, 10, 9, 9, 8, 8, 8, 8, 7, 7, 7, 7, 7, 7,
	7, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 5}

// NewBigIntegerFromString is new BigInteger(s, radix): an optional sign, then digits as
// Character.digit reads them (any Unicode decimal digit), with the JDK's exceptions.
func NewBigIntegerFromString(s *String, radix int32) *BigInteger {
	if radix < minRadix || radix > maxRadix {
		ThrowKind(NumberFormat, "Radix out of range")
	}
	chars := s.chars
	minus, plus := -1, -1 // the last ones
	for i, c := range chars {
		switch c {
		case '-':
			minus = i
		case '+':
			plus = i
		}
	}
	start := 0
	if minus > 0 || plus > 0 {
		ThrowKind(NumberFormat, "Illegal embedded sign character")
	} else if minus == 0 || plus == 0 {
		start = 1
	}
	if start == len(chars) {
		ThrowKind(NumberFormat, "Zero length BigInteger")
	}
	for start < len(chars) && CharDigit(int32(chars[start]), radix) == 0 {
		start++
	}
	digits := make([]byte, 0, len(chars)-start+1)
	if minus == 0 {
		digits = append(digits, '-')
	}
	for i := start; i < len(chars); i++ {
		d := CharDigit(int32(chars[i]), radix)
		if d < 0 {
			group := bigDigitsPerInt[radix]
			first := start + (len(chars)-start-1)%group + 1 // end of the first group
			from, to := start, first
			if i >= first {
				from = first + (i-first)/group*group
				to = from + group
			}
			numberFormat(decodeUTF16(chars[from:to]), radix)
		}
		digits = append(digits, byte(CharForDigit(d, radix)))
	}
	v, ok := new(big.Int).SetString(string(digits), int(radix))
	if !ok { // no digits after leading zeros
		return BigZero
	}
	return bigOf(v)
}

// NewBigIntegerFromBytes reads a two's-complement big-endian byte array.
func NewBigIntegerFromBytes(a *Array[int8]) *BigInteger {
	if len(a.Data) == 0 {
		ThrowKind(NumberFormat, "Zero length BigInteger")
	}
	bytes := make([]byte, len(a.Data))
	for i, x := range a.Data {
		bytes[i] = byte(x)
	}
	v := new(big.Int).SetBytes(bytes)
	if a.Data[0] < 0 {
		v.Sub(v, new(big.Int).Lsh(big.NewInt(1), uint(8*len(bytes))))
	}
	return bigOf(v)
}

func NewBigIntegerFromMagnitude(signum int32, a *Array[int8]) *BigInteger {
	bytes := make([]byte, len(a.Data))
	for i, x := range a.Data {
		bytes[i] = byte(x)
	}
	v := new(big.Int).SetBytes(bytes)
	switch {
	case signum < -1 || signum > 1:
		ThrowKind(NumberFormat, "Invalid signum value")
	case signum == 0 && v.Sign() != 0:
		ThrowKind(NumberFormat, "signum-magnitude mismatch")
	case signum < 0:
		v.Neg(v)
	}
	return bigOf(v)
}

// BigIntegerToByteArray is the shortest two's complement, sign bit included.
func BigIntegerToByteArray(b *BigInteger) *Array[int8] {
	n := BigIntegerBitLength(b)/8 + 1
	mod := new(big.Int).Lsh(big.NewInt(1), uint(8*n))
	u := new(big.Int).Mod(&b.v, mod) // two's complement in n bytes
	bytes := u.FillBytes(make([]byte, n))
	out := make([]int8, n)
	for i, x := range bytes {
		out[i] = int8(x)
	}
	return &Array[int8]{Data: out}
}

func binary(op func(z, x, y *big.Int) *big.Int) func(a, b *BigInteger) *BigInteger {
	return func(a, b *BigInteger) *BigInteger { return bigOf(op(new(big.Int), &a.v, &b.v)) }
}

var (
	BigIntegerAdd      = binary((*big.Int).Add)
	BigIntegerSubtract = binary((*big.Int).Sub)
	BigIntegerMultiply = binary((*big.Int).Mul)
	BigIntegerAnd      = binary((*big.Int).And)
	BigIntegerOr       = binary((*big.Int).Or)
	BigIntegerXor      = binary((*big.Int).Xor)
	BigIntegerAndNot   = binary((*big.Int).AndNot)
)

func checkDivisor(b *BigInteger) {
	if b.v.Sign() == 0 {
		ThrowKind(Arithmetic, "BigInteger divide by zero")
	}
}

// BigIntegerDivide truncates toward zero, like Java.
func BigIntegerDivide(a, b *BigInteger) *BigInteger {
	checkDivisor(b)
	return bigOf(new(big.Int).Quo(&a.v, &b.v))
}

func BigIntegerRemainder(a, b *BigInteger) *BigInteger {
	checkDivisor(b)
	return bigOf(new(big.Int).Rem(&a.v, &b.v))
}

func BigIntegerDivideAndRemainder(a, b *BigInteger) *Array[any] {
	checkDivisor(b)
	q, r := new(big.Int).QuoRem(&a.v, &b.v, new(big.Int))
	return &Array[any]{Data: []any{bigOf(q), bigOf(r)}}
}

func BigIntegerMod(a, m *BigInteger) *BigInteger {
	if m.v.Sign() <= 0 {
		ThrowKind(Arithmetic, "BigInteger: modulus not positive")
	}
	return bigOf(new(big.Int).Mod(&a.v, &m.v))
}

func BigIntegerModPow(a, e, m *BigInteger) *BigInteger {
	if m.v.Sign() <= 0 {
		ThrowKind(Arithmetic, "BigInteger: modulus not positive")
	}
	base, exp := &a.v, &e.v
	if exp.Sign() < 0 {
		base = new(big.Int).ModInverse(base, &m.v)
		if base == nil {
			ThrowKind(Arithmetic, "BigInteger not invertible.")
		}
		exp = new(big.Int).Neg(exp)
	}
	return bigOf(new(big.Int).Exp(base, exp, &m.v))
}

func BigIntegerModInverse(a, m *BigInteger) *BigInteger {
	if m.v.Sign() <= 0 {
		ThrowKind(Arithmetic, "BigInteger: modulus not positive")
	}
	inv := new(big.Int).ModInverse(new(big.Int).Mod(&a.v, &m.v), &m.v)
	if inv == nil {
		ThrowKind(Arithmetic, "BigInteger not invertible.")
	}
	return bigOf(inv)
}

func BigIntegerGcd(a, b *BigInteger) *BigInteger {
	x, y := new(big.Int).Abs(&a.v), new(big.Int).Abs(&b.v)
	return bigOf(new(big.Int).GCD(nil, nil, x, y))
}

func BigIntegerPow(a *BigInteger, e int32) *BigInteger {
	if e < 0 {
		ThrowKind(Arithmetic, "Negative exponent")
	}
	return bigOf(new(big.Int).Exp(&a.v, big.NewInt(int64(e)), nil))
}

func BigIntegerNegate(a *BigInteger) *BigInteger { return bigOf(new(big.Int).Neg(&a.v)) }
func BigIntegerAbs(a *BigInteger) *BigInteger    { return bigOf(new(big.Int).Abs(&a.v)) }
func BigIntegerNot(a *BigInteger) *BigInteger    { return bigOf(new(big.Int).Not(&a.v)) }
func BigIntegerSignum(a *BigInteger) int32       { return int32(a.v.Sign()) }

func BigIntegerShiftLeft(a *BigInteger, n int32) *BigInteger {
	if n < 0 {
		return BigIntegerShiftRight(a, -n)
	}
	return bigOf(new(big.Int).Lsh(&a.v, uint(n)))
}

// BigIntegerShiftRight is an arithmetic shift (rounds toward negative infinity), like Java.
func BigIntegerShiftRight(a *BigInteger, n int32) *BigInteger {
	if n < 0 {
		return BigIntegerShiftLeft(a, -n)
	}
	return bigOf(new(big.Int).Rsh(&a.v, uint(n)))
}

func BigIntegerTestBit(a *BigInteger, n int32) bool {
	if n < 0 {
		ThrowKind(Arithmetic, "Negative bit address")
	}
	// Bit of the two's-complement representation.
	return new(big.Int).Rsh(&a.v, uint(n)).Bit(0) == 1
}

func bitOp(a *BigInteger, n int32, op func(z, x, y *big.Int) *big.Int) *BigInteger {
	if n < 0 {
		ThrowKind(Arithmetic, "Negative bit address")
	}
	return bigOf(op(new(big.Int), &a.v, new(big.Int).Lsh(big.NewInt(1), uint(n))))
}

func BigIntegerSetBit(a *BigInteger, n int32) *BigInteger   { return bitOp(a, n, (*big.Int).Or) }
func BigIntegerClearBit(a *BigInteger, n int32) *BigInteger { return bitOp(a, n, (*big.Int).AndNot) }
func BigIntegerFlipBit(a *BigInteger, n int32) *BigInteger  { return bitOp(a, n, (*big.Int).Xor) }

// BigIntegerBitLength is the length of the minimal two's-complement representation, without sign bit.
func BigIntegerBitLength(a *BigInteger) int32 {
	if a.v.Sign() < 0 {
		return int32(new(big.Int).Not(&a.v).BitLen())
	}
	return int32(a.v.BitLen())
}

func BigIntegerBitCount(a *BigInteger) int32 {
	x := &a.v
	if x.Sign() < 0 {
		x = new(big.Int).Not(x)
	}
	n := 0
	for _, w := range x.Bits() {
		for ; w != 0; w &= w - 1 {
			n++
		}
	}
	return int32(n)
}

func BigIntegerLowestSetBit(a *BigInteger) int32 {
	if a.v.Sign() == 0 {
		return -1
	}
	return int32(new(big.Int).Abs(&a.v).TrailingZeroBits())
}

func BigIntegerIsProbablePrime(a *BigInteger, certainty int32) bool {
	if certainty <= 0 {
		return true
	}
	return new(big.Int).Abs(&a.v).ProbablyPrime(int(certainty+1) / 2)
}

func BigIntegerCompareTo(a, b *BigInteger) int32 { return int32(a.v.Cmp(&b.v)) }

func BigIntegerMin(a, b *BigInteger) *BigInteger {
	if a.v.Cmp(&b.v) <= 0 {
		return a
	}
	return b
}

func BigIntegerMax(a, b *BigInteger) *BigInteger {
	if a.v.Cmp(&b.v) >= 0 {
		return a
	}
	return b
}

func BigIntegerEquals(a *BigInteger, o any) bool {
	b, ok := o.(*BigInteger)
	return ok && a.v.Cmp(&b.v) == 0
}

// lowBits returns the value modulo 2^64 (two's complement truncation).
func (b *BigInteger) lowBits() int64 {
	m := new(big.Int).Mod(&b.v, new(big.Int).Lsh(big.NewInt(1), 64))
	return int64(m.Uint64())
}

func BigIntegerLongValue(a *BigInteger) int64 { return a.lowBits() }
func BigIntegerIntValue(a *BigInteger) int32  { return int32(a.lowBits()) }

func BigIntegerDoubleValue(a *BigInteger) float64 {
	f, _ := new(big.Float).SetInt(&a.v).Float64()
	return f
}

func BigIntegerFloatValue(a *BigInteger) float32 {
	f, _ := new(big.Float).SetInt(&a.v).Float32()
	return f
}

func BigIntegerToString(a *BigInteger, radix int32) *String {
	if radix < 2 || radix > 36 {
		radix = 10
	}
	return Str(a.v.Text(int(radix)))
}

// BigIntegerHashCode matches java.math.BigInteger.hashCode.
func BigIntegerHashCode(a *BigInteger) int32 { return bigIntHashCode(&a.v) }

func bigIntHashCode(x *big.Int) int32 {
	mag := new(big.Int).Abs(x).Bytes()
	for len(mag)%4 != 0 {
		mag = append([]byte{0}, mag...)
	}
	var h int32
	for i := 0; i < len(mag); i += 4 {
		word := uint32(mag[i])<<24 | uint32(mag[i+1])<<16 | uint32(mag[i+2])<<8 | uint32(mag[i+3])
		h = 31*h + int32(word)
	}
	return h * int32(x.Sign())
}
