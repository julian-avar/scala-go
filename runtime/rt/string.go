package rt

import (
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"
	"unsafe"
)

// String is java.lang.String: an immutable sequence of UTF-16 code units. A nil *String is null.
//
// Go strings (UTF-8) are only used at the edges: literals in generated code, I/O and interop.
type String struct {
	chars []uint16
	hash  atomic.Int32 // cached hashCode; 0 means not computed yet (as in the JDK)
}

// Str converts a Go (UTF-8) string, such as a literal in generated code. Invalid UTF-8 bytes
// become U+FFFD.
func Str(s string) *String { return &String{chars: encodeUTF16(s)} }

// encodeUTF16 transcodes UTF-8 to UTF-16, with a fast path for the ASCII prefix. len(s) bounds
// the result's length: each UTF-8 sequence is at least as long as its UTF-16 encoding.
func encodeUTF16(s string) []uint16 {
	chars := make([]uint16, 0, len(s))
	for i := 0; i < len(s); i++ {
		if s[i] >= utf8.RuneSelf {
			for _, r := range s[i:] {
				chars = utf16.AppendRune(chars, r)
			}
			return chars
		}
		chars = append(chars, uint16(s[i]))
	}
	return chars
}

// StrUTF16 builds a string from UTF-16 code units (used for literals Go cannot express, such as
// lone surrogates).
func StrUTF16(chars ...uint16) *String {
	return &String{chars: chars}
}

func newString(chars []uint16) *String { return &String{chars: chars} }

// GoString converts to a Go string. Unpaired surrogates become U+FFFD.
func (s *String) GoString() string { return decodeUTF16(s.chars) }

// decodeUTF16 transcodes UTF-16 to UTF-8, with a fast path for the ASCII prefix.
func decodeUTF16(chars []uint16) string {
	buf := make([]byte, 0, len(chars))
	for i := 0; i < len(chars); i++ {
		c := chars[i]
		if c >= utf8.RuneSelf {
			for _, r := range utf16.Decode(chars[i:]) {
				buf = utf8.AppendRune(buf, r)
			}
			break
		}
		buf = append(buf, byte(c))
	}
	// buf is not used after this, so the string can share its memory.
	return unsafe.String(unsafe.SliceData(buf), len(buf))
}

// GoStringCompare implements scalago.go.GoString's Ordering: -1, 0 or 1, by bytes.
func GoStringCompare(a, b string) int32 { return int32(strings.Compare(a, b)) }

// String implements fmt.Stringer.
func (s *String) String() string { return s.GoString() }

// Chars exposes the UTF-16 code units (read-only).
func (s *String) Chars() []uint16 { return s.chars }

// The bounds checks of String's methods throw StringIndexOutOfBoundsException with the messages
// of the JDK's Preconditions (an index, a range from begin to end, or one from offset and size).

func (s *String) checkIndex(i int32) {
	if i < 0 || int(i) >= len(s.chars) {
		ThrowKind(StringIndexOutOfBounds, "Index "+itoa(i)+" out of bounds for length "+itoa(int32(len(s.chars))))
	}
}

func (s *String) checkRange(begin, end int32) { checkStringRange(begin, end, len(s.chars)) }

func checkStringRange(begin, end int32, length int) {
	if begin < 0 || begin > end || int(end) > length {
		ThrowKind(StringIndexOutOfBounds, "Range ["+itoa(begin)+", "+itoa(end)+") out of bounds for length "+itoa(int32(length)))
	}
}

func checkStringRangeSize(offset, size int32, length int) {
	if offset < 0 || size < 0 || int(offset)+int(size) > length {
		ThrowKind(StringIndexOutOfBounds, "Range ["+itoa(offset)+", "+itoa(offset)+" + "+itoa(size)+") out of bounds for length "+itoa(int32(length)))
	}
}

// ---- Constructors -------------------------------------------------------------------------

func NewStringEmpty() *String { return &String{} }

func NewStringFromChars(a *Array[uint16]) *String {
	return newString(append([]uint16(nil), a.Data...))
}

func NewStringFromCharRange(a *Array[uint16], offset, count int32) *String {
	checkStringRangeSize(offset, count, len(a.Data))
	return newString(append([]uint16(nil), a.Data[offset:offset+count]...))
}

