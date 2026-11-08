---
title: Runtime
---

# Runtime

A linked program is a Go module: generated code in `internal/scala`, the runtime in
`internal/rt`, and a `main` package that calls the entry point. This page specifies how that
program executes, compared with the same program on the JVM. Differences are marked
**Deviation:**.

## Program start and exit

| Event | Behavior |
|-------|----------|
| Start | The Go binary starts; no class is initialized eagerly. String literals and `classOf` constants are created by Go package initialization |
| Entry point | `main(Array[String])` of the configured object (`--main`, default `Main`), or the class the compiler generates for a `@main` method. `args` are the command-line arguments without the program name |
| `main` returns | The program waits for every non-daemon `Thread` it started, runs the shutdown hooks, flushes standard output and exits with status 0 |
| Uncaught exception in `main` | Printed as `Exception in thread "main" ` followed by the stack trace on standard error; then as above, with exit status 1 |
| Uncaught exception in another thread | Passed to the thread's handler, else the default handler, else printed as `Exception in thread "<name>" ...`; the program continues |
| `System.exit(n)`, `Runtime.exit(n)` | Runs the shutdown hooks (concurrently, each in a new thread, waiting for all), flushes standard output and exits with status `n`. Other threads are stopped |
| `Runtime.halt(n)` | Flushes standard output and exits with status `n`, without hooks |
| `exit` called from a shutdown hook | Exits at once. **Deviation:** the JVM blocks forever |
| Go fatal error (stack or heap exhaustion, ...) | Go prints `fatal error: ...` and every goroutine's stack, and exits with status 2; no hooks run |
| `SIGINT`, `SIGTERM`, `SIGHUP` | The process ends with the signal (status 128 + signal number in a shell) |

In Go terms (Go: [Program initialization and execution][go-init]):

- Package initialization runs first: the runtime package `rt` and the Go packages that facades
  import, then the generated package, whose package-level variables (string literals, `classOf`
  constants) are initialized in dependency order and whose `init` functions install the hooks
  the runtime needs (Go: [Package initialization][go-pkginit]). Scala code does not run during
  package initialization.
- `main` calls `rt.RunMain`, which runs the entry point, recovering an uncaught exception. When
  Go's `main` returns, the program exits without waiting for other goroutines (Go:
  [Program execution][go-exec]), so `rt.RunMain` first waits for the non-daemon threads
  (a counter under a `sync.Mutex` and `sync.Cond`), then runs the shutdown hooks and flushes
  standard output. Daemon threads end with the process, as on the JVM.
- `System.exit`, `Runtime.halt` and an uncaught exception in `main` end the process with
  `os.Exit`, which does not run deferred functions: pending `finally` blocks of any thread do not
  run, as on the JVM.
