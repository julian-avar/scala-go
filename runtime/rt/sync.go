package rt

import (
	"reflect"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
)

// Concurrency support: the parts of the Java memory model that generated code relies on.
//
//   - Class and module initialization (InitLock): runs once; other goroutines wait for it, and the
//     initializing goroutine re-entering sees the partially initialized state, as on the JVM.
//   - @volatile fields (Volatile): sequentially consistent loads, stores and compare-and-set.
//   - Monitors (synchronized, wait, notify): reentrant, one per object, created on first use.
//
// Ordinary fields are plain Go memory. A data race on them is a bug, as on the JVM, but its
// consequences are Go's: a racy read of a reference can see a torn value. See docs/design.md.

// GoID returns the current goroutine's id. Go does not expose it, so it is parsed from the
// stack header ("goroutine 18 [running]:"); this costs about a microsecond, so it is only used
// where ownership matters (monitors, initialization in progress).
func GoID() uint64 {
	var buf [32]byte
	n := runtime.Stack(buf[:], false)
	var id uint64
	for _, c := range buf[len("goroutine "):n] {
		if c < '0' || c > '9' {
			break
		}
		id = id*10 + uint64(c-'0')
	}
	return id
}

// ---- Initialization -----------------------------------------------------------------------

// InitLock guards a static initializer or a module's construction.
type InitLock struct {
	done    atomic.Bool
	mu      sync.Mutex
	owner   uint64        // goroutine running the initializer, 0 if none
	failed  bool          // the initializer threw: later uses fail, as on the JVM
	running chan struct{} // closed when the running initializer finishes
}

// Done reports whether initialization has completed (the fast path, inlined at call sites).
func (l *InitLock) Done() bool { return l.done.Load() }

// Run runs init unless it has already completed or is in progress in the current goroutine
// (recursive initialization). If another goroutine is running it, Run waits for it. If it threw,
// Run throws NoClassDefFoundError.
func (l *InitLock) Run(className string, init func()) {
	g := GoID()
	l.mu.Lock()
	for {
		switch {
		case l.done.Load():
			l.mu.Unlock()
			return
		case l.failed:
			l.mu.Unlock()
			ThrowKind(NoClassDefFound, "Could not initialize class "+className)
		case l.owner == g:
			l.mu.Unlock()
			return
		case l.owner == 0:
			l.owner = g
			l.running = make(chan struct{})
			l.mu.Unlock()
			l.run(init)
			return
		}
		running := l.running
		l.mu.Unlock()
		<-running
		l.mu.Lock()
	}
}

func (l *InitLock) run(init func()) {
	ok := false
	defer func() {
		l.mu.Lock()
		l.owner = 0
		if ok {
			l.done.Store(true)
		} else {
			l.failed = true
		}
		close(l.running)
		l.mu.Unlock()
	}()
	init()
	ok = true
}

// ---- Volatile fields ----------------------------------------------------------------------

// Volatile is a @volatile field. Each store allocates; volatile fields are for flags and lazy
// val states, not for bulk data.
type Volatile[T any] struct {
	p atomic.Pointer[T]
}

func (v *Volatile[T]) Load() T {
	if p := v.p.Load(); p != nil {
		return *p
	}
	var zero T
	return zero
}

func (v *Volatile[T]) Store(x T) { v.p.Store(&x) }

// CompareAndSet replaces the value with update if it is expected (reference identity for
// objects, value equality for primitives).
func (v *Volatile[T]) CompareAndSet(expected, update T) bool {
	for {
		old := v.p.Load()
		var current T
		if old != nil {
			current = *old
		}
		if !sameValue(current, expected) {
			return false
		}
		if v.p.CompareAndSwap(old, &update) {
			return true
		}
	}
}

// GetAndSet stores x and returns the previous value.
func (v *Volatile[T]) GetAndSet(x T) T {
	old := v.p.Swap(&x)
	if old == nil {
		var zero T
		return zero
	}
	return *old
}