func NewStringCopy(s *String) *String { return newString(s.chars) }

// ---- Instance methods (receiver first; a nil receiver panics, which becomes an NPE) ---------

func StringLength(s *String) int32 { return int32(len(s.chars)) }

func StringIsEmpty(s *String) bool { return len(s.chars) == 0 }

func StringCharAt(s *String, i int32) uint16 {
	s.checkIndex(i)
	return s.chars[i]
}

func StringCodePointAt(s *String, i int32) int32 {
	s.checkIndex(i)
	c := s.chars[i]
	if utf16.IsSurrogate(rune(c)) && c < 0xdc00 && int(i+1) < len(s.chars) {
		if r := utf16.DecodeRune(rune(c), rune(s.chars[i+1])); r != unicode.ReplacementChar {
			return int32(r)
		}
	}
	return int32(c)
}

func StringSubstring(s *String, begin int32) *String {
	return StringSubstringRange(s, begin, int32(len(s.chars)))
}

func StringSubstringRange(s *String, begin, end int32) *String {
	s.checkRange(begin, end)
	if begin == 0 && int(end) == len(s.chars) {
		return s
	}
	return newString(s.chars[begin:end])
}

func StringEquals(s *String, other any) bool {
	o, ok := other.(*String)
	return ok && stringEq(s, o)
}

// EqualsString is Equals on two Strings.
func EqualsString(a, b *String) bool {
	if a == nil || b == nil {
		return a == b
	}
	return stringEq(a, b)
}

// MatchString is what a `match` on string literals switches on: s as a Go string; for null, a
// string no literal is (invalid UTF-8), so that null takes the default case, as on the JVM.
func MatchString(s *String) string {
	if s == nil {
		return "\xff"
	}
	return s.GoString()
}

func stringEq(a, b *String) bool {
	if a == b {
		return true
	}
	if len(a.chars) != len(b.chars) {
		return false
	}
	for i, c := range a.chars {
		if b.chars[i] != c {
			return false
		}
	}
	return true
}

func StringEqualsIgnoreCase(s *String, o *String) bool {
	if o == nil || len(s.chars) != len(o.chars) {
		return false
	}
	for i, c := range s.chars {
		d := o.chars[i]
		if c != d && unicode.ToUpper(rune(c)) != unicode.ToUpper(rune(d)) && unicode.ToLower(rune(c)) != unicode.ToLower(rune(d)) {
			return false
		}
	}
	return true
}

func StringHashCode(s *String) int32 {
	h := s.hash.Load()
	if h == 0 {
		for _, c := range s.chars {
			h = 31*h + int32(c)
		}
		s.hash.Store(h)
	}
	return h
}

func StringCompareTo(s *String, o *String) int32 {
	n := min(len(s.chars), len(o.chars))
	for i := 0; i < n; i++ {
		if s.chars[i] != o.chars[i] {
			return int32(s.chars[i]) - int32(o.chars[i])
		}
	}
	return int32(len(s.chars) - len(o.chars))
}

func StringCompareToIgnoreCase(s *String, o *String) int32 {
	return StringCompareTo(StringToLowerCase(StringToUpperCase(s)), StringToLowerCase(StringToUpperCase(o)))
}

// StringIndexOfChar and StringLastIndexOfChar search for a code point: one char, or the surrogate
// pair of a supplementary code point. Other values (negative, beyond U+10FFFF) are never found.
func StringIndexOfChar(s *String, c int32, from int32) int32 {
	from = max(from, 0)
	if c >= 0x10000 {
		if c > unicode.MaxRune {
			return -1
		}
		hi, lo := utf16.EncodeRune(rune(c))
		for i := int(from); i+1 < len(s.chars); i++ {
			if rune(s.chars[i]) == hi && rune(s.chars[i+1]) == lo {
				return int32(i)
			}
		}
		return -1
	}
	for i := int(from); i < len(s.chars); i++ {
		if int32(s.chars[i]) == c {
			return int32(i)
		}
	}
	return -1
}

