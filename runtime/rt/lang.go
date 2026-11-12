package rt

import (
	"math"
	"math/bits"
	"math/rand/v2"
	"os"
	"reflect"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"
)

// Static methods of java.lang classes (Integer, Long, Double, Character, Math, System) that the
// linker maps to Go directly.

// numberFormat throws NumberFormatException.forInputString's exception.
func numberFormat(s string, radix int32) {
	msg := "For input string: \"" + s + "\""
	if radix != 10 {
		msg += " under radix " + itoa(radix)
	}
	ThrowKind(NumberFormat, msg)
}

func checkRadix(radix int32) {
	if radix < minRadix {
		ThrowKind(NumberFormat, "radix "+itoa(radix)+" less than Character.MIN_RADIX")
	}
	if radix > maxRadix {
		ThrowKind(NumberFormat, "radix "+itoa(radix)+" greater than Character.MAX_RADIX")
	}
}

const minRadix, maxRadix = 2, 36

func ParseInt(s *String, radix int32) int32  { return int32(parseInteger(s, radix, math.MinInt32)) }
func ParseLong(s *String, radix int32) int64 { return parseInteger(s, radix, math.MinInt64) }

// parseInteger is Integer.parseInt and Long.parseLong, for results in [min, -(min+1)]: the
// digits are Character.digit's (any Unicode decimal digit), accumulated negatively as in the JDK
// so that min itself does not overflow.
func parseInteger(s *String, radix int32, min int64) int64 {
	if s == nil {
		ThrowKind(NumberFormat, "Cannot parse null string")
	}
	checkRadix(radix)
	chars := s.chars
	limit := min + 1
	i, negative := 0, false
	if len(chars) > 0 && chars[0] < '0' {
		switch chars[0] {
		case '-':
			negative, limit = true, min
		case '+':
		default:
			numberFormat(s.GoString(), radix)
		}
		i = 1
	}
	if i == len(chars) {
		numberFormat(s.GoString(), radix)
	}
	r := int64(radix)
	multmin := limit / r
	var result int64
	for ; i < len(chars); i++ {
		d := int64(CharDigit(int32(chars[i]), radix))
		if d < 0 || result < multmin {
			numberFormat(s.GoString(), radix)
		}
		result *= r
		if result < limit+d {
			numberFormat(s.GoString(), radix)
		}
		result -= d
	}
	if negative {
		return result
	}
	return -result
}

func ParseDouble(s *String) float64 { return parseFloating(s, 64) }

// ParseFloat rounds once, from the decimal: rounding through a double would round twice.
func ParseFloat(s *String) float32 { return float32(parseFloating(s, 32)) }

// parseFloating checks the syntax of Double.parseDouble (the JDK's
// FloatingDecimal.readJavaFormatString), then leaves the rounding to strconv: characters up to
// ' ' around the number are ignored; an optional sign precedes NaN, Infinity, or a decimal or
// hexadecimal (0x…p…) number with an optional suffix f, F, d or D.
func parseFloating(s *String, bitSize int) float64 {
	t := StringTrim(s).chars
	if len(t) == 0 {
		ThrowKind(NumberFormat, "empty String")
	}
	body := t
	if t[0] == '-' || t[0] == '+' {
		body = t[1:]
	}
	switch {
	case charsEqual(body, "NaN"):
		return NaN()
	case charsEqual(body, "Infinity"):
		if t[0] == '-' {
			return math.Inf(-1)
		}
		return math.Inf(1)
	}
	var n int // length of the number in body, without the suffix
	if len(body) > 1 && body[0] == '0' && (body[1] == 'x' || body[1] == 'X') {
		n = hexFloatLength(body)
	} else {
		n = decimalFloatLength(body)
	}
	if n == 0 || !(n == len(body) || n == len(body)-1 && strings.IndexByte("fFdD", byte(body[n])) >= 0) {
		numberFormat(s.GoString(), 10)
	}
	// Out of range: ±Infinity or ±0.0, with an error to ignore.
	v, _ := strconv.ParseFloat(decodeUTF16(t[:len(t)-len(body)+n]), bitSize)
	return v
}

func charsEqual(chars []uint16, s string) bool {
	if len(chars) != len(s) {
		return false
	}
	for i, c := range chars {
		if c != uint16(s[i]) {
			return false
		}
	}
	return true
}

// decimalFloatLength is the length of the decimal number body starts with (digits with at most
// one point, then an optional exponent), or 0 if there is none.
func decimalFloatLength(body []uint16) int {
	i, digits, point := 0, false, false
	for ; i < len(body); i++ {
		if c := body[i]; c >= '0' && c <= '9' {
			digits = true
		} else if c != '.' {
			break
		} else if point {
			ThrowKind(NumberFormat, "multiple points")
		} else {
			point = true
		}
	}
	if !digits {
		return 0
	}
	if i < len(body) && (body[i] == 'e' || body[i] == 'E') {
		return exponentLength(body, i)
	}
	return i
}