- A Go panic that no deferred function recovers ends the program with Go's report and exit
  status 2 (Go: [Handling panics][go-panics]). Threads started by `Thread.start` recover
  everything; a Scala exception thrown into Go code that does not recover it (a callback called
  on a goroutine that Go code started, without an `error` result) ends the program this way (see
  [Go interoperability](interop.md#errors-and-panics)).

- **Deviation (bug):** an uncaught exception in the main thread is always printed by the
  runtime: `Thread.setDefaultUncaughtExceptionHandler` and the main thread's own handler are not
  called for it. Other threads use them as on the JVM.
- **Deviation:** shutdown hooks do not run when the process is ended by a signal (the JVM runs
  them on `SIGINT`, `SIGTERM` and `SIGHUP`). Verified with `SIGTERM`: both exit with status 143,
  only the JVM runs the hook.
- **Deviation:** standard output is buffered (see [Standard I/O](#standard-io)); what is still
  in the buffer is lost when the process ends by a signal or a Go fatal error.
- The main thread is a non-daemon `Thread` named `main` with priority 5. Its `threadId` is
  usually 1 (JDK 25: 3).

## Threads

`java.lang.Thread` is javalib Scala code over goroutines (`rt/thread.go`). Every `Thread` runs
on its own goroutine, started by a `go` statement (Go: [Go statements][go-go]); Go's scheduler
multiplexes all of them over `GOMAXPROCS` OS threads. A goroutine has no identity, name or
result in Go, so the `Thread` object and the goroutine are associated by the runtime.

| API | Behavior |
|-----|----------|
| `start`, `run`, `join` (with timeouts), `isAlive`, `getState` | As on the JVM. `getState` reports `WAITING`/`TIMED_WAITING` for parked threads |
| `currentThread` | The `Thread` of the current goroutine. A goroutine not started as a `Thread` (the main goroutine, or one started by Go code) gets a `Thread` object the first time it asks, kept while the goroutine runs |
| names, ids, `setDaemon`, `isDaemon` | As on the JVM; default names `Thread-0`, `Thread-1`, ... |
| priorities | Stored and reported; no effect on scheduling |
| `sleep`, `yield`, `onSpinWait` | `time` timers and `runtime.Gosched`; `sleep` is interruptible |
| `interrupt`, `isInterrupted`, `interrupted` | As on the JVM. An interrupt wakes `sleep`, `join`, `Object.wait`, `LockSupport.park` (and everything built on it: locks, conditions, queues, futures), `Process.waitFor` and blocked `GoChan` operations. **Deviation:** it does not interrupt blocking socket or file I/O |
| `Thread.ofPlatform()`, `ofVirtual()`, `startVirtualThread` | Both build goroutine-backed threads; virtual threads are daemons, unnamed by default, `isVirtual` |
| thread groups | Names and uncaught-exception handlers. **Deviation:** `activeCount`, `enumerate` are missing |
| uncaught-exception handlers | Per thread, per group and default, as on the JVM (except in the main thread, see above) |
| `stop`, `suspend`, `resume`, `getAllStackTraces`, `getStackTrace` of another thread | Not implemented |
| `Runtime.availableProcessors` | `GOMAXPROCS` |

`ThreadLocal` and `InheritableThreadLocal` are stored per `Thread`, as in the JDK; a new thread
copies its creator's inheritable values (through `childValue`). Values live until `remove` or
the end of the thread.

Go code may call Scala code from any goroutine (exports, callbacks); such goroutines are
threads as described above.

## Memory model

Scala programs rely on the Java Memory Model (JMM). scala-go provides its guarantees through the
[Go memory model][go-mem], which defines happens-before through synchronization in the same
way. Each JMM edge is made by a Go operation for which the Go memory model gives the same edge:

| JMM construct | scala-go | Go memory model rule |
|---------------|----------|----------------------|
| `synchronized`: a monitor exit happens-before the next entry | per-object reentrant monitor: a `sync.Mutex`, a `sync.Cond`, an owner and a count, created on first use | the *n*th `Unlock` of a `sync.Mutex` is synchronized before the *m*th `Lock` returns, for *n* < *m* ([Locks][go-mem-locks]) |
| `wait`/`notify`/`notifyAll` | a wait set of channels, under the monitor's mutex; timeouts and interrupts | the lock rule, and a send or close is synchronized before the receive it completes ([Channel communication][go-mem-chan]). `IllegalMonitorStateException` without the monitor |
| `@volatile` reads, writes, compare-and-set | `sync/atomic` values: `rt.VolatileInt32`, ..., and `rt.Volatile[T]`, an atomic pointer to an immutable box, for references | all atomic operations behave as if executed in one sequentially consistent order, and one whose effect another observes is synchronized before it ([Atomic values][go-mem-atomic]): the JMM's synchronization order of volatiles |
| `java.util.concurrent.atomic`, javalib's `VarHandle`-style operations | `sync/atomic` operations on volatile fields and arrays | as above |
| `Thread.start` happens-before the thread's actions | a `go` statement | the `go` statement is synchronized before the goroutine starts ([Goroutine creation][go-mem-go]) |
| a thread's actions happen-before `join` returns or `isAlive` returns `false` | the ending thread sets a volatile state and calls `notifyAll` under the thread's monitor; `join` waits on that monitor | the end of a goroutine is synchronized before nothing ([Goroutine destruction][go-mem-goexit]); the monitor and the volatile carry the edge |
| `LockSupport.unpark` happens-before the `park` it releases | a one-slot channel per thread | [Channel communication][go-mem-chan] |
| class and object initialization happens-before the uses that find it complete | `rt.InitLock`: an atomic flag, a mutex, and a channel closed when the initializer ends | [Atomic values][go-mem-atomic], [Locks][go-mem-locks], [Channel communication][go-mem-chan] |
| `lazy val` | compare-and-set on a volatile state field | [Atomic values][go-mem-atomic] |
| `final` fields | plain Go fields | **Deviation:** Go has no freeze rule; objects must be published through one of the edges above |
| data races on plain fields | plain Go memory | see below |

Programs that are data-race-free behave as on the JVM: both models make them sequentially
consistent (Go: [Overview][go-mem-overview]). Go's race detector (`go build -race` on the
generated module) reports data races in Scala code with Scala file names and lines.

For programs with data races, Go guarantees only that a read of a memory location no larger
than a machine word observes a preceding or concurrent write to it; races on multiword values
can produce values that no single write wrote and, for interfaces, slices and strings, memory
corruption (Go: [Implementation restrictions for programs containing data races][go-mem-races]).
The JMM never tears a reference. A racy read of a plain field is therefore:

| Field type | Go representation | Racy read |
|------------|-------------------|-----------|
| primitive types | one word on 64-bit platforms | a value some write stored, as on the JVM |
| `String`, arrays, `Class`, `BigInteger`, `BigDecimal` | a pointer: one word | a reference some write stored, as on the JVM |
| other reference types: classes, traits, `Any` | an interface: two words (type, data) | **Deviation:** may pair the type of one write with the data of another, and crash the program or corrupt memory |
| `GoString`, `GoSlice` and Go interface values held in such fields | inside the interface above | **Deviation:** as above |

A `GoMap` written concurrently with other uses ends the process with Go's fatal error
`concurrent map writes` (see [Go interoperability](interop.md#go-data)).

## Garbage collection and memory

| Aspect | Behavior |
|--------|----------|
| Collector | Go's concurrent, non-generational, non-moving mark-and-sweep collector. Tuned with `GOGC` and `GOMEMLIMIT` |
| Object addresses | Stable (objects never move), so identity hash codes are derived from addresses |
| `finalize()` | **Deviation:** never called |
| `WeakReference`, `SoftReference` | **Deviation:** hold their referent strongly; `get` returns it until `clear()`. `ReferenceQueue` exists but is never filled. `PhantomReference` is not implemented. `WeakHashMap` holds its keys strongly |
| Heap exhaustion | **Deviation:** a Go fatal error (`fatal error: runtime: out of memory`), not a catchable `OutOfMemoryError` |
| `Runtime.totalMemory`, `freeMemory` | The heap Go obtained from the system, and its unused part |
| `Runtime.maxMemory` | `Long.MaxValue` (no limit unless `GOMEMLIMIT` is set) |
| `Runtime.gc()` | `runtime.GC()`. **Deviation (bug):** `System.gc()` is missing from javalib (a link error) |

## Stack

Each thread runs on a goroutine stack that starts small and grows as needed, up to Go's limit
(1 GB on 64-bit platforms).

- Recursion much deeper than the JVM's default thread stacks (512 KB–1 MB) allows succeeds.
- **Deviation:** exhausting the stack is a Go fatal error (`fatal error: stack overflow`, exit
  status 2), not a `StackOverflowError`: it cannot be caught, `finally` blocks do not run, and
  buffered standard output is lost. Verified: a `try`/`catch` of `StackOverflowError` around
  infinite recursion catches it on the JVM; the Go program aborts.
- The `stackSize` argument of `Thread` constructors and builders is ignored.

## Class and object initialization

| What | When | How |
|------|------|-----|
| `object` (module) | first access to the object | constructor runs once under a per-object `rt.InitLock` |
| static initializer (`@static`, Java statics in javalib, cases of Java-style enums) | before the first instantiation, static field access or static method call from another class | runs once under a per-class `rt.InitLock` |
| `lazy val` | first access | compare-and-set protocol, waiting threads block |

- Initialization is thread-safe: a thread finding an initialization in progress in another
  thread waits for it.
- Re-entrant access from the initializing thread (cycles) sees the partially initialized state,
  as on the JVM.
- A failed initialization makes later accesses throw `NoClassDefFoundError`. **Deviation:** the
  first failure is not wrapped in `ExceptionInInitializerError` (see
  [Language](language.md#5-classes-and-objects)).
- Initialization deadlocks between threads (two objects initialized concurrently in opposite
  orders) deadlock as on the JVM.
- After initialization, an access costs one atomic load.

## Reflection and class loading

There is no class loading: the program is linked whole, and only reachable code exists.

| API | Behavior |
|-----|----------|
| `getClass`, `classOf[T]` | A `Class` object per JVM class name, interned (so `==` and `eq` work) |
| `Class.getName`, `getTypeName`, `toString`, `isArray`, `isPrimitive`, `getComponentType` | As on the JVM, from the class name |
| `Class.getSimpleName` | As on the JVM, except for local classes (see [Language](language.md#5-classes-and-objects)) |
| `Class.isInstance`, `cast` | Not implemented (link error if reached) |
| `Class.isAssignableFrom` | Works for the program's classes (the linker then embeds the class hierarchy), primitives, arrays and the JDK value classes |
| `Class.forName(name)` | **Deviation:** returns a `Class` with that name for any name; never throws `ClassNotFoundException`, and the class is not initialized |
| `getClassLoader` | `null` |
| `getResource`, `getResourceAsStream` | `null`: a binary has no class-path resources |
| `desiredAssertionStatus` | `false` |
| `java.lang.reflect.Array` | `newInstance`, `getLength`, `get`, `set` work. **Deviation:** an array created for a reference component type is an `Object[]` |
| `ClassTag` | Works; `newArray` for reference types gives `Object[]` arrays |
| fields, methods, constructors, annotations, proxies, `MethodHandle`, `VarHandle` | Not implemented |
| `ServiceLoader`, atomic field updaters | Not implemented |

Programs or libraries that depend on run-time reflection fail to link at the first reachable
use, with the chain of calls that reaches it.

## Standard I/O

- `System.out` (and `Console.out`, `println`) writes UTF-8 into a 32 KB buffer, flushed at
  exit, when it is full, before anything is written to standard error, before standard input is
  read, before and after calls into Go code, and before a subprocess inherits standard output.
  **Deviation:** the JDK's `System.out` flushes on every line, so output interleaves with other
  processes or file descriptors differently, and output not yet flushed is lost on abnormal
  termination (see [Program start and exit](#program-start-and-exit)).
- `System.err` is unbuffered.
- Each `print`/`println` call is written atomically: lines from different threads never
  interleave. **Deviation:** the console print methods do not take the stream's monitor, so
  holding `System.out`'s lock does not block other threads' printing.
- Unpaired surrogates are written as `?`, as by the JDK's console streams.
- `System.in` is a buffered stream over the process's standard input.
- `System.setOut`, `setErr`, `setIn` and `Console.withOut` work.
- **Deviation:** writing to a closed pipe on standard output ends the process with `SIGPIPE`
  (Go's behavior for file descriptors 1 and 2); the JVM sets the `PrintStream`'s error flag
  and continues.

## System properties and environment

| Property | Value |
|----------|-------|
| `line.separator` | `\n` (`\r\n` on Windows) |
| `file.separator`, `path.separator` | the platform's |
| `file.encoding`, `native.encoding`, `sun.jnu.encoding`, `stdout.encoding`, `stderr.encoding` | `UTF-8` |
| `java.io.tmpdir` | `/tmp` on Linux, Go's `os.TempDir()` elsewhere |
| `os.name` | `Linux`, `Mac OS X`, `Windows`, `FreeBSD`, else `GOOS` |
| `os.arch` | `GOARCH`, with `aarch64` for arm64 and `x86` for 386 |
| `user.dir`, `user.home`, `user.name` | from the working directory, Go's `os.UserHomeDir`, `$USER`/`$LOGNAME`/`$USERNAME` |
| `java.vm.name`, `java.vendor` | `scala-go` |
| `java.class.path`, `java.library.path` | empty |

- **Deviation:** `java.version`, `java.home`, `java.specification.version` and the other JVM
  properties are not set (`null`).
- `System.setProperty`, `clearProperty`, `getProperties` work on an in-memory `Properties`.
- `System.getenv` reads the process environment; `ProcessBuilder.environment` is a copy.

## Time

- `System.currentTimeMillis` is the wall clock (`time.Now`).
- `System.nanoTime` is monotonic (time since program start, from Go's monotonic clock).
- `java.time.Clock`/`Instant.now` have nanosecond precision.
- Time zones are Go's: the system's zoneinfo, or the copy of the tz database embedded in the
  binary when the program uses named zones. The default zone follows the JDK's rules on Linux
  (`TZ`, `/etc/timezone`, `/etc/localtime`), falling back to `GMT`.
- Differences from the JDK's `java.time` (no long zone names, transitions listed up to 2037,
  ...) are listed in the design document's `java.time` section.

## Locales, text and charsets

- **Deviation:** there is no locale data. `Locale.getDefault()` is `en_US` regardless of the
  environment, and every locale formats numbers and dates and maps case as `Locale.ROOT`
  (`String.format(Locale.GERMANY, "%.1f", x)` uses `.`).
- `String.format` supports the common conversions (`%s %d %x %o %f %e %g %c %b %n %%`, widths,
  precision, flags `-`, `0`, `+`, space). `%f` and `%e` round as the JDK does.
  **Deviation:** the `,` grouping flag, argument indices and other conversions are not
  supported; format errors throw `IllegalArgumentException` itself (the JDK throws subclasses
  such as `UnknownFormatConversionException`). **Deviation (bug):** `%g` is Go's, which drops
  trailing zeros (`"%g".format(1.0)` is `1`; JDK: `1.00000`).
- Charsets: UTF-8 (default), ISO-8859-1 and US-ASCII, with the JDK's malformed-input handling.
  Other charsets are not available.
- **Deviation:** `Character` classification and case mapping use Go's Unicode tables
  (Unicode 15.0 with Go 1.26); JDK 25 uses Unicode 16.0. Code points added in Unicode 16
  classify differently.
- Regular expressions run on Go's RE2 engine: lookaround, backreferences and possessive
  quantifiers are not supported.

## Stack traces

- `Throwable` captures the call stack when constructed (unless `writableStackTrace` is false),
  as Go program counters, and symbolizes it on first use into `StackTraceElement`s with Scala
  class names, method names, file names and lines.
- `printStackTrace` prints the JVM's format, with `Caused by:`, `Suppressed:` and `... n more`.
- **Deviation:** frames of JDK methods implemented in Go are missing, and frames of javalib
  methods show javalib's Scala sources (`java.lang.Thread.run(Thread.scala:62)`); module
  initialization has no `<clinit>` frame; a frame's line is the line of its statement's start;
  a thread's trace may end with the frames that started it.
- `SCALAGO_TRACE=1` keeps every frame, with Go function names.

## Signals

scala-go installs no signal handlers; Go's defaults apply:

| Signal | Behavior |
|--------|----------|
| `SIGINT`, `SIGTERM`, `SIGHUP` | the process terminates by the signal. **Deviation:** no shutdown hooks |
| `SIGQUIT` | Go prints every goroutine's stack and exits with status 2. **Deviation:** the JVM prints a thread dump and continues |
| `SIGPIPE` on standard output or error | the process terminates (see [Standard I/O](#standard-io)) |
| `SIGSEGV`, `SIGBUS`, `SIGFPE` from Go code | Go run-time panics, translated into Java exceptions where possible |

There is no `sun.misc.Signal`.

## Native interoperability

- There is no JNI. `@native` methods have no implementation.
- Native code is reached through Go: Go facades (`@GoImport`) call any Go package, and Go
  packages can use cgo. The generated code and the runtime are pure Go, so `CGO_ENABLED=0`
  builds static binaries unless a facade's package needs cgo.
- Go code calls Scala code through `@GoExport`ed functions and classes, and through Go
  interfaces implemented in Scala.

## Platform

- Any `GOOS`/`GOARCH` that Go supports and that is 64-bit and little-endian; cross-compiling is
  `GOOS=... GOARCH=... go build`. **Deviation:** big-endian platforms are not supported
  (`ByteOrder.nativeOrder()` is little-endian).
- amd64 and arm64 have fast paths (frame-pointer stack capture, reading the goroutine id);
  other architectures fall back to slower portable code.
- File system paths are Unix paths.
- Floating-point results can depend on the architecture (fused multiply-add, see
  [Language](language.md#numeric-operations)).

## Go implementation dependence

scala-go targets gc, the Go project's toolchain (`go build`). Where the Go specification leaves
behavior to the implementation, the generated code and the runtime either avoid depending on it
or depend on gc's behavior:

| Behavior | Go specification | scala-go |
|----------|------------------|----------|
| out-of-range floating-point to integer conversions | result implementation-dependent ([Conversions][go-conversions]) | not used: checked before converting |
| floating-point division by zero | IEEE 754 result; whether it panics is implementation-specific ([Floating-point operators][go-floatops]) | relies on gc: no panic |
| fused multiply-add | permitted ([Floating-point operators][go-floatops]) | not prevented (a deviation, see [Language](language.md#numeric-operations)) |
| order of variable, field and index reads relative to calls | unspecified ([Order of evaluation][go-order]) | avoided where an operand needs statements; otherwise gc's order (a deviation, see [Language](language.md#evaluation-order)) |
| values of run-time panics | `runtime.Error`s, exact values unspecified ([Run-time panics][go-rtpanics]) | relies on gc's messages to choose the Java exception (`integer divide by zero`, `nil pointer dereference`, `index out of range [i] with length n`, `makeslice: len out of range`) |
| size of `int`, `uint`, `uintptr` | implementation-specific ([Numeric types][go-numeric]) | 64 bits: `GoInt` is a `Long`, and only 64-bit platforms are supported |
| moving objects | not specified | relies on gc's non-moving collector: identity hash codes are addresses |
| goroutine identity | not exposed | gc's goroutine structure on amd64 and arm64 (`rt/goid.go`), otherwise the output of `runtime.Stack` |
| stack size | not specified | gc's growable stacks, up to 1 GB on 64-bit platforms |
| map iteration order | unspecified, and differs between iterations ([For statements with range clause][go-range]) | not relied on: `GoMap` iteration is Go's order, Scala and Java maps are the libraries' |

Other Go implementations (gccgo, TinyGo) are not supported.

[go-conversions]: https://go.dev/ref/spec#Conversions
[go-exec]: https://go.dev/ref/spec#Program_execution
[go-floatops]: https://go.dev/ref/spec#Floating_point_operators
[go-go]: https://go.dev/ref/spec#Go_statements
[go-init]: https://go.dev/ref/spec#Program_initialization_and_execution
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