func StringLastIndexOfChar(s *String, c int32, from int32) int32 {
	if c >= 0x10000 {
		if c > unicode.MaxRune {
			return -1
		}
		hi, lo := utf16.EncodeRune(rune(c))
		for i := min(int(from), len(s.chars)-2); i >= 0; i-- {
			if rune(s.chars[i]) == hi && rune(s.chars[i+1]) == lo {
				return int32(i)
			}
		}
		return -1
	}
	for i := min(int(from), len(s.chars)-1); i >= 0; i-- {
		if int32(s.chars[i]) == c {
			return int32(i)
		}
	}
	return -1
}

func StringIndexOf(s *String, sub *String, from int32) int32 {
	n, m := len(s.chars), len(sub.chars)
	from = min(max(from, 0), int32(n)) // past the end, only the empty string is found (at the end)
outer:
	for i := int(from); i+m <= n; i++ {
		for j := 0; j < m; j++ {
			if s.chars[i+j] != sub.chars[j] {
				continue outer
			}
		}
		return int32(i)
	}
	return -1
}

func StringLastIndexOf(s *String, sub *String, from int32) int32 {
	n, m := len(s.chars), len(sub.chars)
outer:
	for i := min(int(from), n-m); i >= 0; i-- {
		for j := 0; j < m; j++ {
			if s.chars[i+j] != sub.chars[j] {
				continue outer
			}
		}
		return int32(i)
	}
	return -1
}

func StringStartsWith(s *String, prefix *String, offset int32) bool {
	if offset < 0 || int(offset)+len(prefix.chars) > len(s.chars) {
		return false
	}
	for i, c := range prefix.chars {
		if s.chars[int(offset)+i] != c {
			return false
		}
	}
	return true
}

// StringRegionMatches compares s[toffset:toffset+n] with other[ooffset:ooffset+n], ignoring
// case if asked (chars equal after toUpperCase or toLowerCase, as Java's).
func StringRegionMatches(s *String, ignoreCase bool, toffset int32, other *String, ooffset int32, n int32) bool {
	if ooffset < 0 || toffset < 0 || int64(toffset) > int64(len(s.chars))-int64(n) ||
		int64(ooffset) > int64(len(other.chars))-int64(n) {
		return false
	}
	for i := int32(0); i < n; i++ {
		c, d := s.chars[toffset+i], other.chars[ooffset+i]
		if c == d {
			continue
		}
		if !ignoreCase || (unicode.ToUpper(rune(c)) != unicode.ToUpper(rune(d)) &&
			unicode.ToLower(rune(c)) != unicode.ToLower(rune(d))) {
			return false
		}
	}
	return true
}

func StringEndsWith(s *String, suffix *String) bool {
	return StringStartsWith(s, suffix, int32(len(s.chars)-len(suffix.chars)))
}

func StringContains(s *String, sub any) bool {
	return StringIndexOf(s, ToString(sub), 0) >= 0
}

func StringConcat(s *String, o *String) *String {
	if len(o.chars) == 0 {
		return s
	}
	chars := make([]uint16, 0, len(s.chars)+len(o.chars))
	return newString(append(append(chars, s.chars...), o.chars...))
}

// StringToUpperCase and StringToLowerCase map case as the JDK does for Locale.ROOT: per code
// point, with Unicode's special casing (ß is SS, final sigma is ς). They return s itself if
// nothing changes.
func StringToUpperCase(s *String) *String {
	return mapCase(s, func(chars []uint16, i int, r rune) ([]uint16, bool) {
		if special, ok := specialUpperCase[r]; ok {
			return special, true
		}
		return nil, false
	}, unicode.ToUpper)
}

func StringToLowerCase(s *String) *String {
	return mapCase(s, func(chars []uint16, i int, r rune) ([]uint16, bool) {
		if r == 'Σ' {
			if isFinalSigma(chars, i) {
				return []uint16{'ς'}, true
			}
			return []uint16{'σ'}, true
		}
		if special, ok := specialLowerCase[r]; ok {
			return special, true
		}
		return nil, false
	}, unicode.ToLower)
}

