package rt

import (
	"errors"
	"regexp"
	"regexp/syntax"
	"strconv"
	"strings"
	"sync"
	"unicode/utf8"
)

// java.util.regex on Go's regexp (RE2). Java features RE2 lacks (lookaround, backreferences,
// possessive quantifiers, COMMENTS) throw UnsupportedOperationException when compiling. Matches
// are found and replaced with Java's rules (findAll, parseReplacement).

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
	compileRegex(p) // checks the pattern alone (see StringMatches)
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
		in.all = findAll(in.r.re, in.text)
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
	return replaceMatches(Cast[*Regex](r).re, input, replacement, all)
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
	return Str(string(parseReplacement(in.r.re, replacement.GoString()).expand(nil, in.text, m)))
}

// findAll returns the matches of re in s that Java's Matcher.find finds in turn: Go's, and the
// empty matches right after a match, which Go skips ("a*" in "aaa" matches "aaa" and then the
// empty string at the end; in Go, only "aaa").
func findAll(re *regexp.Regexp, s string) [][]int {
	matches := re.FindAllStringSubmatchIndex(s, -1)
	var prog *syntax.Prog // computed at the first match that may be followed by a skipped one
	var out [][]int       // with the skipped matches; nil while there are none
	for i, m := range matches {
		if out != nil {
			out = append(out, m)
		}
		if m[0] == m[1] || i+1 < len(matches) && matches[i+1][0] == m[1] {
			continue
		}
		if prog == nil {
			if prog = emptyMatcher(re); prog == nil {
				return matches // re never matches the empty string
			}
		}
		if e := emptyMatchAt(prog, s, m[1], len(m)); e != nil {
			if out == nil {
				out = append(make([][]int, 0, len(matches)+1), matches[:i+1]...)
			}
			out = append(out, e)
		}
	}
	if out == nil {
		return matches
	}
	return out
}

// emptyMatchers caches emptyMatcher's result by regexp.
var emptyMatchers sync.Map

// emptyMatcher returns the program of re (as regexp.Compile builds it), or nil if re cannot
// match the empty string anywhere.
func emptyMatcher(re *regexp.Regexp) *syntax.Prog {
	if prog, ok := emptyMatchers.Load(re); ok {
		return prog.(*syntax.Prog)
	}
	var prog *syntax.Prog
	if parsed, err := syntax.Parse(re.String(), syntax.Perl); err == nil {
		if p, err := syntax.Compile(parsed.Simplify()); err == nil && followEmpty(p, ^syntax.EmptyOp(0), nil, 0) {
			prog = p
		}
	}
	emptyMatchers.Store(re, prog)
	return prog
}

// emptyMatchAt returns the groups of prog's empty match at byte offset pos of s (pos, or -1 for
// a group that does not take part), or nil if there is none. groups is the length of the result.
func emptyMatchAt(prog *syntax.Prog, s string, pos int, groups int) []int {
	before, after := rune(-1), rune(-1)
	if pos > 0 {
		before, _ = utf8.DecodeLastRuneInString(s[:pos])
	}
	if pos < len(s) {
		after, _ = utf8.DecodeRuneInString(s[pos:])
	}
	m := make([]int, groups)
	for i := range m {
		m[i] = -1
	}
	if !followEmpty(prog, syntax.EmptyOpContext(before, after), m, pos) {
		return nil
	}
	m[0], m[1] = pos, pos // the program has no capture for the whole match
	return m
}

// followEmpty reports whether prog matches without consuming input where the conditions in
// context hold, setting the captures of the match in m to pos. Like Go's matchers, it follows
// the instructions in priority order and visits each once, so the match is the one Go prefers.
func followEmpty(prog *syntax.Prog, context syntax.EmptyOp, m []int, pos int) bool {
	visited := make([]bool, len(prog.Inst))
	var follow func(pc uint32) bool
	follow = func(pc uint32) bool {
		if visited[pc] {
			return false
		}
		visited[pc] = true
		switch inst := &prog.Inst[pc]; inst.Op {
		case syntax.InstMatch:
			return true
		case syntax.InstAlt, syntax.InstAltMatch:
			return follow(inst.Out) || follow(inst.Arg)
		case syntax.InstNop:
			return follow(inst.Out)
		case syntax.InstEmptyWidth:
			return syntax.EmptyOp(inst.Arg)&^context == 0 && follow(inst.Out)
		case syntax.InstCapture:
			if int(inst.Arg) >= len(m) {
				return follow(inst.Out)
			}
			old := m[inst.Arg]
			m[inst.Arg] = pos
			if follow(inst.Out) {
				return true
			}
			m[inst.Arg] = old
		}
		return false // an instruction that consumes input, or InstFail
	}
	return follow(uint32(prog.Start))
}

