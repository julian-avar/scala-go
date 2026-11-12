---
title: Runtime
---

# Runtime

A linked program is a Go module: generated code in `internal/scala`, the runtime in
`internal/rt`, and a `main` package that calls the entry point. This page specifies how it
executes relative to the same program on the JVM.

## Program start and exit

| Event | Behavior |
|-------|----------|
| Start | No class is initialized eagerly. Go package initialization creates string literals and `classOf` constants |
| Entry point | `main(Array[String])` of the configured object (`--main`, default `Main`), or the class the compiler generates for a `@main` method. `args` excludes the program name |
| `main` returns | Waits for every non-daemon `Thread`, runs the shutdown hooks, flushes standard output, exits with status 0 |
| Uncaught exception in `main` | Prints `Exception in thread "main" ` and the stack trace on standard error; then as above, with status 1 |
| Uncaught exception in another thread | Goes to the thread's handler, else the group's, else the default handler, else is printed as `Exception in thread "<name>" ...`. The program continues |
| `System.exit(n)`, `Runtime.exit(n)` | Runs the shutdown hooks (each in a new thread, concurrently, waiting for all), flushes standard output, exits with status `n`. Other threads stop |
| `Runtime.halt(n)` | Flushes standard output and exits with status `n`; no hooks |
| `exit` from a shutdown hook | Exits at once. **Deviation:** the JVM blocks forever |
| Go fatal error (stack or heap exhaustion, ...) | Go prints `fatal error: ...` and every goroutine's stack; exit status 2; no hooks |
| `SIGINT`, `SIGTERM`, `SIGHUP` | The process ends by the signal (status 128 + signal number in a shell); see [Signals](#signals) |

- **Deviation (bug):** an uncaught exception in the main thread is always printed by the runtime.
  `Thread.setDefaultUncaughtExceptionHandler` and the main thread's own handler are not called
  for it. Other threads use them as on the JVM.
- **Deviation:** buffered standard output is lost when the process ends by a signal or a Go fatal
  error (see [Standard I/O](#standard-io)).
- The main thread is a non-daemon `Thread` named `main` with priority 5. Its `threadId` is
  usually 1 (JDK 25: 3).

In Go (Go: [Program initialization and execution][go-init]):

- Package initialization runs first: `rt`, the Go packages facades import, then the generated
  package. Its package-level variables (string literals, `classOf` constants) are initialized in
  dependency order; its `init` functions install the runtime's hooks (Go:
  [Package initialization][go-pkginit]). No Scala code runs during package initialization.
- `main` calls `rt.RunMain`, which runs the entry point and recovers an uncaught exception. Go
  exits when `main` returns, without waiting for other goroutines (Go:
  [Program execution][go-exec]). So `rt.RunMain` first waits for the non-daemon threads (a
  counter under a `sync.Mutex` and `sync.Cond`), then runs the hooks and flushes standard output.
  Daemon threads end with the process, as on the JVM.
- `System.exit`, `Runtime.halt` and an uncaught exception in `main` call `os.Exit`, which runs no
  deferred functions: pending `finally` blocks of all threads are skipped, as on the JVM.
- A Go panic that nothing recovers ends the program with Go's report and exit status 2 (Go:
  [Handling panics][go-panics]). Threads started by `Thread.start` recover everything; a Scala
  exception thrown into Go code can end the program this way (see
  [Go interoperability](interop.md#errors-and-panics)).

## Threads

`java.lang.Thread` is javalib Scala code over goroutines (`rt/thread.go`). Each `Thread` runs on
its own goroutine, started by a `go` statement (Go: [Go statements][go-go]). Go's scheduler
multiplexes them over `GOMAXPROCS` OS threads. A goroutine has no identity, name or result in
Go; the runtime associates the `Thread` object with it.

| API | Behavior |
|-----|----------|
| `start`, `run`, `join` (with timeouts), `isAlive`, `getState` | As on the JVM. **Deviation (bug):** `getState` is `RUNNABLE` in `sleep`, `wait`, `join` and while blocked on a monitor; only `LockSupport` parking reports `WAITING`/`TIMED_WAITING`. The main thread stays alive after `main` returns |
| `currentThread` | The current goroutine's `Thread`. A goroutine not started as a `Thread` (the main goroutine, or one Go code started) gets one on first request, kept while it runs |
| names, ids, `setDaemon`, `isDaemon` | As on the JVM; default names `Thread-0`, `Thread-1`, ... |
| priorities | Stored and reported; no effect on scheduling. **Deviation (bug):** a new thread starts at 5, not at its creator's priority capped by its group's maximum |
| `sleep`, `yield`, `onSpinWait` | `time` timers and `runtime.Gosched`; `sleep` is interruptible |
| `interrupt`, `isInterrupted`, `interrupted` | As on the JVM. Wakes `sleep`, `join`, `Object.wait`, `LockSupport.park` (and everything built on it: locks, conditions, queues, futures), `Process.waitFor` and blocked `GoChan` operations. **Deviation:** does not interrupt blocking socket or file I/O |
| `Thread.ofPlatform()`, `ofVirtual()`, `startVirtualThread` | Both build goroutine-backed threads; virtual threads are daemons, unnamed by default, `isVirtual` |
| thread groups | Names and uncaught-exception handlers. **Deviation:** no `activeCount`, `enumerate` |
| uncaught-exception handlers | Per thread, per group and default, as on the JVM (main thread: see above) |
| `stop`, `suspend`, `resume`, `getAllStackTraces`, `getStackTrace` of another thread | Not implemented |
| `Runtime.availableProcessors` | `GOMAXPROCS` |

`ThreadLocal` and `InheritableThreadLocal` values are stored per `Thread`, as in the JDK, and
live until `remove` or the end of the thread. A new thread copies its creator's inheritable
values through `childValue`. Goroutines that Go code starts and that call Scala code are threads
as above (see [Go interoperability](interop.md#goroutines-and-threads)).

## Memory model

scala-go provides the Java Memory Model's (JMM) guarantees through the [Go memory model][go-mem],
which defines happens-before through synchronization in the same way. Each JMM edge is made by a
Go operation for which Go gives the same edge:

| JMM construct | scala-go | Go memory model rule |
|---------------|----------|----------------------|
| `synchronized`: a monitor exit happens-before the next entry | per-object reentrant monitor (a `sync.Mutex`, a `sync.Cond`, an owner and a count), created on first use | the *n*th `Unlock` of a `sync.Mutex` is synchronized before the *m*th `Lock` returns, for *n* < *m* ([Locks][go-mem-locks]) |
| `wait`/`notify`/`notifyAll` | a wait set of channels under the monitor's mutex; timeouts and interrupts. `IllegalMonitorStateException` without the monitor | the lock rule, and a send or close is synchronized before the receive it completes ([Channel communication][go-mem-chan]) |
| `@volatile` reads, writes, compare-and-set | `sync/atomic` values: `rt.VolatileInt32`, ..., and for references `rt.Volatile[T]`, an atomic pointer to an immutable box | atomic operations behave as if executed in one sequentially consistent order, and one whose effect another observes is synchronized before it ([Atomic values][go-mem-atomic]): the JMM's synchronization order of volatiles |
| `java.util.concurrent.atomic`, javalib's `VarHandle`-style operations | `sync/atomic` operations on volatile fields and arrays | as above |
| `Thread.start` happens-before the thread's actions | a `go` statement | the `go` statement is synchronized before the goroutine starts ([Goroutine creation][go-mem-go]) |
| a thread's actions happen-before `join` returns or `isAlive` returns `false` | the ending thread sets a volatile state and calls `notifyAll` under the thread's monitor; `join` waits on that monitor | a goroutine's exit is synchronized before nothing ([Goroutine destruction][go-mem-goexit]); the monitor and the volatile carry the edge |
| `LockSupport.unpark` happens-before the `park` it releases | a one-slot channel per thread | [Channel communication][go-mem-chan] |
| class and object initialization happens-before the uses that find it complete | `rt.InitLock`: an atomic flag, a mutex, and a channel closed when the initializer ends | [Atomic values][go-mem-atomic], [Locks][go-mem-locks], [Channel communication][go-mem-chan] |
| `lazy val` | compare-and-set on a volatile state field | [Atomic values][go-mem-atomic] |
| `final` fields | plain Go fields | **Deviation:** Go has no freeze rule; objects must be published through one of the edges above |
| data races on plain fields | plain Go memory | see below |

Data-race-free programs behave as on the JVM: both models make them sequentially consistent (Go:
[Overview][go-mem-overview]). Go's race detector (`go build -race` on the generated module)
reports data races in Scala code with Scala file names and lines.

For racy programs, Go guarantees only that a read of a location no larger than a machine word
observes a preceding or concurrent write. Races on multiword values can produce values no
single write wrote and, for interfaces, slices and strings, corrupt memory (Go:
[Implementation restrictions for programs containing data races][go-mem-races]). The JMM never
tears a reference. A racy read of a plain field is therefore:

| Field type | Go representation | Racy read |
|------------|-------------------|-----------|
| primitive types | one word on 64-bit platforms | a value some write stored, as on the JVM |
| `String`, arrays, `Class`, `BigInteger`, `BigDecimal` | a pointer: one word | a reference some write stored, as on the JVM |
| other reference types: classes, traits, `Any` | an interface: two words (type, data) | **Deviation:** may pair the type of one write with the data of another, and crash the program or corrupt memory |
| `GoString`, `GoSlice` and Go interface values held in such fields | inside the interface above | **Deviation:** as above |

A `GoMap` written concurrently with other uses is a fatal error (see
[Go data](interop.md#go-data)).

## Garbage collection and memory

| Aspect | Behavior |
|--------|----------|
| Collector | Go's concurrent, non-generational, non-moving mark-and-sweep collector, tuned with `GOGC` and `GOMEMLIMIT` |
| Object addresses | Stable (objects never move); identity hash codes derive from them |
| `finalize()` | **Deviation:** never called |
| `WeakReference`, `SoftReference` | **Deviation:** hold their referent strongly; `get` returns it until `clear()`. `ReferenceQueue` exists but has no methods (`poll`, `remove`). `PhantomReference` is not implemented. `WeakHashMap` holds its keys strongly |
| Heap exhaustion | **Deviation:** a Go fatal error (`fatal error: runtime: out of memory`), not a catchable `OutOfMemoryError` |
| `Runtime.totalMemory`, `freeMemory` | The heap Go obtained from the system, and its unused part |
| `Runtime.maxMemory` | **Deviation:** `Long.MaxValue` (no limit unless `GOMEMLIMIT` is set); the JVM reports its `-Xmx` |
| `Runtime.gc()` | `runtime.GC()`. **Deviation (bug):** `System.gc()` is missing from javalib (a link error) |

## Stack

Each thread runs on a goroutine stack that starts small and grows up to Go's limit (1 GB on
64-bit platforms). Recursion far deeper than the JVM's default thread stacks (512 KB–1 MB)
allows succeeds. The `stackSize` argument of `Thread` constructors and builders is ignored.

**Deviation:** exhausting the stack is a Go fatal error (`fatal error: stack overflow`, exit
status 2), not a `StackOverflowError`. It cannot be caught, `finally` blocks do not run, and
buffered standard output is lost. Verified: a `try`/`catch` of `StackOverflowError` around
infinite recursion catches it on the JVM; the Go program aborts.

## Class and object initialization

Semantics (once, thread-safe, cycles, failure): [Language §5](language.md#5-classes-and-objects).

| What | When | How |
|------|------|-----|
| `object` (module) | first access | constructor runs once under a per-object `rt.InitLock` |
| static initializer (`@static`, Java statics in javalib, cases of Java-style enums) | before the first instantiation, static field access or static method call from another class | runs once under a per-class `rt.InitLock` |
| `lazy val` | first access | compare-and-set protocol; waiting threads block |

- Two threads initializing objects in opposite orders deadlock, as on the JVM.
- After initialization, an access costs one atomic load.

## Reflection and class loading

There is no class loading: the program is linked whole, and only reachable code exists.

| API | Behavior |
|-----|----------|
| `getClass`, `classOf[T]` | One interned `Class` per JVM class name (`==` and `eq` work) |
| `Class.getName`, `getTypeName`, `toString`, `isArray`, `isPrimitive`, `getComponentType` | As on the JVM, from the class name |
| `Class.getSimpleName` | As on the JVM, except for local classes (see [Language](language.md#5-classes-and-objects)) |
| `Class.isInstance`, `cast` | Not implemented (link error if reached) |
| `Class.isAssignableFrom` | Works for the program's classes (the linker then embeds the class hierarchy), primitives, arrays and the JDK value classes |
| `Class.forName(name)` | **Deviation:** returns a `Class` with that name for any name; never throws `ClassNotFoundException`; does not initialize the class |
| `getClassLoader` | **Deviation:** `null`, also for program classes (JVM: the application class loader) |
| `getResource`, `getResourceAsStream` | `null`: a binary has no class-path resources |
| `desiredAssertionStatus` | `false` |
| `java.lang.reflect.Array` | `newInstance`, `getLength`, `get`, `set` work. **Deviation:** `newInstance` of a reference component type gives an `Object[]` (see [Arrays](data.md#arrays)) |
| `ClassTag` | Works; `newArray` of a reference type gives an `Object[]` |
| fields, methods, constructors, annotations, proxies, `MethodHandle`, `VarHandle` | Not implemented |
| `ServiceLoader`, atomic field updaters | Not implemented |

A program or library that depends on run-time reflection fails to link at the first reachable
use, with the chain of calls that reaches it.

## Standard I/O

- `System.out` (and `Console.out`, `println`) writes UTF-8 into a 32 KB buffer. It is flushed at
  exit, when full, before anything is written to standard error, before standard input is read,
  before and after calls into Go code, and before a subprocess inherits standard output.
  **Deviation:** the JDK's `System.out` flushes every line. Output interleaves differently with
  other processes or file descriptors, and unflushed output is lost on abnormal termination.
- `System.err` is unbuffered.
- Each `print`/`println` call is written atomically: lines from different threads never
  interleave. **Deviation:** the console print methods do not take the stream's monitor, so
  holding `System.out`'s lock does not block other threads' printing.
- Unpaired surrogates are written as `?`, as by the JDK's console streams.
- `System.in` is a buffered stream over the process's standard input.
- `System.setOut`, `setErr`, `setIn` and `Console.withOut` work.
- **Deviation:** writing to a closed pipe on standard output ends the process with `SIGPIPE`
  (Go's behavior for file descriptors 1 and 2). The JVM sets the `PrintStream`'s error flag and
  continues.

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

- **Deviation:** `java.version`, `java.home`, `java.specification.version` and the other JVM
  properties are `null`.
- `System.setProperty`, `clearProperty` and `getProperties` work on an in-memory `Properties`.
- `System.getenv` reads the process environment; `ProcessBuilder.environment` is a copy.

## Time

- `System.currentTimeMillis` is the wall clock (`time.Now`). `System.nanoTime` is monotonic: time
  since program start on Go's monotonic clock. `java.time.Clock`/`Instant.now` have nanosecond
  precision.
- `java.time` is scala-java-time 2.9.0 in javalib, with Go's tz database: the system's
  zoneinfo, or Go's embedded copy (`time/tzdata`), which the linker includes when the program
  can reach a named zone.
- The default zone is found as the JDK does on Linux: `TZ`, `/etc/timezone`, `/etc/localtime`,
  else `GMT`.
- **Deviation:** no long zone names ("Eastern Standard Time"); zone names are the tz
  abbreviations ("CEST"), or `GMT+03:00` where there is none. Texts and patterns are English
  (`en_US`) whatever the locale.
- **Deviation:** `ZoneRules.getTransitions` lists transitions up to 2037 and
  `getTransitionRules` is empty. In zones with negative DST (Europe/Dublin) the standard offset
  can differ from the JDK's. `ZoneId.getAvailableZoneIds` lists the system's zoneinfo (empty
  without one, though every zone still loads).
- **Deviation:** the Japanese calendar and the deprecated calendar methods of `Date`, `Calendar`
  and `SimpleDateFormat` are not implemented. Parsing a zone name (`z`) shared by several zones
  picks one arbitrarily.

## Locales, text and charsets

- **Deviation:** there is no locale data. `Locale.getDefault()` is `en_US` regardless of the
  environment, and every locale formats numbers and dates and maps case as `Locale.ROOT`:
  `String.format(Locale.GERMANY, "%.1f", x)` uses `.`.
- `String.format` supports `%s %d %x %o %f %e %g %c %b %n %%`, widths, precision and the flags
  `-`, `0`, `+`, space. `%f` and `%e` round as the JDK does.
  - **Deviation:** the `,` flag, argument indices and other conversions are not supported.
    Format errors throw `IllegalArgumentException` itself, not subclasses such as
    `UnknownFormatConversionException`.
  - **Deviation (bug):** `%g` is Go's, which drops trailing zeros: `"%g".format(1.0)` is `1`
    (JDK: `1.00000`).
- Charsets: UTF-8 (default), ISO-8859-1 and US-ASCII, with the JDK's malformed-input handling.
  No other charsets: `Charset.isSupported` is `false` for them. **Deviation (bug):** using one
  throws `UnsupportedOperationException` (JVM: `UnsupportedEncodingException` or
  `UnsupportedCharsetException`).
- `Character` classification and case mapping use Go's Unicode tables: Unicode 16.0 with Go 1.27,
  as in JDK 25. **Deviation:** they follow the Go release, not the JDK the program targets.
- Regular expressions run on Go's RE2 engine: no lookaround, backreferences or possessive
  quantifiers.

## Stack traces

- A `Throwable` captures the call stack as Go program counters when constructed (unless
  `writableStackTrace` is false). It is symbolized on first use into `StackTraceElement`s with
  Scala class names, method names, file names and lines.
- `printStackTrace` prints the JVM's format, with `Caused by:`, `Suppressed:` and `... n more`.
- **Deviation:** frames of JDK methods implemented in Go are missing; frames of javalib methods
  show javalib's Scala sources (`java.lang.Thread.run(Thread.scala:62)`); module initialization
  has no `<clinit>` frame; a frame's line is the line where its statement starts; a thread's
  trace may end with the frames that started it.
- `SCALAGO_TRACE=1` keeps every frame, with Go function names.

## Signals

scala-go installs no signal handlers; Go's defaults apply. There is no `sun.misc.Signal`.

| Signal | Behavior |
|--------|----------|
| `SIGINT`, `SIGTERM`, `SIGHUP` | The process terminates by the signal. **Deviation:** shutdown hooks do not run (the JVM runs them). Verified with `SIGTERM`: both exit with status 143; only the JVM runs the hook |
| `SIGQUIT` | Go prints every goroutine's stack and exits with status 2. **Deviation:** the JVM prints a thread dump and continues |
| `SIGPIPE` on standard output or error | The process terminates (see [Standard I/O](#standard-io)) |
| `SIGSEGV`, `SIGBUS`, `SIGFPE` from Go code | Go run-time panics, translated into Java exceptions where possible |

## Native interoperability

- There is no JNI; `@native` methods have no implementation.
- Native code is reached through Go: facades (`@GoImport`) call any Go package, and Go packages
  can use cgo. The generated code and the runtime are pure Go, so `CGO_ENABLED=0` builds static
  binaries unless a facade's package needs cgo.
- Go code calls Scala code through `@GoExport`ed functions and classes, and through Go
  interfaces implemented in Scala.

## Platform

- Any `GOOS`/`GOARCH` that Go supports and that is 64-bit and little-endian. Cross-compiling is
  `GOOS=... GOARCH=... go build`. **Deviation:** big-endian platforms are not supported
  (`ByteOrder.nativeOrder()` is little-endian).
- amd64 and arm64 have fast paths (frame-pointer stack capture, reading the goroutine id); other
  architectures use slower portable code.
- File system paths are Unix paths.
- Floating-point results can depend on the architecture (fused multiply-add, see
  [Language](language.md#numeric-operations)).

## Go implementation dependence

scala-go targets gc (`go build`). Where the Go specification leaves behavior to the
implementation, scala-go either avoids depending on it or depends on gc's behavior. Other Go
implementations (gccgo, TinyGo) are not supported.

| Behavior | Go specification | scala-go |
|----------|------------------|----------|
| out-of-range floating-point to integer conversions | implementation-dependent result ([Conversions][go-conversions]) | not used: checked before converting |
| floating-point division by zero | IEEE 754 result; whether it panics is implementation-specific ([Floating-point operators][go-floatops]) | relies on gc: no panic |
| fused multiply-add | permitted ([Floating-point operators][go-floatops]) | not prevented (a deviation, see [Language](language.md#numeric-operations)) |
| order of variable, field and index reads relative to calls | unspecified ([Order of evaluation][go-order]) | avoided where an operand needs statements; otherwise gc's order (a deviation, see [Language](language.md#evaluation-order)) |
| values of run-time panics | `runtime.Error`s, exact values unspecified ([Run-time panics][go-rtpanics]) | relies on gc's messages to choose the Java exception (`integer divide by zero`, `nil pointer dereference`, `index out of range [i] with length n`, `makeslice: len out of range`) |
| size of `int`, `uint`, `uintptr` | implementation-specific ([Numeric types][go-numeric]) | 64 bits: `GoInt` is a `Long`; only 64-bit platforms are supported |
| moving objects | not specified | relies on gc's non-moving collector: identity hash codes are addresses |
| goroutine identity | not exposed | gc's goroutine structure on amd64 and arm64 (`rt/goid.go`), otherwise the output of `runtime.Stack` |
| stack size | not specified | gc's growable stacks, up to 1 GB on 64-bit platforms |
| map iteration order | unspecified, differs between iterations ([For statements with range clause][go-range]) | not relied on: `GoMap` iteration is Go's order; Scala and Java maps are the libraries' |

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
