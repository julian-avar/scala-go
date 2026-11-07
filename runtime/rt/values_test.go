package rt

import (
	"math"
	"strings"
	"testing"
)

func TestToStringMatchesJava(t *testing.T) {
	cases := []struct {
		in   any
		want string
	}{
		{nil, "null"},
		{int32(-42), "-42"},
		{int64(math.MaxInt64), "9223372036854775807"},
		{true, "true"},
		{uint16('A'), "A"},
		{1.0, "1.0"},
		{0.1, "0.1"},
		{-0.0 * -1 * -1, "0.0"},
		{math.Copysign(0, -1), "-0.0"},
		{1e7, "1.0E7"},
		{1.2345e-5, "1.2345E-5"},
		{0.001, "0.001"},
		{123456.789, "123456.789"},
		{math.Inf(-1), "-Infinity"},
		{math.NaN(), "NaN"},
		{float32(0.1), "0.1"},
		{float32(1e10), "1.0E10"},
		{1e23, "1.0E23"},
		{math.SmallestNonzeroFloat64, "4.9E-324"},
		{2 * math.SmallestNonzeroFloat64, "9.9E-324"},
		{float32(3 * math.SmallestNonzeroFloat32), "4.2E-45"},
		{float32(math.SmallestNonzeroFloat32), "1.4E-45"},
	}
	for _, c := range cases {
		if got := ToGoString(c.in); got != c.want {
			t.Errorf("ToString(%#v) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestEqualsIsCooperative(t *testing.T) {
	if !Equals(int32(1), int64(1)) || !Equals(int32(1), 1.0) || Equals(int32(1), int32(2)) {
		t.Error("numeric cooperative equality")
	}
	if ObjectEquals(int32(1), int64(1)) {
		t.Error("Integer.equals(Long) must be false")
	}
	if !Equals(nil, nil) || Equals(nil, Str("a")) || Equals(Str("a"), Str("b")) || !Equals(Str("a"), Str("a")) {
		t.Error("null and string equality")
	}
}

func TestHashCodeMatchesJava(t *testing.T) {
	if got := HashCode(Str("hello")); got != 99162322 {
		t.Errorf("hash of hello = %d", got)
	}
	if got := HashCode(int64(-1)); got != 0 {
		t.Errorf("hash of -1L = %d", got)
	}
}

func TestD2ISaturates(t *testing.T) {
	if D2I(math.NaN()) != 0 || D2I(1e20) != math.MaxInt32 || D2I(-1e20) != math.MinInt32 || D2I(-1.9) != -1 {
		t.Error("D2I")
	}
}

func TestStringsAreUTF16(t *testing.T) {
	s := Str("hé\U0001F600")
	if StringLength(s) != 4 {
		t.Errorf("length = %d, want 4 (surrogate pair counts as 2)", StringLength(s))
	}
	if StringCharAt(s, 2) != 0xd83d {
		t.Errorf("charAt(2) = %x", StringCharAt(s, 2))
	}
	if got := StringSubstringRange(s, 1, 2).GoString(); got != "é" {
		t.Errorf("substring = %q", got)
	}
	if StringCompareTo(Str("abc"), Str("abd")) >= 0 || StringIndexOf(Str("hello"), Str("ll"), 0) != 2 {
		t.Error("compareTo / indexOf")
	}
}

func TestRuntimeErrorsBecomeJavaExceptions(t *testing.T) {
	var arr []int32
	i := 3
	exc := Try(func() { _ = arr[i] })
	if got := ToGoString(exc); got != "java.lang.ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 0" {
		t.Errorf("got %q", got)
	}
	var s *String
	if got := ToGoString(Try(func() { StringLength(s) })); got != "java.lang.NullPointerException" {
		t.Errorf("got %q", got)
	}
}

func TestRegexSplitAndReplace(t *testing.T) {
	split := func(s, re string, limit int32) []string {
		var out []string
		for _, p := range StringSplitLimit(Str(s), Str(re), limit).Data {
			out = append(out, p.(*String).GoString())
		}
		return out
	}
	cases := []struct {
		s, re string
		limit int32
		want  string
	}{
		{"a b  c", `\s+`, 0, "a|b|c"},
		{" a b ", `\s+`, 0, "|a|b"},
		{"a,b,,", ",", 0, "a|b"},
		{"a,b,,", ",", -1, "a|b||"},
		{"a,b,c", ",", 2, "a|b,c"},
		{"abc", "", 0, "a|b|c"},
		{"x", ",", 0, "x"},
	}
	for _, c := range cases {
		if got := strings.Join(split(c.s, c.re, c.limit), "|"); got != c.want {
			t.Errorf("split(%q, %q, %d) = %q, want %q", c.s, c.re, c.limit, got, c.want)
		}
	}
	if !StringMatches(Str("abc123"), Str(`[a-z]+\d+`)) || StringMatches(Str("abc123x"), Str(`[a-z]+\d+`)) {
		t.Error("matches")
	}
	if got := StringReplaceAll(Str("a1b22"), Str(`(\d+)`), Str("<$1>")).GoString(); got != "a<1>b<22>" {
		t.Errorf("replaceAll: %q", got)
	}
	if got := StringReplaceFirst(Str("a1b22"), Str(`\d`), Str(`\$`)).GoString(); got != "a$b22" {
		t.Errorf("replaceFirst: %q", got)
	}
}

func BenchmarkClassOf(b *testing.B) {
	for i := 0; i < b.N; i++ {
		ClassOf("scala.collection.mutable.HashMap")
	}
}
