package rt

import (
	"os"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

// java.lang.Thread on goroutines. The Thread class itself is Scala (javalib); this is its native
// side: which goroutine runs which Thread, interrupts, parking (LockSupport), waiting for
// non-daemon threads, and shutdown hooks.

// threadState is the native side of a java.lang.Thread object, which holds it (NewThreadState).
type threadState struct {
	thread      any
	goroutine   atomic.Uint64 // the goroutine running it, 0 before it runs
	interrupted atomic.Bool
	wake        chan struct{} // signaled (without blocking) by interrupt
	permit      chan struct{} // LockSupport's permit: full when available
	parkTimer   *time.Timer   // for timed parks, reused (only the thread itself parks)
}

var (
	// byGoroutine maps the ids of goroutines running a Thread to its state.
	byGoroutine sync.Map
	// currentCache is a direct-mapped cache in front of byGoroutine, indexed by goroutine id:
	// Thread.currentThread (so every lock and ThreadLocal access) is an atomic load and a
	// comparison. Goroutine ids are never reused, so an entry of a finished goroutine never
	// matches a live one; finished threads clear their entry so that it does not keep them alive.
	currentCache [4096]atomic.Pointer[threadState]

	liveMu    sync.Mutex
	liveCond  = sync.NewCond(&liveMu)
	liveCount int // running non-daemon threads

	running atomic.Int32 // running threads started as Threads (Thread.activeCount)

	mainGoroutine atomic.Uint64
)

// NewThreadState creates the native side of a new Thread object.
func NewThreadState(thread any) any {
	return &threadState{thread: thread, wake: make(chan struct{}, 1), permit: make(chan struct{}, 1)}
}

// currentThreadState is nil for goroutines that never asked for their Thread.
func currentThreadState(g uint64) *threadState {
	slot := &currentCache[g%uint64(len(currentCache))]
	if t := slot.Load(); t != nil && t.goroutine.Load() == g {
		return t
	}
	if t, ok := byGoroutine.Load(g); ok {
		state := t.(*threadState)
		slot.Store(state)
		return state
	}
	return nil
}

// bindThread makes t the state of goroutine g.
func bindThread(g uint64, t *threadState) {
	t.goroutine.Store(g)
	byGoroutine.Store(g, t)
	currentCache[g%uint64(len(currentCache))].Store(t)
}

func unbindThread(g uint64, t *threadState) {
	byGoroutine.Delete(g)
	currentCache[g%uint64(len(currentCache))].CompareAndSwap(t, nil)
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
func StartThread(state any, daemon bool, run func(thread any)) {
	t := state.(*threadState)
	if !daemon {
		liveMu.Lock()
		liveCount++
		liveMu.Unlock()
	}
	running.Add(1)
	go func() {
		g := GoID()
		bindThread(g, t)
		defer func() {
			unbindThread(g, t)
			running.Add(-1)
			if !daemon {
				liveMu.Lock()
				liveCount--
				liveCond.Broadcast()
				liveMu.Unlock()
			}
		}()
		run(t.thread)
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

// AttachThread makes the Thread whose state is given the current goroutine's Thread.
func AttachThread(state any) { bindThread(GoID(), state.(*threadState)) }

func IsMainGoroutine() bool { return GoID() == mainGoroutine.Load() }

// ActiveThreadCount estimates the number of live threads (Thread.activeCount): those started as
// Threads, plus the main thread.
func ActiveThreadCount() int32 { return running.Load() + 1 }

func InterruptThread(state any) {
	t := state.(*threadState)
	t.interrupted.Store(true)
	select {
	case t.wake <- struct{}{}:
	default:
	}
}

// ThreadIsInterrupted reports the interrupt status, clearing it if clear is set.
func ThreadIsInterrupted(state any, clear bool) bool {
	t := state.(*threadState)
	if clear {
		return t.interrupted.Swap(false)
	}
	return t.interrupted.Load()
}

// Park implements LockSupport.park for the current thread (whose state is given): it returns
// when the permit is available (consuming it), when the thread is interrupted (leaving the
// status set), or after nanos if nanos >= 0. Like the JVM's, it may also return spuriously.
func Park(state any, nanos int64) {
	t := state.(*threadState)
	select {
	case <-t.permit:
		return
	default:
	}
	if t.interrupted.Load() || nanos == 0 {
		return
	}
	var expired <-chan time.Time
	if nanos > 0 {
		if t.parkTimer == nil {
			t.parkTimer = time.NewTimer(time.Duration(nanos))
		} else {
			t.parkTimer.Reset(time.Duration(nanos))
		}
		defer stopTimer(t.parkTimer)
		expired = t.parkTimer.C
	}
	for {
		select {
		case <-t.permit:
			return
		case <-expired:
			return
		case <-t.wake:
			if t.interrupted.Load() {
				// The status stays set, so the next blocking operation must see the wake-up too.
				select {
				case t.wake <- struct{}{}:
				default:
				}
				return
			}
		}
	}
}

// stopTimer stops a timer for reuse, draining a value it sent but nobody received (needed before
// Go 1.23's timers, harmless after).
func stopTimer(timer *time.Timer) {
	if !timer.Stop() {
		select {
		case <-timer.C:
		default:
		}
	}
}

// Unpark makes the permit of the thread whose state is given available.
func Unpark(state any) {
	select {
	case state.(*threadState).permit <- struct{}{}:
	default:
	}
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
				ReportUncaught("main", Caught(r))
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