// hexFloatLength is the length of the hexadecimal number body starts with (0x, hexadecimal
// digits with at most one point, then a binary exponent), or 0 if there is none.
func hexFloatLength(body []uint16) int {
	i, digits, point := 2, false, false
	for ; i < len(body); i++ {
		if c := body[i]; c >= '0' && c <= '9' || c|0x20 >= 'a' && c|0x20 <= 'f' {
			digits = true
		} else if c != '.' || point {
			break
		} else {
			point = true
		}
	}
	if !digits || i == len(body) || body[i] != 'p' && body[i] != 'P' {
		return 0
	}
	return exponentLength(body, i)
}

// exponentLength is the length up to the end of the exponent at body[at] (e or p, an optional
// sign and digits), or 0 if it has no digits.
func exponentLength(body []uint16, at int) int {
	i := at + 1
	if i < len(body) && (body[i] == '-' || body[i] == '+') {
		i++
	}
	start := i
	for i < len(body) && body[i] >= '0' && body[i] <= '9' {
		i++
	}
	if i == start {
		return 0
	}
	return i
}

func ParseBoolean(s *String) bool { return s != nil && strings.EqualFold(s.GoString(), "true") }

// IntToString and LongToString are toString(i, radix): an out-of-range radix means 10.
func IntToString(i int32, radix int32) *String { return LongToString(int64(i), radix) }

func LongToString(i int64, radix int32) *String {
	if radix < minRadix || radix > maxRadix {
		radix = 10
	}
	return Str(strconv.FormatInt(i, int(radix)))
}

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

// SmallIntCompare is Byte.compare and Short.compare: the difference, as on the JDK.
func SmallIntCompare(a, b int32) int32 { return a - b }

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
func CharIsSpaceChar(c int32) bool     { return unicode.In(rune(c), unicode.Zs, unicode.Zl, unicode.Zp) }
func CharToUpperCase(c int32) int32    { return int32(unicode.ToUpper(rune(c))) }
func CharToLowerCase(c int32) int32    { return int32(unicode.ToLower(rune(c))) }

// CharIsUpperCase and CharIsLowerCase include Other_Uppercase and Other_Lowercase (Roman
// numerals, ª), as Character's do.
func CharIsUpperCase(c int32) bool {
	return unicode.IsUpper(rune(c)) || unicode.Is(unicode.Other_Uppercase, rune(c))
}

func CharIsLowerCase(c int32) bool {
	return unicode.IsLower(rune(c)) || unicode.Is(unicode.Other_Lowercase, rune(c))
}

// CharIsAlphabetic is Character.isAlphabetic: letters, letter numbers (Nl) and Other_Alphabetic.
func CharIsAlphabetic(c int32) bool {
	return unicode.IsLetter(rune(c)) || unicode.In(rune(c), unicode.Nl, unicode.Other_Alphabetic)
}

// CharDigit is Character.digit: the value of a Unicode decimal digit or of a Latin letter (ASCII
// or fullwidth), if less than radix; otherwise, or if radix is out of range, -1.
func CharDigit(c int32, radix int32) int32 {
	if radix < minRadix || radix > maxRadix {
		return -1
	}
	var d int32 = -1
	switch {
	case c >= '0' && c <= '9':
		d = c - '0'
	case c >= 'a' && c <= 'z':
		d = c - 'a' + 10
	case c >= 'A' && c <= 'Z':
		d = c - 'A' + 10
	case c < 0x80:
	case c >= 0xff21 && c <= 0xff3a:
		d = c - 0xff21 + 10
	case c >= 0xff41 && c <= 0xff5a:
		d = c - 0xff41 + 10
	default:
		d = decimalDigitValue(c)
	}
	if d >= radix {
		return -1
	}
	return d
}