func sameValue[T any](a, b T) bool {
	x, y := any(a), any(b)
	if x == nil || y == nil {
		return x == y
	}
	if t := reflect.TypeOf(x); t != reflect.TypeOf(y) {
		return false
	} else if !t.Comparable() {
		return sameRef(x, y)
	}
	return x == y
}

// sameRef is identity for Go values that == cannot compare (slices, maps, funcs).
func sameRef(x, y any) bool {
	vx, vy := reflect.ValueOf(x), reflect.ValueOf(y)
	switch vx.Kind() {
	case reflect.Slice, reflect.Map, reflect.Func, reflect.Pointer, reflect.Chan, reflect.UnsafePointer:
		return vx.Pointer() == vy.Pointer()
	}
	return false
}

// ---- Monitors -----------------------------------------------------------------------------

// ObjectHeader is embedded in every Scala object: it holds the object's monitor, created on first
// use. It also keeps objects from being zero-sized, so distinct objects have distinct addresses.
type ObjectHeader struct {
	monitor atomic.Pointer[Monitor]
}

func (h *ObjectHeader) objectHeader() *ObjectHeader { return h }

type hasObjectHeader interface{ objectHeader() *ObjectHeader }

// Monitors of values without a header (strings, boxes, Go values). Entries are never removed:
// locking such values is rare, and the JVM discourages it.
var foreignMonitors sync.Map

// MonitorOf returns the monitor of x, creating it if needed. A null x throws.
func MonitorOf(x any) *Monitor {
	if x == nil {
		ThrowKind(NullPointer, "")
	}
	if h, ok := x.(hasObjectHeader); ok {
		header := h.objectHeader()
		if m := header.monitor.Load(); m != nil {
			return m
		}
		header.monitor.CompareAndSwap(nil, newMonitor())
		return header.monitor.Load()
	}
	key := x
	if !reflect.TypeOf(x).Comparable() {
		key = reflect.ValueOf(x).Pointer()
	}
	m, _ := foreignMonitors.LoadOrStore(key, newMonitor())
	return m.(*Monitor)
}

// Monitor is a Java object monitor: a reentrant lock with a wait set.
type Monitor struct {
	mu      sync.Mutex
	free    sync.Cond // signaled when the monitor is released
	owner   uint64
	count   int
	waiters []chan struct{}
}

func newMonitor() *Monitor {
	m := &Monitor{}
	m.free.L = &m.mu
	return m
}

func (m *Monitor) acquire(g uint64, count int) {
	for m.owner != 0 && m.owner != g {
		m.free.Wait()
	}
	m.owner = g
	m.count += count
}

func (m *Monitor) release() {
	m.owner = 0
	m.count = 0
	m.free.Signal()
}

func (m *Monitor) checkOwner(g uint64) {
	if m.owner != g {
		m.mu.Unlock()
		ThrowKind(IllegalMonitorState, "current thread is not owner")
	}
}

// MonitorEnter starts `x.synchronized { ... }`.
func MonitorEnter(x any) {
	m := MonitorOf(x)
	g := GoID()
	m.mu.Lock()
	m.acquire(g, 1)
	m.mu.Unlock()
}

// MonitorExit ends `x.synchronized { ... }`.
func MonitorExit(x any) {
	m := MonitorOf(x)
	m.mu.Lock()
	m.checkOwner(GoID())
	m.count--
	if m.count == 0 {
		m.release()
	}
	m.mu.Unlock()
}

// Synchronized runs body holding x's monitor (for runtime code; generated code uses
// MonitorEnter and MonitorExit around try/finally).
func Synchronized(x any, body func()) {
	MonitorEnter(x)
	defer MonitorExit(x)
	body()
}

// ObjectWait implements Object.wait(timeoutMillis, nanos); zero means no timeout.
func ObjectWait(x any, millis int64, nanos int32) {
	if millis < 0 || nanos < 0 || nanos > 999999 {
		ThrowKind(IllegalArgument, "timeout value is negative or out of range")
	}
	m := MonitorOf(x)
	m.wait(&m.waiters, time.Duration(millis)*time.Millisecond+time.Duration(nanos))
}

