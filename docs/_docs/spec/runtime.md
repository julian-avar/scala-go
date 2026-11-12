---
title: Runtime
---

# Runtime

A linked program is a Go module: generated code in `internal/scala`, the runtime in
`internal/rt`, and a `main` package that calls the entry point. Mechanisms are described in
[Design](../contributing/design.md#concurrency-and-the-memory-model).

## Program start and exit

| Event | Behavior |
|-------|----------|
| Start | Go package initialization (Go: [Package initialization][go-pkginit]) of `rt`, the packages facades import, then the generated package. It creates string literals and `classOf` constants and runs no Scala code; no class is initialized eagerly |
| Entry point | `main(Array[String])` of the configured object (`--main`, default `Main`), or the class generated for a `@main` method. `args` excludes the program name |
| `main` returns | Waits for every non-daemon `Thread` (Go alone would not; Go: [Program execution][go-exec]), runs the shutdown hooks, flushes standard output, exits with status 0. Daemon threads end with the process |
| Uncaught exception in `main` | Prints `Exception in thread "main" ` and the stack trace on standard error; then as above, with status 1 |
| Uncaught exception in another thread | Goes to the thread's handler, else the group's, else the default handler, else is printed as `Exception in thread "<name>" ...`. The program continues |
| `System.exit(n)`, `Runtime.exit(n)` | Runs the shutdown hooks (each in a new thread, concurrently, waiting for all), flushes standard output, exits with status `n`. Other threads stop |
| `Runtime.halt(n)` | Flushes standard output, exits with status `n`; no hooks |
| `exit` from a shutdown hook | Exits at once. **Deviation:** the JVM blocks forever |
| Go panic nothing recovers (Go: [Handling panics][go-panics]) | Go's panic report, exit status 2, no hooks. `Thread`s recover every panic; a Scala exception thrown into Go code can end here ([Errors and panics](interop.md#errors-and-panics)) |
| Go fatal error (stack or heap exhaustion, ...) | Go prints `fatal error: ...` and every goroutine's stack; exit status 2; no hooks |

- Exits with a status (`System.exit`, `halt`, uncaught exception in `main`) skip the pending
  `finally` blocks of all threads, as on the JVM (`os.Exit` runs no deferred functions).
- The main thread is a non-daemon `Thread` named `main` with priority 5; its `threadId` is
  usually 1 (JDK 25: 3).
- **Deviation:** buffered standard output is lost when the process ends by a signal
  ([Signals](#signals)) or a Go fatal error.
- **Deviation (bug):** an uncaught exception in `main` is always printed by the runtime; the main
  thread's handler and the default handler are not called for it. The main `Thread` stays alive
  after `main` returns or throws (`join()` on it never returns). An overridden
  `ThreadGroup.uncaughtException` is never called, and a default handler that throws ends the
  program with a Go panic. [Known bugs](../contributing/roadmap.md#known-bugs).

## Threads

Each `Thread` runs on its own goroutine (Go: [Go statements][go-go]), multiplexed by Go's
scheduler over `GOMAXPROCS` OS threads. Goroutines that Go code starts:
[Goroutines and threads](interop.md#goroutines-and-threads).

| API | Behavior |
|-----|----------|
| `start`, `run`, `join` (with timeouts), `isAlive`, names (`Thread-0`, ...), ids, daemon status, uncaught-exception handlers | As on the JVM |
| `getState` | **Deviation (bug):** `RUNNABLE` in `sleep`, `wait`, `join` and while blocked on a monitor; only `LockSupport` parking reports `WAITING`/`TIMED_WAITING` |
| `currentThread` | The goroutine's `Thread`. A goroutine not started as a `Thread` (the main goroutine, or one Go code started) gets one on first request, kept while it runs |
| priorities | Stored and reported; no effect on scheduling. **Deviation (bug):** a new thread starts at 5, not at its creator's priority capped by its group's maximum |
| `sleep`, `yield`, `onSpinWait` | `sleep` is interruptible; `yield` and `onSpinWait` are `runtime.Gosched` |
| `interrupt`, `isInterrupted`, `interrupted` | As on the JVM. Wakes `sleep`, `join`, `Object.wait`, `LockSupport.park` (and all built on it: locks, conditions, queues, futures), `Process.waitFor` and blocked `GoChan` operations. **Deviation:** does not interrupt blocking socket or file I/O |
| `Thread.ofPlatform()`, `ofVirtual()`, `startVirtualThread` | Both build goroutine-backed threads; virtual threads are daemons, unnamed by default, `isVirtual` |
| thread groups | Names and uncaught-exception handlers. **Deviation:** no `activeCount`, `enumerate` |
| `stop`, `suspend`, `resume`, `getAllStackTraces`, another thread's `getStackTrace` | Not implemented |
| `Runtime.availableProcessors` | `GOMAXPROCS` |

`ThreadLocal` and `InheritableThreadLocal` values are stored per `Thread` and live until
`remove` or the end of the thread; a new thread copies its creator's inheritable values through
`childValue`.

## Memory model

scala-go provides the Java Memory Model's (JMM) guarantees through the [Go memory model][go-mem]:
each JMM happens-before edge is made by a Go operation for which Go gives the same edge.

| JMM edge | Go operation and rule |
|----------|-----------------------|
| `synchronized`: a monitor exit before the next entry | a per-object reentrant monitor on a `sync.Mutex`, created on first use ([Locks][go-mem-locks]) |
| `wait`/`notify`/`notifyAll` (with timeouts and interrupts; `IllegalMonitorStateException` without the monitor) | channels under the monitor's mutex ([Locks][go-mem-locks], [Channels][go-mem-chan]) |
| `@volatile`, `java.util.concurrent.atomic`, `VarHandle`-style operations: one total order | `sync/atomic`; a reference is an atomic pointer to an immutable box. Go's atomics are sequentially consistent ([Atomics][go-mem-atomic]) |
| `Thread.start` before the thread's actions | a `go` statement ([Goroutine creation][go-mem-go]) |
| a thread's actions before `join` returns or `isAlive` is `false` | the ending thread sets a volatile state and calls `notifyAll` on its monitor; goroutine exit itself synchronizes with nothing ([Goroutine destruction][go-mem-goexit]) |
| `LockSupport.unpark` before the `park` it releases | a one-slot channel per thread ([Channels][go-mem-chan]) |
| class and object initialization before uses that find it complete | `rt.InitLock`: an atomic flag, a mutex, a channel closed when the initializer ends |
| `lazy val` | compare-and-set on a volatile state field |
| `final` fields | **Deviation:** plain Go fields; Go has no freeze rule, so objects must be published through one of the edges above |

Data-race-free programs are sequentially consistent under both models (Go:
[Overview][go-mem-overview]). `go build -race` on the generated module reports data races in
Scala code with Scala file names and lines.

For racy programs Go guarantees only that a read of a location no larger than a machine word
observes a preceding or concurrent write; races on multiword values can produce values no single
write wrote and corrupt memory (Go: [Restrictions][go-mem-races]). The JMM never tears a
reference. A racy read of a plain field is therefore:

| Field type | Go representation | Racy read |
|------------|-------------------|-----------|
| primitive types | one word (64-bit platforms) | a value some write stored, as on the JVM |
| `String`, arrays, `Class`, `BigInteger`, `BigDecimal` | a pointer | a reference some write stored, as on the JVM |
| other reference types (classes, traits, `Any`), including Go values held there | an interface: two words | **Deviation:** may pair one write's type with another's data, and crash or corrupt memory |

A `GoMap` used concurrently with a write is a fatal error ([Go data](interop.md#go-data)).

## Garbage collection and memory

| Aspect | Behavior |
|--------|----------|
| Collector | Go's concurrent, non-generational, non-moving mark-and-sweep collector, tuned with `GOGC` and `GOMEMLIMIT` |
| Object addresses | Stable; identity hash codes derive from them |
| `finalize()` | **Deviation:** never called |
| `WeakReference`, `SoftReference`, `WeakHashMap` | **Deviation:** hold referents (keys) strongly; `get` returns the referent until `clear()`. `ReferenceQueue` has no methods (`poll`, `remove`); `PhantomReference` is not implemented |
| Heap exhaustion | **Deviation:** a Go fatal error (`fatal error: runtime: out of memory`), not a catchable `OutOfMemoryError` |
| `Runtime.totalMemory`, `freeMemory` | The heap Go obtained from the system, and its unused part |
| `Runtime.maxMemory` | **Deviation:** `Long.MaxValue` unless `GOMEMLIMIT` is set (JVM: its `-Xmx`) |
| `Runtime.gc()` | `runtime.GC()`. **Deviation (bug):** `System.gc()` is a link error ([Known bugs](../contributing/roadmap.md#known-bugs)) |

## Stack

A thread's goroutine stack grows up to Go's limit (1 GB on 64-bit platforms), so recursion far
deeper than the JVM's default thread stacks (512 KB–1 MB) succeeds. The `stackSize` argument of
`Thread` constructors and builders is ignored.

**Deviation:** exhausting the stack is a Go fatal error (`fatal error: stack overflow`, exit
status 2), not a `StackOverflowError`: no `catch` or `finally` runs, and buffered standard output
is lost (`tests/cases/limits-runtime-stack-overflow`).

## Class and object initialization

Semantics (when, once, thread-safe, cycles, failure):
[Language §5](language.md#5-classes-and-objects) and, for `lazy val`,
[§4](language.md#4-basic-definitions). Each `object` and each class with a static initializer
has an `rt.InitLock`; after initialization an access costs one atomic load. Two threads
initializing objects in opposite orders deadlock, as on the JVM.

## Reflection and class loading

There is no class loading: the program is linked whole, and only reachable code exists. Code
that reaches an unimplemented reflective API fails to link, naming the chain of calls.

| API | Behavior |
|-----|----------|
| `getClass`, `classOf[T]` | One interned `Class` per JVM class name (`==` and `eq` work) |
| `Class.getName`, `getTypeName`, `toString`, `isArray`, `isPrimitive`, `getComponentType` | As on the JVM, from the class name |
| `Class.getSimpleName` | As on the JVM, except for local classes ([Language](language.md#5-classes-and-objects)) |
| `Class.isAssignableFrom` | Works for program classes, primitives, arrays and the JDK value classes |
| `Class.forName(name)` | **Deviation:** returns a `Class` with that name for any name; never throws `ClassNotFoundException`; does not initialize the class |
| `getClassLoader` | **Deviation:** `null`, also for program classes |
| `getResource`, `getResourceAsStream` | `null`: a binary has no class-path resources |
| `desiredAssertionStatus` | `false` |
| `java.lang.reflect.Array` | `newInstance`, `getLength`, `get`, `set`. **Deviation:** `newInstance` of a reference component type gives an `Object[]` ([Arrays](data.md#arrays)) |
| `ClassTag` | Works; `newArray` of a reference type gives an `Object[]` |
| `Class.isInstance`, `cast`; fields, methods, constructors, annotations, proxies, `MethodHandle`, `VarHandle`, `ServiceLoader`, atomic field updaters | Not implemented |

## Standard I/O

- `System.out` (and `Console.out`, `println`) writes UTF-8 into a 32 KB buffer, flushed at exit,
  when full, before anything is written to standard error, before standard input is read, around
  calls into Go code, and before a subprocess inherits standard output. **Deviation:** the JDK
  flushes every line; output interleaves differently with other writers to the same descriptor.
- `System.err` is unbuffered. `System.in` is a buffered stream over standard input.
- Each `print`/`println` call is written atomically: lines from different threads never
  interleave. **Deviation:** the print methods do not take the stream's monitor, so holding
  `System.out`'s lock does not block other threads' printing.
- Unpaired surrogates are written as `?`, as by the JDK's console streams.
- `System.setOut`, `setErr`, `setIn` and `Console.withOut` work.
- **Deviation:** writing to a closed pipe on standard output or error ends the process with
  `SIGPIPE` (JVM: sets the `PrintStream`'s error flag and continues).

## System properties and environment

| Property | Value |
|----------|-------|
| `line.separator` | `\n` (`\r\n` on Windows) |
| `file.separator`, `path.separator` | the platform's |
| `file.encoding`, `native.encoding`, `sun.jnu.encoding`, `stdout.encoding`, `stderr.encoding` | `UTF-8` |
| `java.io.tmpdir` | `/tmp` on Linux, Go's `os.TempDir()` elsewhere |
| `os.name` | `Linux`, `Mac OS X`, `Windows`, `FreeBSD`, else `GOOS` |
| `os.arch` | `GOARCH`, with `aarch64` for arm64 and `x86` for 386 |
| `user.dir`, `user.home`, `user.name` | the working directory, Go's `os.UserHomeDir`, `$USER`/`$LOGNAME`/`$USERNAME` |
| `java.vm.name`, `java.vendor` | **Deviation:** `scala-go` |
| `java.class.path`, `java.library.path` | **Deviation:** empty |
| `java.version`, `java.home`, `java.specification.version`, other JVM properties | **Deviation:** `null` |

`System.setProperty`, `clearProperty` and `getProperties` work on an in-memory `Properties`.
`System.getenv` reads the process environment; `ProcessBuilder.environment` is a copy.

## Time

- `System.currentTimeMillis` is the wall clock. `System.nanoTime` is monotonic (Go's monotonic
  clock, from program start). `Clock`/`Instant.now` have nanosecond precision.
- `java.time` is scala-java-time 2.9.0 with Go's tz database: the system's zoneinfo, or Go's
  embedded copy (`time/tzdata`), linked in when the program can reach a named zone.
- The default zone is found as the JDK does on Linux: `TZ`, `/etc/timezone`, `/etc/localtime`,
  else `GMT`.
- **Deviation:** no long zone names; zone names are tz abbreviations (`CEST`), or `GMT+03:00`
  where there is none. Texts and patterns are English (`en_US`) whatever the locale.
- **Deviation:** `ZoneRules.getTransitions` stops at 2037 and `getTransitionRules` is empty. In
  zones with negative DST (Europe/Dublin) the standard offset can differ from the JDK's.
  `ZoneId.getAvailableZoneIds` lists the system's zoneinfo (empty without one; every zone still
  loads).
- **Deviation:** the Japanese calendar and the deprecated calendar methods of `Date`, `Calendar`
  and `SimpleDateFormat` are not implemented. Parsing a zone name (`z`) shared by several zones
  picks one arbitrarily.

## Locales, text and charsets

- **Deviation:** there is no locale data. `Locale.getDefault()` is `en_US` regardless of the
  environment, and every locale formats numbers and dates and maps case as `Locale.ROOT`:
  `String.format(Locale.GERMANY, "%.1f", x)` uses `.`.
- `String.format`, `f"..."`, `formatted` and `printf` are `java.util.Formatter`, with the JDK's
  conversions, flags, argument indices and exceptions. `%t` takes `java.time` values, and `Long`
  and `Date` in the default time zone (there is no `Calendar`).
- Charsets: UTF-8 (default), ISO-8859-1 and US-ASCII, with the JDK's malformed-input handling;
  `Charset.isSupported` is `false` for others. **Deviation (bug):** using another throws
  `UnsupportedOperationException` (JVM: `UnsupportedEncodingException` or
  `UnsupportedCharsetException`).
- `Character` classification and case mapping use Go's Unicode tables (Unicode 16.0 with Go 1.27,
  as in JDK 25). **Deviation:** they follow the Go release, not the targeted JDK.
- Regular expressions run on Go's RE2: no lookaround, backreferences or possessive quantifiers.

## Stack traces

- A `Throwable` captures the call stack as Go program counters when constructed (unless
  `writableStackTrace` is false), symbolized on first use into `StackTraceElement`s with Scala
  class, method and file names and lines.
- `printStackTrace` prints the JVM's format, with `Caused by:`, `Suppressed:` and `... n more`.
- **Deviation:** frames of JDK methods implemented in Go are missing; javalib frames show
  javalib's Scala sources (`java.lang.Thread.run(Thread.scala:62)`); module initialization has no
  `<clinit>` frame; a frame's line is where its statement starts; a thread's trace may end with
  the frames that started it.
- `SCALAGO_TRACE=1` keeps every frame, with Go function names.

## Signals

scala-go installs no signal handlers; Go's defaults apply. There is no `sun.misc.Signal`.

| Signal | Behavior |
|--------|----------|
| `SIGINT`, `SIGTERM`, `SIGHUP` | The process ends by the signal (status 128 + signal in a shell). **Deviation:** shutdown hooks do not run. Verified with `SIGTERM`: both exit with 143; only the JVM runs the hook |
| `SIGQUIT` | Go prints every goroutine's stack and exits with status 2. **Deviation:** the JVM prints a thread dump and continues |
| `SIGPIPE` on standard output or error | The process ends ([Standard I/O](#standard-io)) |
| `SIGSEGV`, `SIGBUS`, `SIGFPE` from Go code | Go run-time panics, translated into Java exceptions where possible |

## Native interoperability

There is no JNI; `@native` methods have no implementation. Native code is reached through Go
facades, whose packages may use cgo. The generated code and the runtime are pure Go, so
`CGO_ENABLED=0` builds static binaries unless a facade's package needs cgo.

## Platform

- Any `GOOS`/`GOARCH` that Go supports and that is 64-bit and little-endian; cross-compiling is
  `GOOS=... GOARCH=... go build`. **Deviation:** big-endian platforms are not supported
  (`ByteOrder.nativeOrder()` is little-endian).
- File system paths are Unix paths.
- Floating-point results can depend on the architecture (fused multiply-add,
  [Language](language.md#numeric-operations)).

## Go implementation dependence

scala-go targets gc (`go build`); gccgo and TinyGo are not supported. Where the Go specification
leaves behavior to the implementation, scala-go avoids depending on it or relies on gc:

| Left open by Go | scala-go |
|-----------------|----------|
| out-of-range float-to-integer conversions ([Conversions][go-conversions]) | not used: checked before converting |
| whether floating-point division by zero panics ([Floating-point operators][go-floatops]) | relies on gc: no panic |
| fused multiply-add ([Floating-point operators][go-floatops]) | not prevented (a deviation, [Language](language.md#numeric-operations)) |
| order of variable, field and index reads relative to calls ([Order of evaluation][go-order]) | avoided where an operand needs statements; otherwise gc's order (a deviation, [Language](language.md#evaluation-order)) |
| values of run-time panics ([Run-time panics][go-rtpanics]) | gc's messages choose the Java exception (`integer divide by zero`, `nil pointer dereference`, `index out of range [i] with length n`, `makeslice: len out of range`) |
| size of `int`, `uint`, `uintptr` ([Numeric types][go-numeric]) | 64 bits (`GoInt` is a `Long`); only 64-bit platforms |
| whether objects move | gc's collector does not move them: identity hash codes are addresses |
| goroutine identity (not exposed) | gc's goroutine structure on amd64 and arm64, otherwise parsed from `runtime.Stack` |
| stack size | gc's growable stacks, up to 1 GB on 64-bit platforms |
| map iteration order ([For range][go-range]) | not relied on: `GoMap` iterates in Go's order; Scala and Java maps in the libraries' |

[go-conversions]: https://go.dev/ref/spec#Conversions
[go-exec]: https://go.dev/ref/spec#Program_execution
[go-floatops]: https://go.dev/ref/spec#Floating_point_operators
[go-go]: https://go.dev/ref/spec#Go_statements
[go-numeric]: https://go.dev/ref/spec#Numeric_types
[go-order]: https://go.dev/ref/spec#Order_of_evaluation
[go-panics]: https://go.dev/ref/spec#Handling_panics
[go-pkginit]: https://go.dev/ref/spec#Package_initialization
[go-range]: https://go.dev/ref/spec#For_range
[go-rtpanics]: https://go.dev/ref/spec#Run_time_panics
[go-mem]: https://go.dev/ref/mem
[go-mem-atomic]: https://go.dev/ref/mem#atomic
[go-mem-chan]: https://go.dev/ref/mem#chan
[go-mem-go]: https://go.dev/ref/mem#go
[go-mem-goexit]: https://go.dev/ref/mem#goexit
[go-mem-locks]: https://go.dev/ref/mem#locks
[go-mem-overview]: https://go.dev/ref/mem#overview
[go-mem-races]: https://go.dev/ref/mem#restrictions