// replaceMatches replaces the first match of re in s, or all of them (see findAll), with a Java
// replacement string.
func replaceMatches(re *regexp.Regexp, s *String, replacement *String, all bool) *String {
	str := s.GoString()
	var matches [][]int
	if all {
		matches = findAll(re, str)
	} else if m := re.FindStringSubmatchIndex(str); m != nil {
		matches = [][]int{m}
	}
	if matches == nil {
		return s
	}
	repl := parseReplacement(re, replacement.GoString())
	out := make([]byte, 0, len(str))
	end := 0
	for _, m := range matches {
		out = repl.expand(append(out, str[end:m[0]]...), str, m)
		end = m[1]
	}
	return Str(string(append(out, str[end:]...)))
}

// replacement is a parsed Java replacement string.
type replacement []replacementPart

// replacementPart is literal text, or (if group >= 0) a group's text.
type replacementPart struct {
	text  string
	group int
}

// parseReplacement parses a replacement string for re: `\` escapes the next char, `$n` names
// group n (taking digits while the number is a group), `${name}` a named group. It throws as
// Matcher.appendReplacement does.
func parseReplacement(re *regexp.Regexp, r string) replacement {
	var parts replacement
	var text []byte
	addText := func() {
		if len(text) > 0 {
			parts = append(parts, replacementPart{string(text), -1})
			text = text[:0]
		}
	}
	addGroup := func(group int) {
		addText()
		parts = append(parts, replacementPart{"", group})
	}
	isDigit := func(c byte) bool { return c >= '0' && c <= '9' }
	for i := 0; i < len(r); i++ {
		switch c := r[i]; c {
		case '\\':
			i++
			if i == len(r) {
				ThrowKind(IllegalArgument, "character to be escaped is missing")
			}
			text = append(text, r[i])
		case '$':
			i++
			switch {
			case i == len(r):
				ThrowKind(IllegalArgument, "Illegal group reference: group index is missing")
			case r[i] == '{':
				j := i + 1
				for j < len(r) && (isDigit(r[j]) || r[j]|0x20 >= 'a' && r[j]|0x20 <= 'z') {
					j++
				}
				name := r[i+1 : j]
				switch {
				case name == "":
					ThrowKind(IllegalArgument, "named capturing group has 0 length name")
				case j == len(r) || r[j] != '}':
					ThrowKind(IllegalArgument, "named capturing group is missing trailing '}'")
				case isDigit(name[0]):
					ThrowKind(IllegalArgument, "capturing group name {"+name+"} starts with digit character")
				}
				group := re.SubexpIndex(name)
				if group < 0 {
					ThrowKind(IllegalArgument, "No group with name {"+name+"}")
				}
				addGroup(group)
				i = j
			case isDigit(r[i]):
				group := int(r[i] - '0')
				for i+1 < len(r) && isDigit(r[i+1]) && group*10+int(r[i+1]-'0') <= re.NumSubexp() {
					i++
					group = group*10 + int(r[i]-'0')
				}
				if group > re.NumSubexp() {
					ThrowKind(IndexOutOfBounds, "No group "+strconv.Itoa(group))
				}
				addGroup(group)
			default:
				ThrowKind(IllegalArgument, "Illegal group reference")
			}
		default:
			text = append(text, c)
		}
	}
	addText()
	return parts
}

// expand appends the replacement of match m (byte offsets in s, -1 for groups that did not
// match) to dst.
func (r replacement) expand(dst []byte, s string, m []int) []byte {
	for _, p := range r {
		switch {
		case p.group < 0:
			dst = append(dst, p.text...)
		case m[2*p.group] >= 0:
			dst = append(dst, s[m[2*p.group]:m[2*p.group+1]]...)
		}
	}
	return dst
}

// MakePatternSyntaxException is installed by the generated program: it instantiates
// java.util.regex.PatternSyntaxException.
var MakePatternSyntaxException func(description, regex *String, index int32) any

// throwRegexError throws PatternSyntaxException (with Go's description, at no index) if Java
// rejects the pattern too, or else UnsupportedOperationException: the pattern uses Java syntax
// RE2 does not have.
func throwRegexError(pattern string, err error) {
	var e *syntax.Error
	if errors.As(err, &e) && javaRejects(e) {
		if MakePatternSyntaxException == nil {
			ThrowKind(IllegalArgument, string(e.Code))
		}
		panic(&Exception{MakePatternSyntaxException(Str(string(e.Code)), Str(pattern), -1)})
	}
	ThrowKind(UnsupportedOperation, "unsupported regular expression "+pattern+": "+err.Error())
}

// javaRejects reports whether a Go syntax error is one in Java too. Go reports Java's property
// classes (`\p{Lower}`) and its nested classes (`[[:x:]]`) as invalid ranges.
func javaRejects(e *syntax.Error) bool {
	switch e.Code {
	case syntax.ErrMissingParen, syntax.ErrUnexpectedParen, syntax.ErrMissingBracket,
		syntax.ErrMissingRepeatArgument, syntax.ErrTrailingBackslash:
		return true
	case syntax.ErrInvalidCharRange:
		return !strings.HasPrefix(e.Expr, `\p`) && !strings.HasPrefix(e.Expr, `\P`) && !strings.HasPrefix(e.Expr, "[")
	}
	return false
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
