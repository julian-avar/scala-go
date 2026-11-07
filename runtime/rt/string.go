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

// String implements fmt.Stringer.
func (s *String) String() string { return s.GoString() }

// Chars exposes the UTF-16 code units (read-only).
func (s *String) Chars() []uint16 { return s.chars }

func (s *String) checkIndex(i int32) {
	if i < 0 || int(i) >= len(s.chars) {
		ThrowKind(StringIndexOutOfBounds, "index "+itoa(i)+", length "+itoa(int32(len(s.chars))))
	}
}

func (s *String) checkRange(begin, end int32) {
	if begin < 0 || end > int32(len(s.chars)) || begin > end {
		ThrowKind(StringIndexOutOfBounds, "begin "+itoa(begin)+", end "+itoa(end)+", length "+itoa(int32(len(s.chars))))
	}
}

// ---- Constructors -------------------------------------------------------------------------

func NewStringEmpty() *String { return &String{} }

func NewStringFromChars(a *Array[uint16]) *String {
	return newString(append([]uint16(nil), a.Data...))
}

func NewStringFromCharRange(a *Array[uint16], offset, count int32) *String {
	if offset < 0 || count < 0 || int(offset+count) > len(a.Data) {
		ThrowKind(StringIndexOutOfBounds, "offset "+itoa(offset)+", count "+itoa(count)+", length "+itoa(int32(len(a.Data))))
	}
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

func StringIndexOfChar(s *String, c int32, from int32) int32 {
	from = max(from, 0)
	if c >= 0x10000 {
		return StringIndexOf(s, Str(string(rune(c))), from)
	}
	for i := int(from); i < len(s.chars); i++ {
		if int32(s.chars[i]) == c {
			return int32(i)
		}
	}
	return -1
}

func StringLastIndexOfChar(s *String, c int32, from int32) int32 {
	for i := min(int(from), len(s.chars)-1); i >= 0; i-- {
		if int32(s.chars[i]) == c {
			return int32(i)
		}
	}
	return -1
}

func StringIndexOf(s *String, sub *String, from int32) int32 {
	from = max(from, 0)
	n, m := len(s.chars), len(sub.chars)
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

func StringToUpperCase(s *String) *String { return Str(strings.ToUpper(s.GoString())) }
func StringToLowerCase(s *String) *String { return Str(strings.ToLower(s.GoString())) }

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

func StringStrip(s *String) *String { return Str(strings.TrimFunc(s.GoString(), unicode.IsSpace)) }

func StringIsBlank(s *String) bool { return len(StringStrip(s).chars) == 0 }

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
	internMu    sync.Mutex
	internTable = map[string]*String{}
)

func StringIntern(s *String) *String {
	key := string(utf16.Decode(s.chars))
	internMu.Lock()
	defer internMu.Unlock()
	if t, ok := internTable[key]; ok && stringEq(t, s) {
		return t
	}
	internTable[key] = s
	return s
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

func splitWith(re *regexp.Regexp, s *String, limit int32) *Array[any] {
	str := s.GoString()
	if len(str) == 0 {
		return &Array[any]{Data: []any{s}}
	}
	var parts []string
	start := 0
	for _, m := range re.FindAllStringIndex(str, -1) {
		if limit > 0 && int32(len(parts)) == limit-1 {
			break
		}
		if m[1] == 0 { // zero-width match at the beginning
			continue
		}
		if m[0] == m[1] && m[0] >= len(str) {
			break
		}
		parts = append(parts, str[start:m[0]])
		start = m[1]
	}
	parts = append(parts, str[start:])
	if limit == 0 {
		for len(parts) > 1 && parts[len(parts)-1] == "" {
			parts = parts[:len(parts)-1]
		}
		if len(parts) == 1 && parts[0] == "" {
			parts = nil
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
// covers the common Java syntax). Features RE2 lacks (lookaround, backreferences, possessive
// quantifiers) throw UnsupportedOperationException.
func compileRegex(pattern string) *regexp.Regexp {
	regexMu.Lock()
	defer regexMu.Unlock()
	if re, ok := regexCache[pattern]; ok {
		return re
	}
	re, err := regexp.Compile(pattern)
	if err != nil {
		ThrowKind(UnsupportedOperation, "unsupported regular expression "+pattern+": "+err.Error())
	}
	regexCache[pattern] = re
	return re
}

// StringMatches implements matches(regex): the whole string must match.
func StringMatches(s *String, regex *String) bool {
	re := compileRegex("^(?:" + regex.GoString() + ")$")
	return re.MatchString(s.GoString())
}

// StringReplaceAll implements replaceAll(regex, replacement) ($1 and \$ in the replacement, as in Java).
func StringReplaceAll(s *String, regex *String, replacement *String) *String {
	return Str(compileRegex(regex.GoString()).ReplaceAllString(s.GoString(), javaReplacement(replacement.GoString())))
}

// StringReplaceFirst implements replaceFirst(regex, replacement).
func StringReplaceFirst(s *String, regex *String, replacement *String) *String {
	re := compileRegex(regex.GoString())
	str := s.GoString()
	m := re.FindStringSubmatchIndex(str)
	if m == nil {
		return s
	}
	var out []byte
	out = re.ExpandString(out, javaReplacement(replacement.GoString()), str, m)
	return Str(str[:m[0]] + string(out) + str[m[1]:])
}

// javaReplacement converts Java's replacement syntax ($1, \$) to Go's (${1}, $$).
func javaReplacement(r string) string {
	var b strings.Builder
	for i := 0; i < len(r); i++ {
		switch c := r[i]; {
		case c == '\\' && i+1 < len(r):
			i++
			if r[i] == '$' {
				b.WriteString("$$")
			} else {
				b.WriteByte(r[i])
			}
		case c == '$':
			j := i + 1
			for j < len(r) && r[j] >= '0' && r[j] <= '9' {
				j++
			}
			b.WriteString("${" + r[i+1:j] + "}")
			i = j - 1
		default:
			b.WriteByte(c)
		}
	}
	return b.String()
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
	copy(dst.Data[dstBegin:], s.chars[srcBegin:srcEnd])
}

// ---- Charsets ------------------------------------------------------------------------------------
//
// A java.nio.charset.Charset is represented by its name (a *String). UTF-8, ISO-8859-1 and
// US-ASCII are supported.

func charsetOf(charset any) string {
	name := strings.ToUpper(strings.ReplaceAll(ToGoString(charset), "_", "-"))
	switch name {
	case "UTF-8", "UTF8":
		return "UTF-8"
	case "ISO-8859-1", "ISO8859-1", "LATIN1":
		return "ISO-8859-1"
	case "US-ASCII", "ASCII":
		return "US-ASCII"
	}
	ThrowKind(UnsupportedOperation, "unsupported charset: "+ToGoString(charset))
	return ""
}

// NewStringFromBytes implements new String(bytes, charset).
func NewStringFromBytes(a *Array[int8], charset any) *String {
	bytes := Reinterpret[int8, byte](a.Data)
	switch charsetOf(charset) {
	case "UTF-8":
		return Str(string(bytes))
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

// NewStringFromByteRange implements new String(bytes, offset, length, charset).
func NewStringFromByteRange(a *Array[int8], offset, length int32, charset any) *String {
	if offset < 0 || length < 0 || int(offset)+int(length) > len(a.Data) {
		ThrowKind(StringIndexOutOfBounds, "offset "+itoa(offset)+", count "+itoa(length)+", length "+itoa(int32(len(a.Data))))
	}
	return NewStringFromBytes(&Array[int8]{Data: a.Data[offset : offset+length]}, charset)
}

// StringGetBytes implements String.getBytes(charset).
func StringGetBytes(s *String, charset any) *Array[int8] {
	switch charsetOf(charset) {
	case "UTF-8":
		return ArrayOfSlice(Reinterpret[byte, int8]([]byte(s.GoString())))
	default:
		limit := uint16(0xff)
		if charsetOf(charset) == "US-ASCII" {
			limit = 0x7f
		}
		out := make([]int8, len(s.chars))
		for i, c := range s.chars {
			if c > limit {
				c = '?'
			}
			out[i] = int8(c)
		}
		return ArrayOfSlice(out)
	}
}
