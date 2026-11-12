package rt

import (
	"reflect"
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

// ---- Initialization -----------------------------------------------------------------------

// InitLock guards a static initializer or a module's construction.
type InitLock struct {
	done    atomic.Bool
	mu      sync.Mutex
	owner   uint64        // goroutine running the initializer, 0 if none
	failed  bool          // the initializer threw: later uses fail, as on the JVM
	failure any           // what InitializerRecorded made of the exception
	running chan struct{} // closed when the running initializer finishes
}

// The exceptions of failed initializers, as the JVM makes them: functions the generated program
// installs (javalib's scalago.runtime.Initializers). The use that runs an initializer that
// throws a Throwable throws InitializerThrown's result (an ExceptionInInitializerError unless the
// Throwable is an Error); later uses throw InitializerNoClassDefFound's, whose cause is what
// InitializerRecorded made of the Throwable.
var (
	InitializerThrown          func(throwable any) any
	InitializerRecorded        func(throwable any) any
	InitializerNoClassDefFound func(className, recorded any) any
)

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
			failure := l.failure
			l.mu.Unlock()
			if failure != nil && InitializerNoClassDefFound != nil {
				panic(Exc(InitializerNoClassDefFound(Str(className), failure)))
			}
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
		var thrown, failure any
		if !ok {
			if r := recover(); r != nil {
				thrown = Caught(r)
				// Before releasing the lock: other threads must find the failure.
				if InitializerRecorded != nil {
					failure = InitializerRecorded(thrown)
				}
				if InitializerThrown != nil {
					thrown = InitializerThrown(thrown)
				}
			}
		}
		l.mu.Lock()
		l.owner = 0
		if ok {
			l.done.Store(true)
		} else {
			l.failed = true
			l.failure = failure
		}
		close(l.running)
		l.mu.Unlock()
		if thrown != nil {
			panic(Exc(thrown))
		}
	}()
	init()
	ok = true
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
	if m.owner != 0 && m.owner != g {
		t := currentThreadState(g)
		t.setBlockedIn(blockedOnMonitor)
		for m.owner != 0 && m.owner != g {
			m.free.Wait()
		}
		t.setBlockedIn(notBlocked)
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
	checkTimeout(millis, nanos)
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

// MonitorHeldByCurrent reports whether the current goroutine holds x's monitor (Thread.holdsLock).
func MonitorHeldByCurrent(x any) bool {
	m := MonitorOf(x)
	g := GoID()
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.owner == g
}