// decimalDigitValue is the value of a decimal digit (category Nd), or -1. Unicode encodes these
// in runs of ten, 0 to 9, so each of unicode.Nd's ranges starts with a 0.
func decimalDigitValue(c int32) int32 {
	if c <= math.MaxUint16 {
		r := unicode.Nd.R16
		i := sort.Search(len(r), func(i int) bool { return int32(r[i].Hi) >= c })
		if i < len(r) && int32(r[i].Lo) <= c {
			return (c - int32(r[i].Lo)) % 10
		}
		return -1
	}
	r := unicode.Nd.R32
	i := sort.Search(len(r), func(i int) bool { return int32(r[i].Hi) >= c })
	if i < len(r) && int32(r[i].Lo) <= c {
		return (c - int32(r[i].Lo)) % 10
	}
	return -1
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

// MathRound and MathRoundFloat are Math.round: the closest integer, ties toward positive
// infinity. Not floor(d + 0.5): the sum rounds (0.49999999999999994 + 0.5 is 1).
func MathRound(d float64) int64 { return D2L(roundTiesUp(d)) }

func MathRoundFloat(f float32) int32 { return D2I(roundTiesUp(float64(f))) }

// roundTiesUp keeps NaN, which D2L and D2I map to 0. d - floor(d) is exact.
func roundTiesUp(d float64) float64 {
	r := math.Floor(d)
	if d-r >= 0.5 {
		r++
	}
	return r
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

func longOverflow() { ThrowKind(Arithmetic, "long overflow") }

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

func IntSubtractExact(a, b int32) int32 {
	r := a - b
	if (a^b)&(a^r) < 0 {
		overflow()
	}
	return r
}

func IntNegateExact(a int32) int32 {
	if a == math.MinInt32 {
		overflow()
	}
	return -a
}

func LongAddExact(a, b int64) int64 {
	r := a + b
	if (a^r)&(b^r) < 0 {
		longOverflow()
	}
	return r
}

func LongSubtractExact(a, b int64) int64 {
	r := a - b
	if (a^b)&(a^r) < 0 {
		longOverflow()
	}
	return r
}

func LongNegateExact(a int64) int64 {
	if a == math.MinInt64 {
		longOverflow()
	}
	return -a
}

// ToIntExact implements Math.toIntExact.
func ToIntExact(a int64) int32 {
	if int64(int32(a)) != a {
		overflow()
	}
	return int32(a)
}

func LongMultiplyExact(a, b int64) int64 {
	hi, lo := bits.Mul64(uint64(absInt64(a)), uint64(absInt64(b)))
	r := a * b
	if hi != 0 || lo > math.MaxInt64+1 || (a != 0 && r/a != b) {
		longOverflow()
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

// MathClamp implements Math.clamp(long, int, int) and Math.clamp(long, long, long).
func MathClamp[T int32 | int64](value int64, lo, hi T) T {
	if lo > hi {
		ThrowKind(IllegalArgument, strconv.FormatInt(int64(lo), 10)+" > "+strconv.FormatInt(int64(hi), 10))
	}
	return T(min(max(value, int64(lo)), int64(hi)))
}

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
	clone := copied.Interface()
	if h, ok := clone.(hasObjectHeader); ok {
		h.objectHeader().monitor.Store(nil) // the clone is a new object, with its own monitor
	}
	return clone
}

// ---- Identifier characters (Character.isJavaIdentifierStart, ...) --------------------------

func CharIsIdentifierIgnorable(c int32) bool {
	return (c >= 0 && c <= 8) || (c >= 0x0e && c <= 0x1b) || (c >= 0x7f && c <= 0x9f) || unicode.Is(unicode.Cf, rune(c))
}

func CharIsUnicodeIdentifierStart(c int32) bool {
	r := rune(c)
	return unicode.IsLetter(r) || unicode.In(r, unicode.Nl, unicode.Other_ID_Start)
}

func CharIsUnicodeIdentifierPart(c int32) bool {
	r := rune(c)
	return CharIsUnicodeIdentifierStart(c) || unicode.In(r, unicode.Pc, unicode.Nd, unicode.Mc, unicode.Mn, unicode.Other_ID_Continue) ||
		CharIsIdentifierIgnorable(c)
}

func CharIsJavaIdentifierStart(c int32) bool {
	r := rune(c)
	return unicode.IsLetter(r) || unicode.In(r, unicode.Nl, unicode.Sc, unicode.Pc)
}

func CharIsJavaIdentifierPart(c int32) bool {
	r := rune(c)
	return CharIsJavaIdentifierStart(c) || unicode.In(r, unicode.Nd, unicode.Mc, unicode.Mn) || CharIsIdentifierIgnorable(c)
}

// ---- Unsigned decimal strings ----------------------------------------------------------------

func IntToUnsignedDecimal(x int32) *String  { return Str(strconv.FormatUint(uint64(uint32(x)), 10)) }
func LongToUnsignedDecimal(x int64) *String { return Str(strconv.FormatUint(uint64(x), 10)) }

// WriteFloatingChars writes Java's Double.toString (bits 64) or Float.toString (bits 32) of d into
// buf at off, and returns the offset after it. It implements ujson's FloatToDecimalChar and
// DoubleToDecimalChar, Java classes (so without TASTy) that port the JDK's algorithm.
func WriteFloatingChars(buf *Array[uint16], off int32, d float64, bits int) int32 {
	s := formatFloating(d, bits)
	if int(off)+len(s) > len(buf.Data) {
		ThrowKind(ArrayIndexOutOfBounds, "Index "+itoa(off+int32(len(s))-1)+" out of bounds for length "+itoa(int32(len(buf.Data))))
	}
	for i := 0; i < len(s); i++ {
		buf.Data[int(off)+i] = uint16(s[i])
	}
	return off + int32(len(s))
}
