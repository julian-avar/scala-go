package rt

import (
	"regexp"
	"testing"
)

func splitStrings(a *Array[any]) []string {
	out := make([]string, len(a.Data))
	for i, x := range a.Data {
		out[i] = x.(*String).GoString()
	}
	return out
}

// The single-char fast path of split must agree with the regex path.
func TestSplitOnCharMatchesRegex(t *testing.T) {
	inputs := []string{"", ",", ",,", "a", "a,b", ",a,b,", "a,,b,,", ",,a", "ü,ß,,😀,", "a,b,c,d,e"}
	for _, in := range inputs {
		for _, limit := range []int32{-1, 0, 1, 2, 3, 10} {
			fast := splitStrings(splitOnChar(Str(in), ',', limit))
			slow := splitStrings(splitWith(regexp.MustCompile(","), Str(in), limit))
			if len(fast) != len(slow) {
				t.Errorf("split(%q, %d): fast %q, regex %q", in, limit, fast, slow)
				continue
			}
			for i := range fast {
				if fast[i] != slow[i] {
					t.Errorf("split(%q, %d): fast %q, regex %q", in, limit, fast, slow)
					break
				}
			}
		}
	}
}

func TestLiteralSplitChar(t *testing.T) {
	for regex, want := range map[string]bool{
		",": true, " ": true, "é": true, "\\.": true, "\\|": true, "\\$": true,
		".": false, "|": false, "\\d": false, "\\s": false, "ab": false, "\\": false, "\\1": false, "": false,
	} {
		if _, got := literalSplitChar(Str(regex).chars); got != want {
			t.Errorf("literalSplitChar(%q) = %v, want %v", regex, got, want)
		}
	}
}

func TestUTF16RoundTrip(t *testing.T) {
	for _, s := range []string{"", "ascii", "héllo", "😀 smile", "a\xffb", "日本語"} {
		got := Str(s).GoString()
		want := string([]rune(s)) // invalid bytes become U+FFFD
		if got != want {
			t.Errorf("round trip of %q gave %q", s, got)
		}
	}
	if got := StrUTF16(0xd800, 'x').GoString(); got != "�x" {
		t.Errorf("lone surrogate gave %q", got)
	}
}

func BenchmarkStrASCII(b *testing.B) {
	for i := 0; i < b.N; i++ {
		Str("the quick brown fox jumps over the lazy dog")
	}
}

func BenchmarkSplitOnChar(b *testing.B) {
	s := Str("a,bb,ccc,dddd,eeeee,ffffff,ggggggg,hhhhhhhh")
	sep := Str(",")
	for i := 0; i < b.N; i++ {
		StringSplitLimit(s, sep, 0)
	}
}