// mapCase maps each code point of s (at char index i) with special, or else with simple.
func mapCase(s *String, special func(chars []uint16, i int, r rune) ([]uint16, bool), simple func(rune) rune) *String {
	chars := s.chars
	var out []uint16 // nil while nothing has changed
	for i := 0; i < len(chars); {
		r, n := rune(chars[i]), 1
		if utf16.IsSurrogate(r) && i+1 < len(chars) {
			if pair := utf16.DecodeRune(r, rune(chars[i+1])); pair != unicode.ReplacementChar {
				r, n = pair, 2
			}
		}
		mapped, ok := special(chars, i, r)
		if !ok {
			if m := simple(r); m != r {
				mapped = utf16.AppendRune(nil, m)
				ok = true
			}
		}
		if ok && out == nil {
			out = append(make([]uint16, 0, len(chars)+4), chars[:i]...)
		}
		if out != nil {
			if ok {
				out = append(out, mapped...)
			} else {
				out = append(out, chars[i:i+n]...)
			}
		}
		i += n
	}
	if out == nil {
		return s
	}
	return newString(out)
}

// isFinalSigma implements Unicode's Final_Sigma condition for the Σ at index i: a cased letter
// before it and none after it, skipping case-ignorable characters.
func isFinalSigma(chars []uint16, i int) bool {
	cased := func(r rune) bool { return unicode.IsUpper(r) || unicode.IsLower(r) || unicode.IsTitle(r) }
	ignorable := func(r rune) bool {
		return unicode.In(r, unicode.Mn, unicode.Me, unicode.Cf, unicode.Lm, unicode.Sk) ||
			r == '\'' || r == '.' || r == ':' || r == '\u00b7' || r == '\u2019'
	}
	before := false
	for j := i - 1; j >= 0; j-- {
		r := rune(chars[j])
		if ignorable(r) {
			continue
		}
		before = cased(r)
		break
	}
	if !before {
		return false
	}
	for j := i + 1; j < len(chars); j++ {
		r := rune(chars[j])
		if ignorable(r) {
			continue
		}
		return !cased(r)
	}
	return true
}

func isJavaWhitespaceForTrim(c uint16) bool { return c <= ' ' }

func StringTrim(s *String) *String {
	begin, end := 0, len(s.chars)
	for begin < end && isJavaWhitespaceForTrim(s.chars[begin]) {
		begin++
	}
	for end > begin && isJavaWhitespaceForTrim(s.chars[end-1]) {
		end--
	}
	return StringSubstringRange(s, int32(begin), int32(end))
}

// StringStrip and StringIsBlank use Character.isWhitespace, as the JDK does: unlike Go's
// unicode.IsSpace, it excludes the no-break spaces (U+00A0, U+2007, U+202F) and U+0085. No
// supplementary code point is white space, so they test chars.
func StringStrip(s *String) *String {
	begin, end := 0, len(s.chars)
	for begin < end && CharIsWhitespace(int32(s.chars[begin])) {
		begin++
	}
	for end > begin && CharIsWhitespace(int32(s.chars[end-1])) {
		end--
	}
	return StringSubstringRange(s, int32(begin), int32(end))
}

func StringIsBlank(s *String) bool {
	for _, c := range s.chars {
		if !CharIsWhitespace(int32(c)) {
			return false
		}
	}
	return true
}

func StringReplaceChar(s *String, old, new uint16) *String {
	chars := append([]uint16(nil), s.chars...)
	for i, c := range chars {
		if c == old {
			chars[i] = new
		}
	}
	return newString(chars)
}

func StringReplace(s *String, target any, replacement any) *String {
	return Str(strings.ReplaceAll(s.GoString(), ToGoString(target), ToGoString(replacement)))
}

func StringToCharArray(s *String) *Array[uint16] {
	return &Array[uint16]{Data: append([]uint16(nil), s.chars...)}
}

func StringRepeat(s *String, n int32) *String {
	if n < 0 {
		ThrowKind(IllegalArgument, "count is negative: "+itoa(n))
	}
	chars := make([]uint16, 0, len(s.chars)*int(n))
	for i := int32(0); i < n; i++ {
		chars = append(chars, s.chars...)
	}
	return newString(chars)
}

var (
	internMu       sync.Mutex
	internTable    map[string]*String // keyed by the chars' bytes, built on first use
	internLiterals []*String
)

// RegisterStringLiterals is called by a program that uses intern(), with its string literals:
// intern returns them for equal strings, as the JVM interns literals.
func RegisterStringLiterals(literals ...*String) { internLiterals = literals }

