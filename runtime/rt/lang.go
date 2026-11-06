package rt

import (
	"math"
	"math/bits"
	"math/rand/v2"
	"os"
	"reflect"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// Static methods of java.lang classes (Integer, Long, Double, Character, Math, System) that the
// linker maps to Go directly.

func numberFormat(s *String) {
	ThrowKind(NumberFormat, "For input string: \""+ToGoString(s)+"\"")
}

func ParseInt(s *String, radix int32) int32 {
	if s == nil {
		ThrowKind(NumberFormat, "Cannot parse null string: null")
	}
	v, err := strconv.ParseInt(s.GoString(), int(radix), 32)
	if err != nil {
		numberFormat(s)
	}
	return int32(v)
}

func ParseLong(s *String, radix int32) int64 {
	if s == nil {
		ThrowKind(NumberFormat, "Cannot parse null string: null")
	}
	v, err := strconv.ParseInt(s.GoString(), int(radix), 64)
	if err != nil {
		numberFormat(s)
	}
	return v
}

func ParseDouble(s *String) float64 {
	str := strings.TrimSpace(s.GoString())
	str = strings.TrimSuffix(strings.TrimSuffix(strings.TrimSuffix(str, "d"), "D"), "f")
	switch str {
	case "NaN":
		return math.NaN()
	case "Infinity", "+Infinity":
		return math.Inf(1)
	case "-Infinity":
		return math.Inf(-1)
	}
	v, err := strconv.ParseFloat(str, 64)
	if err != nil && !strings.Contains(err.Error(), "out of range") {
		numberFormat(s)
	}
	return v
}

func ParseBoolean(s *String) bool { return s != nil && strings.EqualFold(s.GoString(), "true") }

func IntToString(i int32, radix int32) *String  { return Str(strconv.FormatInt(int64(i), int(radix))) }
func LongToString(i int64, radix int32) *String { return Str(strconv.FormatInt(i, int(radix))) }

func IntToUnsignedString(i int32, shift uint) *String {
	return Str(strconv.FormatUint(uint64(uint32(i)), 1<<shift))
}

func LongToUnsignedString(i int64, shift uint) *String {
	return Str(strconv.FormatUint(uint64(i), 1<<shift))
}

func IntBitCount(i int32) int32            { return int32(bits.OnesCount32(uint32(i))) }
func IntLeadingZeros(i int32) int32        { return int32(bits.LeadingZeros32(uint32(i))) }
func IntTrailingZeros(i int32) int32       { return int32(bits.TrailingZeros32(uint32(i))) }
func IntReverse(i int32) int32             { return int32(bits.Reverse32(uint32(i))) }
func IntReverseBytes(i int32) int32        { return int32(bits.ReverseBytes32(uint32(i))) }
func IntRotateLeft(i int32, d int32) int32 { return int32(bits.RotateLeft32(uint32(i), int(d&31))) }
func IntHighestOneBit(i int32) int32 {
	if i == 0 {
		return 0
	}
	return int32(uint32(1) << (31 - bits.LeadingZeros32(uint32(i))))
}

func LongBitCount(i int64) int32            { return int32(bits.OnesCount64(uint64(i))) }
func LongLeadingZeros(i int64) int32        { return int32(bits.LeadingZeros64(uint64(i))) }
func LongTrailingZeros(i int64) int32       { return int32(bits.TrailingZeros64(uint64(i))) }
func LongReverse(i int64) int64             { return int64(bits.Reverse64(uint64(i))) }
func LongRotateLeft(i int64, d int32) int64 { return int64(bits.RotateLeft64(uint64(i), int(d&63))) }
func LongHighestOneBit(i int64) int64 {
	if i == 0 {
		return 0
	}
	return int64(uint64(1) << (63 - bits.LeadingZeros64(uint64(i))))
}

func compareInts[T int32 | int64 | uint16 | int8 | int16](a, b T) int32 {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

func IntCompare(a, b int32) int32   { return compareInts(a, b) }
func LongCompare(a, b int64) int32  { return compareInts(a, b) }
func CharCompare(a, b uint16) int32 { return int32(a) - int32(b) }

// DoubleCompare implements Double.compare: -0.0 < 0.0, and NaN is greater than everything.
func DoubleCompare(a, b float64) int32 {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	ab, bb := DoubleToLongBits(a), DoubleToLongBits(b)
	return compareInts(ab, bb)
}

func BoolCompare(a, b bool) int32 {
	switch {
	case a == b:
		return 0
	case a:
		return 1
	}
	return -1
}

func DoubleToLongBits(d float64) int64 {
	if math.IsNaN(d) {
		return 0x7ff8000000000000
	}
	return int64(math.Float64bits(d))
}

func FloatToIntBits(f float32) int32 {
	if f != f {
		return 0x7fc00000
	}
	return int32(math.Float32bits(f))
}

// ---- Character ----------------------------------------------------------------------------

func CharIsWhitespace(c int32) bool {
	switch {
	case c == '\t' || c == '\n' || c == 0x0b || c == '\f' || c == '\r' || (c >= 0x1c && c <= 0x1f):
		return true
	case c == 0xa0 || c == 0x2007 || c == 0x202f:
		return false
	}
	return unicode.Is(unicode.Zs, rune(c)) || unicode.Is(unicode.Zl, rune(c)) || unicode.Is(unicode.Zp, rune(c))
}

func CharIsDigit(c int32) bool         { return unicode.IsDigit(rune(c)) }
func CharIsLetter(c int32) bool        { return unicode.IsLetter(rune(c)) }
func CharIsLetterOrDigit(c int32) bool { return unicode.IsLetter(rune(c)) || unicode.IsDigit(rune(c)) }
func CharIsUpperCase(c int32) bool     { return unicode.IsUpper(rune(c)) }
func CharIsLowerCase(c int32) bool     { return unicode.IsLower(rune(c)) }
func CharIsSpaceChar(c int32) bool     { return unicode.In(rune(c), unicode.Zs, unicode.Zl, unicode.Zp) }
func CharToUpperCase(c int32) int32    { return int32(unicode.ToUpper(rune(c))) }
func CharToLowerCase(c int32) int32    { return int32(unicode.ToLower(rune(c))) }

func CharDigit(c int32, radix int32) int32 {
	var d int32 = -1
	switch {
	case c >= '0' && c <= '9':
		d = c - '0'
	case c >= 'a' && c <= 'z':
		d = c - 'a' + 10
	case c >= 'A' && c <= 'Z':
		d = c - 'A' + 10
	case unicode.IsDigit(rune(c)):
		d = int32(rune(c) - '0') // approximation for non-ASCII digits
	}
	if d >= radix {
		return -1
	}
	return d
}

func CharForDigit(d int32, radix int32) uint16 {
	if d < 0 || d >= radix || radix < 2 || radix > 36 {
		return 0
	}
	if d < 10 {
		return uint16('0' + d)
	}
	return uint16('a' + d - 10)
}

// ---- Math ---------------------------------------------------------------------------------

func MathRound(d float64) int64 {
	if math.IsNaN(d) {
		return 0
	}
	return D2L(math.Floor(d + 0.5))
}

func MathRoundFloat(f float32) int32 {
	if f != f {
		return 0
	}
	return D2I(math.Floor(float64(f) + 0.5))
}

func MathSignum(d float64) float64 {
	switch {
	case d > 0:
		return 1
	case d < 0:
		return -1
	}
	return d
}

func MathRint(d float64) float64 { return math.RoundToEven(d) }

func IntFloorDiv(a, b int32) int32 {
	q := a / b
	if (a%b != 0) && ((a < 0) != (b < 0)) {
		q--
	}
	return q
}

func IntFloorMod(a, b int32) int32 { return a - IntFloorDiv(a, b)*b }

func LongFloorDiv(a, b int64) int64 {
	q := a / b
	if (a%b != 0) && ((a < 0) != (b < 0)) {
		q--
	}
	return q
}

func LongFloorMod(a, b int64) int64 { return a - LongFloorDiv(a, b)*b }

func overflow() { ThrowKind(Arithmetic, "integer overflow") }

func IntAddExact(a, b int32) int32 {
	r := a + b
	if (a^r)&(b^r) < 0 {
		overflow()
	}
	return r
}

func IntMultiplyExact(a, b int32) int32 {
	r := int64(a) * int64(b)
	if int64(int32(r)) != r {
		overflow()
	}
	return int32(r)
}

func LongAddExact(a, b int64) int64 {
	r := a + b
	if (a^r)&(b^r) < 0 {
		overflow()
	}
	return r
}

func LongMultiplyExact(a, b int64) int64 {
	hi, lo := bits.Mul64(uint64(absInt64(a)), uint64(absInt64(b)))
	r := a * b
	if hi != 0 || lo > math.MaxInt64+1 || (a != 0 && r/a != b) {
		overflow()
	}
	return r
}

func absInt64(a int64) int64 {
	if a < 0 {
		return -a
	}
	return a
}

func MathRandom() float64 { return rand.Float64() }

// ---- System -------------------------------------------------------------------------------

var startTime = time.Now()

func CurrentTimeMillis() int64 { return time.Now().UnixMilli() }
func NanoTime() int64          { return int64(time.Since(startTime)) }

func Getenv(name *String) *String {
	v, ok := os.LookupEnv(name.GoString())
	if !ok {
		return nil
	}
	return Str(v)
}

// ---- Misc ---------------------------------------------------------------------------------

// Nop implements methods with no effect in a single-threaded program (notify, notifyAll).
func Nop(any) {}

// Abs implements Math.abs (abs(-0.0) is 0.0, abs(MinValue) is MinValue as in Java).
func Abs[T int32 | int64 | float32 | float64](x T) T {
	if x <= 0 {
		return 0 - x
	}
	return x
}

func CharCount(codePoint int32) int32 {
	if codePoint >= 0x10000 {
		return 2
	}
	return 1
}

// Clone implements Object.clone(): arrays are copied; other objects are shallow-copied.
func Clone(x any) any {
	if _, ok := x.(arrayOps); ok {
		return ArrayClone(x)
	}
	v := reflect.ValueOf(x)
	if v.Kind() != reflect.Pointer || v.IsNil() {
		ThrowKind(RuntimeError, "clone not supported")
	}
	copied := reflect.New(v.Elem().Type())
	copied.Elem().Set(v.Elem())
	return copied.Interface()
}
