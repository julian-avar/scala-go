package rt

import (
	"strings"
	"unicode"
	"unicode/utf16"
)

// String is java.lang.String: an immutable sequence of UTF-16 code units. A nil *String is null.
//
// Go strings (UTF-8) are only used at the edges: literals in generated code, I/O and interop.
type String struct {
	chars []uint16
	hash  int32 // cached hashCode; 0 means not computed yet (as in the JDK)
}

// Str converts a Go (UTF-8) string, such as a literal in generated code.
func Str(s string) *String {
	return &String{chars: utf16.Encode([]rune(s))}
}

// StrUTF16 builds a string from UTF-16 code units (used for literals Go cannot express, such as
// lone surrogates).
func StrUTF16(chars ...uint16) *String {
	return &String{chars: chars}
}

func newString(chars []uint16) *String { return &String{chars: chars} }

// GoString converts to a Go string. Unpaired surrogates become U+FFFD.
func (s *String) GoString() string { return string(utf16.Decode(s.chars)) }

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
	if s.hash == 0 {
		var h int32
		for _, c := range s.chars {
			h = 31*h + int32(c)
		}
		s.hash = h
	}
	return s.hash
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

var internTable = map[string]*String{}

func StringIntern(s *String) *String {
	key := string(utf16.Decode(s.chars))
	if t, ok := internTable[key]; ok && stringEq(t, s) {
		return t
	}
	internTable[key] = s
	return s
}

// StringSplit implements split(regex) for patterns without regular-expression metacharacters
// (and the common single-character escapes). Full regex support is not implemented yet.
func StringSplit(s *String, regex *String) *Array[any] {
	sep := regex.GoString()
	if len(sep) == 2 && sep[0] == '\\' {
		sep = sep[1:]
	} else if strings.ContainsAny(sep, `.$|()[{^?*+\`) {
		ThrowKind(UnsupportedOperation, "regular expressions are not supported yet: "+sep)
	}
	if len(s.chars) == 0 {
		return &Array[any]{Data: []any{s}}
	}
	parts := strings.Split(s.GoString(), sep)
	// Java drops trailing empty strings.
	for len(parts) > 0 && parts[len(parts)-1] == "" {
		parts = parts[:len(parts)-1]
	}
	out := make([]any, len(parts))
	for i, p := range parts {
		out[i] = Str(p)
	}
	return &Array[any]{Data: out}
}

// ---- Static methods -----------------------------------------------------------------------

func StringValueOf(x any) *String { return ToString(x) }

func StringValueOfChar(c uint16) *String { return newString([]uint16{c}) }

// Concat implements string concatenation: String.valueOf(a) + String.valueOf(b).
func Concat(a, b any) *String { return StringConcat(ToString(a), ToString(b)) }

func StringGetChars(s *String, srcBegin, srcEnd int32, dst *Array[uint16], dstBegin int32) {
	s.checkRange(srcBegin, srcEnd)
	copy(dst.Data[dstBegin:], s.chars[srcBegin:srcEnd])
}