func StringIntern(s *String) *String {
	internMu.Lock()
	defer internMu.Unlock()
	if internTable == nil {
		internTable = make(map[string]*String, len(internLiterals))
		for _, l := range internLiterals {
			internTable[charsKey(l.chars)] = l
		}
	}
	key := charsKey(s.chars)
	if t, ok := internTable[key]; ok {
		return t
	}
	internTable[key] = s
	return s
}

// charsKey views UTF-16 code units as a string of their bytes, without copying: the chars of a
// String never change.
func charsKey(chars []uint16) string {
	return unsafe.String((*byte)(unsafe.Pointer(unsafe.SliceData(chars))), 2*len(chars))
}

// StringSplit implements split(regex): patterns are Java regular expressions, run by Go's
// regexp package (see compileRegex).
func StringSplit(s *String, regex *String) *Array[any] { return StringSplitLimit(s, regex, 0) }

// StringSplitLimit implements split(regex, limit) with Java's rules: a zero-width match at the
// start yields no leading empty string, limit > 0 caps the number of parts, and limit == 0
// drops trailing empty strings.
func StringSplitLimit(s *String, regex *String, limit int32) *Array[any] {
	if c, ok := literalSplitChar(regex.chars); ok {
		return splitOnChar(s, c, limit)
	}
	return splitWith(compileRegex(regex.GoString()), s, limit)
}

// literalSplitChar reports whether regex matches exactly one char and nothing else, as the JDK's
// fast path does: one char that is not a metacharacter, or a backslash and an ASCII char that
// is neither a letter nor a digit.
func literalSplitChar(regex []uint16) (uint16, bool) {
	isASCIIAlnum := func(c uint16) bool { return c < 0x80 && (unicode.IsLetter(rune(c)) || unicode.IsDigit(rune(c))) }
	switch {
	case len(regex) == 1 && !strings.ContainsRune(".$|()[{^?*+\\", rune(regex[0])) && !utf16.IsSurrogate(rune(regex[0])):
		return regex[0], true
	case len(regex) == 2 && regex[0] == '\\' && regex[1] < 0x80 && !isASCIIAlnum(regex[1]):
		return regex[1], true
	}
	return 0, false
}

// splitOnChar is split with a regex matching only c, on the UTF-16 chars (no transcoding). The
// parts share the string's chars, and their headers are allocated together.
func splitOnChar(s *String, c uint16, limit int32) *Array[any] {
	chars := s.chars
	n := 0 // number of parts
	if len(chars) > 0 {
		n = 1
		for _, x := range chars {
			if x == c && (limit <= 0 || int32(n) < limit) {
				n++
			}
		}
	}
	if n <= 1 {
		return &Array[any]{Data: []any{s}} // no match: the string itself, as in the JDK
	}
	strs := make([]String, n)
	parts := make([]any, n)
	start, k := 0, 0
	for i, x := range chars {
		if x == c && k < n-1 {
			strs[k].chars = chars[start:i:i]
			parts[k] = &strs[k]
			start, k = i+1, k+1
		}
	}
	strs[k].chars = chars[start:len(chars):len(chars)]
	parts[k] = &strs[k]
	if limit == 0 {
		for n > 0 && len(strs[n-1].chars) == 0 {
			n--
		}
		parts = parts[:n]
	}
	return &Array[any]{Data: parts}
}

// splitWith is Pattern.split: the parts between the matches Java finds (see findAll).
func splitWith(re *regexp.Regexp, s *String, limit int32) *Array[any] {
	str := s.GoString()
	var parts []string
	start := 0
	for _, m := range findAll(re, str) {
		if limit > 0 && int32(len(parts)) == limit-1 {
			break
		}
		if m[1] == 0 { // zero-width match at the beginning
			continue
		}
		parts = append(parts, str[start:m[0]])
		start = m[1]
	}
	if parts == nil {
		return &Array[any]{Data: []any{s}} // no match: the string itself, as in the JDK
	}
	parts = append(parts, str[start:])
	if limit == 0 {
		for len(parts) > 0 && parts[len(parts)-1] == "" {
			parts = parts[:len(parts)-1]
		}
	}
	out := make([]any, len(parts))
	for i, p := range parts {
		out[i] = Str(p)
	}
	return &Array[any]{Data: out}
}

