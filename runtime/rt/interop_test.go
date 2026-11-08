package rt

import (
	"errors"
	"fmt"
	"image"
	"net/url"
	"reflect"
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

// Go structs held by Scala code: image.Point has only value-receiver methods (like bubbletea's
// KeyMsg), strings.Builder has pointer-receiver ones (like bytes.Buffer). Types of this package
// count as Scala's and are never passed by value.
func TestGoStructsGoToInterfacesAsGoPassesThem(t *testing.T) {
	v := &image.Point{1, 2}
	if got, ok := ToGoAny(v).(image.Point); !ok || got.X != 1 {
		t.Errorf("a struct without pointer methods should go to any by value, got %T", ToGoAny(v))
	}
	p := &strings.Builder{}
	if got := ToGoAny(p); got != any(p) {
		t.Errorf("a struct with pointer methods should stay a pointer, got %T", got)
	}
	if got := ToGoInterface[fmt.Stringer](v); got != (image.Point{1, 2}) {
		t.Errorf("a struct whose value implements the interface should go by value, got %T", got)
	}
	if got := ToGoInterface[fmt.Stringer](p); got != fmt.Stringer(p) {
		t.Errorf("a pointer implementing the interface should stay a pointer, got %T", got)
	}
	if ToGoInterface[fmt.Stringer](nil) != nil || ToGoAny(nil) != nil {
		t.Error("null should become nil")
	}
	if got := ToGoAny(Str("s")); got != "s" {
		t.Errorf("Scala strings should become Go strings, got %#v", got)
	}
	var nilPtr *image.Point
	if got := ToGoAny(nilPtr); got != any(nilPtr) {
		t.Errorf("a nil pointer should stay as it is, got %#v", got)
	}
	if got := ToGoAny(&Array[int32]{}); reflect.TypeOf(got) != reflect.TypeFor[*Array[int32]]() {
		t.Errorf("runtime values should stay as they are, got %T", got)
	}
}

func TestStructFacadesAcceptValuesAndPointers(t *testing.T) {
	var msg any = image.Point{3, 4}
	if !IsStruct[image.Point](msg) || !IsStruct[image.Point](&image.Point{}) || IsStruct[image.Point]("x") {
		t.Error("IsStruct should accept T and *T only")
	}
	p, ok := CastStruct[image.Point](msg).(*image.Point)
	if !ok || p.X != 3 {
		t.Errorf("CastStruct should copy a value to a pointer, got %#v", CastStruct[image.Point](msg))
	}
	same := &image.Point{5, 6}
	if CastStruct[image.Point](same) != any(same) || CastStruct[image.Point](nil) != nil {
		t.Error("CastStruct should pass pointers and null as they are")
	}
}
