package rt

import (
	"sync"
	"testing"
)

func TestGoIDMatchesRuntimeStack(t *testing.T) {
	// On architectures with getg, a failure to find the offset means this Go version laid out g
	// differently: everything would still work, but slowly.
	if getg() != nil && goidOffset < 0 {
		t.Fatal("the goroutine id offset was not found: GoID takes the slow path")
	}
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if got, want := GoID(), slowGoID(); got != want {
				t.Errorf("GoID() = %d, runtime.Stack says %d", got, want)
			}
		}()
	}
	wg.Wait()
	if got, want := GoID(), slowGoID(); got != want {
		t.Errorf("GoID() = %d on the test goroutine, runtime.Stack says %d", got, want)
	}
}

func BenchmarkGoID(b *testing.B) {
	for i := 0; i < b.N; i++ {
		GoID()
	}
}

func BenchmarkSlowGoID(b *testing.B) {
	for i := 0; i < b.N; i++ {
		slowGoID()
	}
}

func BenchmarkSynchronized(b *testing.B) {
	x := &struct{ ObjectHeader }{}
	for i := 0; i < b.N; i++ {
		MonitorEnter(x)
		MonitorExit(x)
	}
}