var (
	regexMu    sync.Mutex
	regexCache = map[string]*regexp.Regexp{}
)

// compileRegex compiles a Java regular expression with Go's regexp package (RE2 syntax, which
// covers the common Java syntax). An invalid pattern throws PatternSyntaxException; features RE2
// lacks (lookaround, backreferences, possessive quantifiers) throw UnsupportedOperationException.
func compileRegex(pattern string) *regexp.Regexp {
	regexMu.Lock()
	defer regexMu.Unlock()
	if re, ok := regexCache[pattern]; ok {
		return re
	}
	re, err := regexp.Compile(pattern)
	if err != nil {
		throwRegexError(pattern, err)
	}
	regexCache[pattern] = re
	return re
}

// StringMatches implements matches(regex): the whole string must match. The pattern is compiled
// alone first, so that the anchors added around it cannot make an invalid one valid (`a)|(b`).
func StringMatches(s *String, regex *String) bool {
	p := regex.GoString()
	compileRegex(p)
	return compileRegex("^(?:" + p + ")$").MatchString(s.GoString())
}

// StringReplaceAll implements replaceAll(regex, replacement) (see replaceMatches).
func StringReplaceAll(s *String, regex *String, replacement *String) *String {
	return replaceMatches(compileRegex(regex.GoString()), s, replacement, true)
}

// StringReplaceFirst implements replaceFirst(regex, replacement).
func StringReplaceFirst(s *String, regex *String, replacement *String) *String {
	return replaceMatches(compileRegex(regex.GoString()), s, replacement, false)
}

// ---- Static methods -----------------------------------------------------------------------

func StringValueOf(x any) *String { return ToString(x) }

func StringValueOfChar(c uint16) *String { return newString([]uint16{c}) }

// Concat implements string concatenation: String.valueOf(a) + String.valueOf(b).
func Concat(a, b any) *String { return StringConcat(ToString(a), ToString(b)) }

// ConcatAll implements a chain of string concatenations, once all the operands are evaluated.
func ConcatAll(parts ...any) *String {
	strs := make([]*String, len(parts))
	n := 0
	for i, p := range parts {
		strs[i] = ToString(p)
		n += len(strs[i].chars)
	}
	chars := make([]uint16, 0, n)
	for _, s := range strs {
		chars = append(chars, s.chars...)
	}
	return newString(chars)
}

func StringGetChars(s *String, srcBegin, srcEnd int32, dst *Array[uint16], dstBegin int32) {
	s.checkRange(srcBegin, srcEnd)
	checkStringRangeSize(dstBegin, srcEnd-srcBegin, len(dst.Data))
	copy(dst.Data[dstBegin:], s.chars[srcBegin:srcEnd])
}

// ---- Charsets ------------------------------------------------------------------------------------
//
// Charsets are given by name: a *String, or a java.nio.charset.Charset (javalib's), whose
// toString is its name. UTF-8, ISO-8859-1 and US-ASCII are supported; another name throws
// UnsupportedEncodingException, as an unknown one does in the JDK.

func charsetOf(charset any) string {
	if IsNull(charset) {
		ThrowKind(NullPointer, "")
	}
	name := strings.ToUpper(strings.ReplaceAll(ToGoString(charset), "_", "-"))
	switch name {
	case "UTF-8", "UTF8":
		return "UTF-8"
	case "ISO-8859-1", "ISO8859-1", "LATIN1":
		return "ISO-8859-1"
	case "US-ASCII", "ASCII":
		return "US-ASCII"
	}
	ThrowKind(UnsupportedEncoding, ToGoString(charset))
	return ""
}

// NewStringFromBytes implements new String(bytes, charset).
func NewStringFromBytes(a *Array[int8], charset any) *String {
	return newStringFromBytes(Reinterpret[int8, byte](a.Data), charset)
}

// NewStringFromByteRange implements new String(bytes, offset, length, charset).
func NewStringFromByteRange(a *Array[int8], offset, length int32, charset any) *String {
	checkStringRangeSize(offset, length, len(a.Data))
	return newStringFromBytes(Reinterpret[int8, byte](a.Data[offset:offset+length]), charset)
}

