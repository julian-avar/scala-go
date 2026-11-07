package rt

import (
	"testing"
	"time"
)

func TestParkPermit(t *testing.T) {
	state := NewThreadState(&Object{})
	Unpark(state)
	Unpark(state) // permits do not accumulate
	start := time.Now()
	Park(state, -1) // takes the permit
	Park(state, int64(20*time.Millisecond))
	if time.Since(start) < 20*time.Millisecond {
		t.Fatal("second park did not wait for its timeout")
	}
}

func TestParkUnparkFromAnotherGoroutine(t *testing.T) {
	state := NewThreadState(&Object{})
	done := make(chan struct{})
	StartThread(state, true, func(any) {
		Park(state, -1)
		close(done)
	})
	time.Sleep(10 * time.Millisecond)
	Unpark(state)
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("not unparked")
	}
}

func TestParkInterrupt(t *testing.T) {
	state := NewThreadState(&Object{})
	done := make(chan bool)
	StartThread(state, true, func(any) {
		Park(state, -1)
		done <- ThreadIsInterrupted(state, false)
	})
	time.Sleep(10 * time.Millisecond)
	InterruptThread(state)
	select {
	case interrupted := <-done:
		if !interrupted {
			t.Fatal("interrupt status cleared by park")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("park not interrupted")
	}
	// An interrupted thread does not park.
	Park(state, -1)
}

func TestCurrentThreadCache(t *testing.T) {
	thread := &Object{}
	AttachThread(NewThreadState(thread))
	if CurrentThread() != thread {
		t.Fatal("attached thread not current")
	}
	other := make(chan any)
	go func() { other <- CurrentThread() }()
	if <-other != nil {
		t.Fatal("another goroutine sees this goroutine's thread")
	}
}

func TestVolatileReusesBoxes(t *testing.T) {
	var v Volatile[any]
	a, b := &Object{}, &Object{}
	v.Store(a)
	allocs := testing.AllocsPerRun(100, func() {
		v.Store(nil)
		v.Store(a)
	})
	if allocs != 0 {
		t.Fatalf("storing the last object again allocated (%v)", allocs)
	}
	if !v.CompareAndSet(a, b) || v.Load() != b || v.GetAndSet(nil) != b || v.Load() != nil {
		t.Fatal("wrong value")
	}
}

func TestVolatilePrimitives(t *testing.T) {
	var i VolatileInt32
	var l VolatileInt64
	var f VolatileFloat64
	if i.GetAndAdd(5) != 0 || i.GetAndAdd(-2) != 5 || i.Load() != 3 || !i.CompareAndSet(3, 7) || i.GetAndSet(1) != 7 {
		t.Fatal("VolatileInt32")
	}
	if l.GetAndAdd(1<<40) != 0 || l.Load() != 1<<40 {
		t.Fatal("VolatileInt64")
	}
	f.Store(1.5)
	if !f.CompareAndSet(1.5, 2.5) || f.Load() != 2.5 {
		t.Fatal("VolatileFloat64")
	}
}
