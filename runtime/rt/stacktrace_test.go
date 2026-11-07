package rt

import (
	"reflect"
	"runtime"
	"strings"
	"testing"
)

func TestUnescape(t *testing.T) {
	for mangled, want := range map[string]string{
		"Main_S":                 "Main$",
		"scala_dcollection_dSeq": "scala.collection.Seq",
		"a_ub":                   "a_b",
		"_x003cclinit_x003e":     "<clinit>",
		"x_xd83d_xde00":          "x\U0001F600",
		"plain":                  "plain",
	} {
		if got := unescape(mangled); got != want {
			t.Errorf("unescape(%q) = %q, want %q", mangled, got, want)
		}
	}
}

func TestDemangle(t *testing.T) {
	type want struct {
		kind          frameKind
		class, method string
	}
	for name, w := range map[string]want{
		"Impl_Main_S__deep_pI_rI":  {implFrame, "Main$", "deep"},
		"Impl_Widget___init_pI_rV": {implFrame, "Widget", "<init>"},
		"Impl_Foo_SBar__Show_p_rV": {implFrame, "Foo$Bar", "Show"},
		"Impl_p_dQ__x_u1_pT_rV":    {implFrame, "p.Q", "x_1"},
		"Fwd_Main__main_pAT_rV":    {forwarderFrame, "Main", "main"},
	} {
		fn := demangle(name)
		if fn.kind != w.kind || fn.class.GoString() != w.class || fn.method.GoString() != w.method {
			t.Errorf("demangle(%q) = %v %v.%v, want %+v", name, fn.kind, fn.class, fn.method, w)
		}
	}
	for _, name := range []string{"New_Main_S___init_p_rV", "Mod_Main_S"} {
		if fn := demangle(name); fn.kind != allocFrame {
			t.Errorf("demangle(%q): kind %v, want allocFrame", name, fn.kind)
		}
	}
	if fn := demangle("Impl_Main_S_S_SLambda_S1__apply_p_rV"); !fn.hidden {
		t.Errorf("lambda class not hidden")
	}
	if f := fileName("/src/Main.scala"); f.GoString() != "Main.scala" {
		t.Errorf("fileName = %v", f)
	}
	// Without a line directive, the position is in the generated Go code: unknown.
	if f := fileName("/out/internal/scala/Main_S.go"); f != nil {
		t.Errorf("fileName of Go code = %v", f)
	}
}

//go:noinline
func captureAt(depth int) []uintptr {
	if depth == 0 {
		return CaptureStackTrace().(*StackTrace).pcs
	}
	return captureAt(depth - 1)
}

// Following frame pointers finds the same frames as runtime.Callers.
func TestCaptureStackTrace(t *testing.T) {
	functions := func(pcs []uintptr) []string {
		var names []string
		frames := runtime.CallersFrames(pcs)
		for more := true; more; {
			var f runtime.Frame
			f, more = frames.Next()
			// runtime.Callers leaves out wrappers (testing.(*T).Run.gowrap1, ...).
			if !strings.Contains(f.Function, "wrap") && !strings.HasSuffix(f.Function, ".CaptureStackTrace") {
				names = append(names, f.Function)
			}
		}
		return names
	}
	fast := functions(captureAt(5))
	preciseCaptures.Add(1)
	precise := functions(captureAt(5))
	preciseCaptures.Add(-1)
	if !reflect.DeepEqual(fast, precise) {
		t.Fatalf("frame pointers:\n%v\nruntime.Callers:\n%v", fast, precise)
	}
	if n := strings.Count(strings.Join(fast, " "), ".captureAt"); n != 6 {
		t.Fatalf("%d captureAt frames in %v", n, fast)
	}
}

func BenchmarkCaptureStackTrace(b *testing.B) {
	for i := 0; i < b.N; i++ {
		CaptureStackTrace()
	}
}
