package rt

import (
	"os"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

// java.lang.Thread on goroutines. The Thread class itself is Scala (javalib); this is its native
// side: which goroutine runs which Thread, interrupts, and waiting for non-daemon threads.

// threadState is the runtime side of a java.lang.Thread object.
type threadState struct {
	thread      any
	interrupted atomic.Bool
	wake        chan struct{} // signaled (without blocking) by interrupt
}

var (
	threadsMu   sync.Mutex
	byThread    = map[any]*threadState{}
	byGoroutine = map[uint64]*threadState{}

	liveMu    sync.Mutex
	liveCond  = sync.NewCond(&liveMu)
	liveCount int // running non-daemon threads

	mainGoroutine atomic.Uint64
)

func threadStateOf(thread any) *threadState {
	threadsMu.Lock()
	defer threadsMu.Unlock()
	t := byThread[thread]
	if t == nil {
		t = &threadState{thread: thread, wake: make(chan struct{}, 1)}
		byThread[thread] = t
	}
	return t
}

// currentThreadState is nil for goroutines that never asked for their Thread.
func currentThreadState(g uint64) *threadState {
	threadsMu.Lock()
	defer threadsMu.Unlock()
	return byGoroutine[g]
}

// block waits until done is closed or the timeout (if positive) expires. It returns true if the
// thread was interrupted instead, clearing its interrupt status.
func (t *threadState) block(done <-chan struct{}, timeout time.Duration) bool {
	var expired <-chan time.Time
	if timeout > 0 {
		timer := time.NewTimer(timeout)
		defer timer.Stop()
		expired = timer.C
	}
	var wake chan struct{}
	if t != nil {
		wake = t.wake
	}
	for {
		select {
		case <-done:
			return false
		case <-expired:
			return false
		case <-wake:
			// A stale wake-up (the status was cleared by Thread.interrupted()) is ignored.
			if t.interrupted.Swap(false) {
				return true
			}
		}
	}
}

// StartThread runs a started Thread's body, run(thread), on a new goroutine.
func StartThread(thread any, daemon bool, run func(thread any)) {
	t := threadStateOf(thread)
	if !daemon {
		liveMu.Lock()
		liveCount++
		liveMu.Unlock()
	}
	go func() {
		g := GoID()
		threadsMu.Lock()
		byGoroutine[g] = t
		threadsMu.Unlock()
		defer func() {
			threadsMu.Lock()
			delete(byGoroutine, g)
			delete(byThread, thread)
			threadsMu.Unlock()
			if !daemon {
				liveMu.Lock()
				liveCount--
				liveCond.Broadcast()
				liveMu.Unlock()
			}
		}()
		run(thread)
	}()
}

// CurrentThread returns the Thread object of the current goroutine, or nil if it has none yet
// (the main goroutine, or a goroutine started by Go code): Scala then creates and attaches one.
func CurrentThread() any {
	if t := currentThreadState(GoID()); t != nil {
		return t.thread
	}
	return nil
}

// AttachThread makes thread the current goroutine's Thread.
func AttachThread(thread any) {
	t := threadStateOf(thread)
	threadsMu.Lock()
	byGoroutine[GoID()] = t
	threadsMu.Unlock()
}

func IsMainGoroutine() bool { return GoID() == mainGoroutine.Load() }

func InterruptThread(thread any) {
	t := threadStateOf(thread)
	t.interrupted.Store(true)
	select {
	case t.wake <- struct{}{}:
	default:
	}
}

// ThreadIsInterrupted reports the interrupt status, clearing it if clear is set.
func ThreadIsInterrupted(thread any, clear bool) bool {
	t := threadStateOf(thread)
	if clear {
		return t.interrupted.Swap(false)
	}
	return t.interrupted.Load()
}

// Sleep implements Thread.sleep: interruptible.
func Sleep(millis int64, nanos int32) {
	if millis < 0 || nanos < 0 || nanos > 999999 {
		ThrowKind(IllegalArgument, "timeout value is negative or out of range")
	}
	t := currentThreadState(GoID())
	if t != nil && t.interrupted.Swap(false) {
		ThrowKind(Interrupted, "sleep interrupted")
	}
	d := time.Duration(millis)*time.Millisecond + time.Duration(nanos)
	if d == 0 {
		runtime.Gosched()
		return
	}
	if t.block(nil, d) {
		ThrowKind(Interrupted, "sleep interrupted")
	}
}

func Yield() { runtime.Gosched() }

// RunMain runs the program's entry point like the JVM's launcher: an uncaught exception is
// reported, the program waits for the non-daemon threads it started, runs the shutdown hooks,
// then exits (with status 1 after an uncaught exception in main).
func RunMain(body func()) {
	mainGoroutine.Store(GoID())
	status := 0
	func() {
		defer func() {
			if r := recover(); r != nil {
				FlushStdout()
				os.Stderr.WriteString("Exception in thread \"main\" " + ToGoString(Caught(r)) + "\n")
				status = 1
			}
		}()
		body()
	}()
	liveMu.Lock()
	for liveCount > 0 {
		liveCond.Wait()
	}
	liveMu.Unlock()
	runShutdownHooks()
	FlushStdout()
	if status != 0 {
		os.Exit(status)
	}
}
