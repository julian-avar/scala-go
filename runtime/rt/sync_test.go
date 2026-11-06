package rt

import (
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestGoID(t *testing.T) {
	main := GoID()
	other := make(chan uint64)
	go func() { other <- GoID() }()
	if g := <-other; g == main || g == 0 || main == 0 {
		t.Fatalf("ids %d and %d", main, g)
	}
}

func TestInitLockRunsOnceAndWaits(t *testing.T) {
	var l InitLock
	var runs atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			l.Run("C", func() {
				runs.Add(1)
				time.Sleep(10 * time.Millisecond)
			})
			if !l.Done() {
				t.Error("returned before initialization finished")
			}
		}()
	}
	wg.Wait()
	if runs.Load() != 1 {
		t.Fatalf("ran %d times", runs.Load())
	}
}

func TestInitLockRecursionAndFailure(t *testing.T) {
	var l InitLock
	depth := 0
	l.Run("C", func() {
		depth++
		l.Run("C", func() { depth++ }) // recursive: sees initialization in progress
	})
	if depth != 1 {
		t.Fatalf("depth %d", depth)
	}
	var bad InitLock
	if Try(func() { bad.Run("Bad", func() { ThrowKind(IllegalArgument, "boom") }) }) == nil {
		t.Fatal("first failure should propagate")
	}
	thrown := Try(func() { bad.Run("Bad", func() {}) })
	if thrown == nil || !strings.Contains(ToGoString(thrown), "NoClassDefFoundError: Could not initialize class Bad") {
		t.Fatalf("got %v", ToGoString(thrown))
	}
}

func TestVolatile(t *testing.T) {
	var v Volatile[any]
	if v.Load() != nil {
		t.Fatal("zero value")
	}
	a, b := &Object{}, &Object{}
	if !v.CompareAndSet(nil, a) || v.CompareAndSet(nil, b) || v.Load() != a {
		t.Fatal("CAS from nil")
	}
	if !v.CompareAndSet(a, b) || v.Load() != b {
		t.Fatal("CAS by identity")
	}
	var n Volatile[int32]
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				for {
					old := n.Load()
					if n.CompareAndSet(old, old+1) {
						break
					}
				}
			}
		}()
	}
	wg.Wait()
	if n.Load() != 5000 {
		t.Fatalf("got %d", n.Load())
	}
	var s Volatile[any]
	slice := []int{1}
	s.Store(slice)
	if !s.CompareAndSet(slice, nil) {
		t.Fatal("slices compare by identity")
	}
}

func TestMonitorExclusionAndReentrancy(t *testing.T) {
	lock := &Object{}
	counter := 0
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 100; j++ {
				Synchronized(lock, func() {
					Synchronized(lock, func() { counter++ })
				})
			}
		}()
	}
	wg.Wait()
	if counter != 2000 {
		t.Fatalf("got %d", counter)
	}
	if Try(func() { MonitorExit(lock) }) == nil {
		t.Fatal("exit without owning")
	}
	if Try(func() { ObjectNotify(lock) }) == nil {
		t.Fatal("notify without owning")
	}
	if MonitorOf(Str("a")) == MonitorOf(Str("a")) {
		t.Fatal("distinct strings have distinct monitors")
	}
	if MonitorOf(&Object{}) == MonitorOf(&Object{}) {
		t.Fatal("distinct objects have distinct monitors")
	}
}

func TestWaitNotify(t *testing.T) {
	lock := &Object{}
	ready := false
	done := make(chan struct{})
	go func() {
		Synchronized(lock, func() {
			for !ready {
				ObjectWait(lock, 0, 0)
			}
		})
		close(done)
	}()
	time.Sleep(10 * time.Millisecond)
	Synchronized(lock, func() {
		ready = true
		ObjectNotifyAll(lock)
	})
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("waiter not woken")
	}
	start := time.Now()
	Synchronized(lock, func() { ObjectWait(lock, 20, 0) })
	if time.Since(start) < 20*time.Millisecond {
		t.Fatal("timed wait returned early")
	}
}

func TestInterruptSleep(t *testing.T) {
	thread := &Object{}
	result := make(chan any)
	StartThread(thread, true, func(any) {
		result <- Try(func() { Sleep(5000, 0) })
	})
	time.Sleep(10 * time.Millisecond)
	InterruptThread(thread)
	select {
	case thrown := <-result:
		if !strings.Contains(ToGoString(thrown), "InterruptedException") {
			t.Fatalf("got %v", ToGoString(thrown))
		}
	case <-time.After(2 * time.Second):
		t.Fatal("sleep not interrupted")
	}
}
