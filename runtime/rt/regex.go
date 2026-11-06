package rt

import (
	"regexp"
	"strings"
	"unicode/utf8"
)

// java.util.regex on Go's regexp (RE2). Java features RE2 lacks (lookaround, backreferences,
// possessive quantifiers, COMMENTS) throw UnsupportedOperationException when compiling.

// Pattern flags (java.util.regex.Pattern).
const (
	patternCaseInsensitive = 2
	patternComments        = 4
	patternMultiline       = 8
	patternLiteral         = 16
	patternDotall          = 32
)

// Regex is a compiled java.util.regex.Pattern: the pattern, anchored at the start (lookingAt),
// and anchored at both ends (matches).
type Regex struct {
	re, start, full *regexp.Regexp
}

func RegexCompile(pattern *String, flags int32) any {
	p := pattern.GoString()
	if flags&patternLiteral != 0 {
		p = regexp.QuoteMeta(p)
	}
	if flags&patternComments != 0 {
		ThrowKind(UnsupportedOperation, "Pattern.COMMENTS is not supported")
	}
	prefix := ""
	if flags&patternCaseInsensitive != 0 {
		prefix += "i"
	}
	if flags&patternMultiline != 0 {
		prefix += "m"
	}
	if flags&patternDotall != 0 {
		prefix += "s"
	}
	if prefix != "" {
		p = "(?" + prefix + ")" + p
	}
	return &Regex{re: compileRegex(p), start: compileRegex(`\A(?:` + p + `)`), full: compileRegex(`\A(?:` + p + `)\z`)}
}

func RegexGroupCount(r any) int32 { return int32(Cast[*Regex](r).re.NumSubexp()) }

// RegexGroupIndex is the number of a named group, or -1.
func RegexGroupIndex(r any, name *String) int32 {
	i := Cast[*Regex](r).re.SubexpIndex(name.GoString())
	return int32(i)
}

// RegexInput is a Matcher's input: the UTF-8 text, and the UTF-16 offset of each byte offset.
type RegexInput struct {
	r       *Regex
	text    string
	charsAt []int32 // UTF-16 offset of each byte offset (len(text)+1 entries)
	all     [][]int // all matches (byte offsets), computed on the first find
}

func RegexNewInput(r any, input *String) any {
	text := input.GoString()
	charsAt := make([]int32, len(text)+1)
	var c int32
	for i, ch := range text {
		n := utf8.RuneLen(ch)
		for k := 0; k < n; k++ {
			charsAt[i+k] = c
		}
		if ch >= 0x10000 {
			c += 2
		} else {
			c++
		}
	}
	charsAt[len(text)] = c
	return &RegexInput{r: Cast[*Regex](r), text: text, charsAt: charsAt}
}

// groups converts a match (byte offsets, -1 for unmatched groups) to UTF-16 offsets.
func (in *RegexInput) groups(m []int) *Array[int32] {
	out := make([]int32, len(m))
	for i, b := range m {
		if b < 0 {
			out[i] = -1
		} else {
			out[i] = in.charsAt[b]
		}
	}
	return &Array[int32]{Data: out}
}

// RegexFind returns the first match starting at or after UTF-16 offset from (start and end
// offsets of each group), or null. Matches are found in one pass over the whole input, so
// anchors and word boundaries see the text before `from`.
func RegexFind(i any, from int32) *Array[int32] {
	in := Cast[*RegexInput](i)
	if in.all == nil {
		in.all = in.r.re.FindAllStringSubmatchIndex(in.text, -1)
		if in.all == nil {
			in.all = [][]int{}
		}
	}
	for _, m := range in.all {
		if in.charsAt[m[0]] >= from {
			return in.groups(m)
		}
	}
	return nil
}

// RegexMatchStart matches at the start of the input (Matcher.lookingAt), or the whole input
// (Matcher.matches) if whole is set; null if it does not match.
func RegexMatchStart(i any, whole bool) *Array[int32] {
	in := Cast[*RegexInput](i)
	re := in.r.start
	if whole {
		re = in.r.full
	}
	m := re.FindStringSubmatchIndex(in.text)
	if m == nil {
		return nil
	}
	return in.groups(m)
}

// RegexSplit implements Pattern.split (Java's rules, as String.split).
func RegexSplit(r any, input *String, limit int32) *Array[any] {
	return splitWith(Cast[*Regex](r).re, input, limit)
}

// RegexReplace replaces all matches (or the first) with a Java replacement string.
func RegexReplace(r any, input *String, replacement *String, all bool) *String {
	re := Cast[*Regex](r).re
	repl := javaReplacement(replacement.GoString())
	str := input.GoString()
	if all {
		return Str(re.ReplaceAllString(str, repl))
	}
	m := re.FindStringSubmatchIndex(str)
	if m == nil {
		return input
	}
	return Str(str[:m[0]] + string(re.ExpandString(nil, repl, str, m)) + str[m[1]:])
}

// RegexExpand expands a Java replacement string for a match (Matcher.appendReplacement).
func RegexExpand(i any, replacement *String, groups *Array[int32]) *String {
	in := Cast[*RegexInput](i)
	// Back to byte offsets.
	m := make([]int, len(groups.Data))
	for k, c := range groups.Data {
		m[k] = -1
		if c >= 0 {
			m[k] = byteOffset(in.charsAt, c)
		}
	}
	return Str(string(in.r.re.ExpandString(nil, javaReplacement(replacement.GoString()), in.text, m)))
}

func byteOffset(charsAt []int32, c int32) int {
	lo, hi := 0, len(charsAt)-1
	for lo < hi {
		mid := (lo + hi) / 2
		if charsAt[mid] < c {
			lo = mid + 1
		} else {
			hi = mid
		}
	}
	return lo
}

// RegexQuoteReplacement implements Matcher.quoteReplacement.
func RegexQuoteReplacement(s *String) *String {
	r := strings.NewReplacer(`\`, `\\`, `$`, `\$`)
	return Str(r.Replace(s.GoString()))
}