// wait releases the monitor (held by the current goroutine), waits on the wait set `list`
// until notified, interrupted or the timeout (if positive) expires, and reacquires the
// monitor. It reports whether it was notified; an interrupt throws InterruptedException.
func (m *Monitor) wait(list *[]chan struct{}, timeout time.Duration) bool {
	g := GoID()
	t := currentThreadState(g)
	m.mu.Lock()
	m.checkOwner(g)
	if t != nil && t.interrupted.Swap(false) {
		m.mu.Unlock()
		ThrowKind(Interrupted, "")
	}
	ch := make(chan struct{})
	*list = append(*list, ch)
	saved := m.count
	m.release()
	m.mu.Unlock()

	interrupted := t.block(ch, timeout)

	m.mu.Lock()
	notified := true
	for i, w := range *list {
		if w == ch {
			*list = append((*list)[:i], (*list)[i+1:]...)
			notified = false
			break
		}
	}
	m.acquire(g, saved)
	m.mu.Unlock()
	if interrupted {
		ThrowKind(Interrupted, "")
	}
	return notified
}

// ObjectNotify implements Object.notify.
func ObjectNotify(x any) {
	m := MonitorOf(x)
	m.mu.Lock()
	m.checkOwner(GoID())
	if len(m.waiters) > 0 {
		close(m.waiters[0])
		m.waiters = m.waiters[1:]
	}
	m.mu.Unlock()
}

// ObjectNotifyAll implements Object.notifyAll.
func ObjectNotifyAll(x any) {
	m := MonitorOf(x)
	m.mu.Lock()
	m.checkOwner(GoID())
	for _, w := range m.waiters {
		close(w)
	}
	m.waiters = nil
	m.mu.Unlock()
}

// ---- java.util.concurrent.locks ----------------------------------------------------------

// ReentrantLock and its Conditions use a monitor (of a private object of the lock).

// MonitorTryEnter acquires x's monitor if that does not block.
func MonitorTryEnter(x any) bool {
	m := MonitorOf(x)
	g := GoID()
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.owner != 0 && m.owner != g {
		return false
	}
	m.owner = g
	m.count++
	return true
}

func MonitorHeldByCurrent(x any) bool {
	m := MonitorOf(x)
	g := GoID()
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.owner == g
}

func MonitorIsLocked(x any) bool {
	m := MonitorOf(x)
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.owner != 0
}

// Condition is a wait set of a monitor, separate from the monitor's own (Lock.newCondition).
type Condition struct {
	m       *Monitor
	waiters []chan struct{}
}

func NewCondition(lock any) any { return &Condition{m: MonitorOf(lock)} }

// ConditionAwait waits until signaled or interrupted (which throws), or until nanos have elapsed
// if nanos >= 0. It returns an estimate of the remaining nanoseconds (<= 0 on timeout).
func ConditionAwait(c any, nanos int64) int64 {
	cond := Cast[*Condition](c)
	if nanos == 0 {
		return 0
	}
	start := time.Now()
	timeout := time.Duration(nanos)
	if nanos < 0 {
		timeout = 0
	}
	if cond.m.wait(&cond.waiters, timeout) {
		if nanos < 0 {
			return 1
		}
		return max(nanos-int64(time.Since(start)), 1)
	}
	return nanos - int64(time.Since(start))
}

func ConditionSignal(c any) {
	cond := Cast[*Condition](c)
	cond.m.mu.Lock()
	cond.m.checkOwner(GoID())
	if len(cond.waiters) > 0 {
		close(cond.waiters[0])
		cond.waiters = cond.waiters[1:]
	}
	cond.m.mu.Unlock()
}

func ConditionSignalAll(c any) {
	cond := Cast[*Condition](c)
	cond.m.mu.Lock()
	cond.m.checkOwner(GoID())
	for _, w := range cond.waiters {
		close(w)
	}
	cond.waiters = nil
	cond.m.mu.Unlock()
}