func newStringFromBytes(bytes []byte, charset any) *String {
	switch charsetOf(charset) {
	case "UTF-8":
		if utf8.Valid(bytes) {
			return Str(string(bytes))
		}
		return newString(decodeMalformedUTF8(bytes))
	default:
		chars := make([]uint16, len(bytes))
		for i, b := range bytes {
			chars[i] = uint16(b)
			if b >= 0x80 && charsetOf(charset) == "US-ASCII" {
				chars[i] = 0xfffd
			}
		}
		return newString(chars)
	}
}

// decodeMalformedUTF8 decodes UTF-8 as the JDK does: each maximal prefix of a valid sequence
// (rather than each byte, as Go does) becomes one U+FFFD, and so does an encoded surrogate
// (ED A0..BF 80..BF), which Go's DecodeRune rejects.
func decodeMalformedUTF8(bytes []byte) []uint16 {
	chars := make([]uint16, 0, len(bytes))
	for i := 0; i < len(bytes); {
		b := bytes[i]
		if b < utf8.RuneSelf {
			chars = append(chars, uint16(b))
			i++
			continue
		}
		// The sequence's length, and the range of its second byte (the others are 80..BF).
		n, lo, hi := 1, byte(0x80), byte(0xbf)
		switch {
		case b >= 0xc2 && b <= 0xdf:
			n = 2
		case b >= 0xe0 && b <= 0xef:
			n = 3
		case b >= 0xf0 && b <= 0xf4:
			n = 4
		}
		switch b {
		case 0xe0:
			lo = 0xa0
		case 0xf0:
			lo = 0x90
		case 0xf4:
			hi = 0x8f
		}
		valid := 1
		for valid < n && i+valid < len(bytes) {
			c := bytes[i+valid]
			if valid == 1 && (c < lo || c > hi) || valid > 1 && (c < 0x80 || c > 0xbf) {
				break
			}
			valid++
		}
		if valid == n && n > 1 {
			r, _ := utf8.DecodeRune(bytes[i : i+n])
			chars = utf16.AppendRune(chars, r)
		} else {
			chars = append(chars, 0xfffd)
		}
		i += valid
	}
	return chars
}

// StringGetBytes implements String.getBytes(charset).
func StringGetBytes(s *String, charset any) *Array[int8] {
	switch charsetOf(charset) {
	case "UTF-8":
		return ArrayOfSlice(Reinterpret[byte, int8](appendUTF16(make([]byte, 0, len(s.chars)), s.chars)))
	default:
		limit := uint16(0xff)
		if charsetOf(charset) == "US-ASCII" {
			limit = 0x7f
		}
		// Unmappable chars become '?', a surrogate pair a single one.
		out := make([]int8, 0, len(s.chars))
		for i := 0; i < len(s.chars); i++ {
			c := s.chars[i]
			if c > limit {
				if utf16.IsSurrogate(rune(c)) && c < 0xdc00 && i+1 < len(s.chars) &&
					s.chars[i+1] >= 0xdc00 && s.chars[i+1] <= 0xdfff {
					i++
				}
				c = '?'
			}
			out = append(out, int8(c))
		}
		return ArrayOfSlice(out)
	}
}

// StringJoin implements String.join(delimiter, elements...): null elements are "null", a null
// delimiter or array throws NullPointerException.
func StringJoin(delimiter any, elements *Array[any]) *String {
	if IsNull(delimiter) || elements == nil {
		ThrowKind(NullPointer, "")
	}
	sep := ToString(delimiter).chars
	var chars []uint16
	for i, e := range elements.Data {
		if i > 0 {
			chars = append(chars, sep...)
		}
		chars = append(chars, ToString(e).chars...)
	}
	return newString(chars)
}

// Locale-taking variants. scala-go has no locale data: every locale behaves as Locale.ROOT
// (docs/design.md), but a null locale throws as in the JDK.

func StringToLowerCaseLocale(s *String, locale any) *String {
	if IsNull(locale) {
		ThrowKind(NullPointer, "")
	}
	return StringToLowerCase(s)
}

func StringToUpperCaseLocale(s *String, locale any) *String {
	if IsNull(locale) {
		ThrowKind(NullPointer, "")
	}
	return StringToUpperCase(s)
}
