package rt

import (
	"errors"
	"net/url"
	"strings"
	"testing"
)

func TestReinterpretSharesBytes(t *testing.T) {
	a := &Array[int8]{Data: []int8{1, -1}}
	b := Reinterpret[int8, uint8](SliceOf(a))
	if b[1] != 255 {
		t.Fatalf("got %v", b)
	}
	b[0] = 200
	if a.Data[0] != -56 {
		t.Fatalf("not shared: %v", a.Data)
	}
	if Reinterpret[int8, uint8](SliceOf[int8](nil)) != nil {
		t.Fatal("null array should be a nil slice")
	}
	if got := ArrayOfSlice(Reinterpret[uint8, int8](nil)); got == nil || len(got.Data) != 0 {
		t.Fatal("nil slice should be an empty array")
	}
}

func TestConvertSlice(t *testing.T) {
	a := &Array[any]{Data: []any{Str("a"), Str("b")}}
	s := ConvertSlice(a, func(x any) string { return Cast[*String](x).GoString() })
	if strings.Join(s, ",") != "a,b" {
		t.Fatalf("got %v", s)
	}
	back := ConvertToArray(s, func(x string) any { return Str(x) })
	if !Equals(back.Data[1], Str("b")) {
		t.Fatalf("got %v", back.Data)
	}
}

func TestForeignValues(t *testing.T) {
	v := url.Values{"a": {"1"}}
	if !ObjectEquals(v, v) || ObjectEquals(v, url.Values{"a": {"1"}}) {
		t.Fatal("maps compare by identity")
	}
	if HashCode(v) != HashCode(v) {
		t.Fatal("unstable hash")
	}
	if !Equals(any("go"), any("go")) || HashCode(any("go")) != HashCode(any("go")) {
		t.Fatal("Go strings compare by content")
	}
	err := errors.New("boom")
	if ToGoString(err) != "boom" {
		t.Fatalf("got %q", ToGoString(err))
	}
	if GetClass(&strings.Builder{}).name != "*strings.Builder" {
		t.Fatalf("got %q", GetClass(&strings.Builder{}).name)
	}
	if ToGoAny(Str("x")) != "x" || ToGoAny((*String)(nil)) != (*String)(nil) {
		t.Fatal("ToGoAny converts Scala strings")
	}
}

func TestErrorsAndExceptions(t *testing.T) {
	err := errors.New("bad")
	thrown := Try(func() { Check(err) })
	if thrown == nil || !strings.Contains(ToGoString(thrown), "bad") {
		t.Fatalf("got %v", thrown)
	}
	if Try(func() { Check(nil) }) != nil {
		t.Fatal("nil error thrown")
	}
	result := func() (e error) {
		defer RecoverAsError(&e)
		ThrowKind(IllegalArgument, "nope")
		return nil
	}()
	if result == nil || !strings.Contains(result.Error(), "nope") {
		t.Fatalf("got %v", result)
	}
}

func TestFromPtrAndAddr(t *testing.T) {
	if FromPtr[strings.Builder](nil) != nil {
		t.Fatal("nil pointer should be null")
	}
	p := Addr(url.URL{Scheme: "https"}).(*url.URL)
	if Deref[url.URL](p).Scheme != "https" {
		t.Fatal("round trip")
	}
	if Try(func() { Deref[url.URL](nil) }) == nil {
		t.Fatal("null should throw")
	}
}
