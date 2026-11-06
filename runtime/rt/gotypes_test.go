package rt

import (
	"reflect"
	"testing"
)

func TestCanonicalValues(t *testing.T) {
	ints := []int{1, 2}
	if GoSliceGet(ints, 1) != int64(2) {
		t.Fatal("int is Long")
	}
	GoSliceSet(ints, 0, int64(5))
	if ints[0] != 5 {
		t.Fatal("shared")
	}
	u := []uint32{4000000000}
	if GoSliceGet(u, 0) != int32(-294967296) {
		t.Fatalf("uint32 is Int bits: %v", GoSliceGet(u, 0))
	}
	type point struct{ X int }
	ps := []point{{1}}
	p := GoSliceGet(ps, 0).(*point)
	p.X = 9
	if ps[0].X != 1 {
		t.Fatal("struct values are copied out")
	}
	GoSliceSet(ps, 0, p)
	if ps[0].X != 9 {
		t.Fatal("struct values are copied in")
	}
	if Try(func() { GoSliceGet(ints, 2) }) == nil {
		t.Fatal("bounds")
	}
}

func TestMapsChansPtrs(t *testing.T) {
	m := GoMakeMap(GoTypeBasic(Str("string")), GoTypeBasic(Str("int")))
	GoMapStore(m, "a", int64(1))
	if GoMapLookup(m, "a", nil) != int64(1) || GoMapLookup(m, "b", "missing") != "missing" {
		t.Fatal("lookup")
	}
	if m.(map[string]int)["a"] != 1 {
		t.Fatal("a real Go map")
	}
	c := GoMakeChan(GoTypeBasic(Str("int32")), 1)
	if !GoChanTrySend(c, int32(3)) || GoChanTrySend(c, int32(4)) {
		t.Fatal("trySend")
	}
	if GoChanTryReceive(c, "none", "closed") != int32(3) || GoChanTryReceive(c, "none", "closed") != "none" {
		t.Fatal("tryReceive")
	}
	GoChanClose(c)
	if GoChanTryReceive(c, "none", "closed") != "closed" || GoChanReceive(c, "closed") != "closed" {
		t.Fatal("closed")
	}
	p := GoNewPtr(GoTypeBasic(Str("uint8")))
	GoPtrSet(p, int8(-1))
	if *p.(*uint8) != 255 || GoPtrGet(p) != int8(-1) {
		t.Fatal("pointers")
	}
	if !GoIsNil([]int(nil)) || GoIsNil([]int{}) || !GoIsNil(nil) {
		t.Fatal("nil")
	}
	ro := ConvertGo[<-chan int](make(chan int))
	if reflect.TypeOf(ro).ChanDir() != reflect.RecvDir {
		t.Fatal("ConvertGo")
	}
}
