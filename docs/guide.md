# Using scala-go

This guide is for building and shipping programs with scala-go. [design.md](design.md) explains
how it works.

## Setup

You need a JDK (17 or later) and Go (1.22 or later) on the `PATH`, and Mill. In `build.mill`:

```scala
//| mvnDeps: ["io.github.scalago:mill-scalago_mill1_3:0.1.0-SNAPSHOT"]
import scalago.mill.ScalaGoModule

object app extends ScalaGoModule   // Scala 3.9.0
```

| Command                | Does                                                              |
|------------------------|-------------------------------------------------------------------|
| `mill app.goRun a b`   | compiles, links, builds and runs the program with arguments `a b` |
| `mill app.goBuild`     | the executable (`out/app/goBuild.dest/app`)                       |
| `mill app.goLink`      | the Go module only (`out/app/goLink.dest/go`), for `go build` yourself |
| `mill app.run`         | the same program on the JVM, as usual                             |

The plugin only adds `.goir` files next to the `.class` files, so the module still compiles,
tests and runs on the JVM. Test your logic on the JVM, and run the Go binary in CI as well.

The Scala version must be exactly the one the plugin was built for (3.9.0), because the
plugin uses compiler internals.

## Dependencies

Scala 3 libraries need no scala-go specific artifact: add them to `mvnDeps` as usual, and
`ScalaGoModule` compiles them to Go from the TASTy in their jars (cached by Mill). For
example cats, upickle (including derived `ReadWriter`s), fastparse, pprint, mainargs, os-lib and
gears work (this repository's `mill ecosystem.check` compares them with the JVM).

What does not work:

- **Java code in dependencies.** Classes written in Java have no TASTy. Pure-Java libraries,
  and Java classes inside Scala libraries, are missing at link time (a few, such as ujson's
  float formatting, have native implementations).
- **Reflection and runtime code generation.** `Class.forName`, `getMethods`, Java
  serialization and dynamic proxies are not available. Libraries that only use macros, given
  instances and `Mirror`s (most Scala 3 libraries) are fine.
- **Scala 2 libraries** (no TASTy).

## The JDK

scala-go has its own Java standard library (javalib), written in Scala: `java.lang`,
`java.util` (collections, streams, `Optional`, `Random`, `UUID`, `regex`, `Properties`, ...),
`java.util.concurrent` (executors, `ForkJoinPool`, `CompletableFuture`, locks, atomics,
concurrent collections; so `scala.concurrent.Future` too), `java.math` (`BigInteger`, `BigDecimal`, exact),
`java.io`, `java.nio` (buffers, file channels, `Files`, the default file system), subprocesses
(`ProcessBuilder`, so `scala.sys.process` and os-lib's `os.proc`), `java.time` (time zones from
Go's tz database), and the parts of `java.text` that `java.time` needs. design.md's [Status](design.md#status) lists the gaps.

Differences from the JVM worth knowing:

- **Regular expressions** use Go's RE2 engine: no lookaround or backreferences.
- **Locales:** there is no locale data; every locale formats as `Locale.ROOT`/English.
- **Memory model:** a data race on a reference field can crash a Go program, where the JVM
  would only see a stale value. Use `synchronized`, `@volatile`, atomics or channels, as you
  should on the JVM; `go build -race` finds races.
- **Standard output** is buffered and flushed at exit, before reading standard input and around
  Go calls, not after each line.
- **Stack traces** are like the JVM's, but line numbers are per statement, and JDK methods
  implemented natively have no frames.
- **`WeakHashMap`** keeps its keys (Go 1.22 has no weak pointers).
- **Unicode version:** character properties and case mappings come from Go's tables (Unicode
  15, like JDK 21); characters added in Unicode 16 (JDK 25) have no case mapping.

## When linking fails

The linker includes only the code that can run, and fails if that code uses something with no
implementation, naming it and showing how it is reached:

```
Main.scala:21:35: static method java.time.LocalDate.of(III)... is not available
    in Main$.main(Array[java.lang.String])
```

- Read the chain bottom-up: it starts at your `main` and ends at the missing API.
- If the API is in your own code, avoid it, or wrap it in a Go facade (see the README).
- Reachability over-approximates: a virtual call reaches every implementation of the method in
  every instantiated class. So code that never runs can need an API that does not exist. Set
  `def goMissingApis = "throw"`: the errors become warnings, and the missing APIs throw
  `NoSuchMethodError`/`NoClassDefFoundError` (with a stack trace) only if they run.

## Debugging

- Runtime errors print JVM-style stack traces with Scala file names and lines. Go compiler
  errors in generated code also point at Scala lines. `def goLineDirectives = false` shows Go
  positions instead, for reporting linker bugs.
- `SCALAGO_TRACE=1` keeps every Go frame in stack traces.
- The generated module (`goLink`) is ordinary Go: `go vet`, `go build -race`, `pprof` and Delve
  work on it.

## Performance

Generated code is a native binary that starts in a few milliseconds, with no JVM and no warm-up.
Against a fully warmed-up JVM (`mill bench.run 3` and `mill bench.goRun 3` in this repository,
third round; amd64):

| Code                                                         | Go time / JVM time |
|--------------------------------------------------------------|--------------------|
| virtual calls, exceptions                                    | 0.5–0.8            |
| numeric loops, strings, closures, mutable collections, `synchronized` | 1.6–3     |
| boxed streams, immutable collections, `java.time` arithmetic | 4–7                |
| allocation-heavy recursion, primitive and collector streams  | 10–13              |
| `DateTimeFormatter` parsing                                  | about 25           |

The JIT inlines across calls and removes short-lived allocations; Go's compiler cannot do that
across Go interfaces, and Go's garbage collector is not generational. A JVM before warm-up (the
first seconds of any program, and all of a short-lived one) is much closer to Go, or slower.

Tuning:

- `GOGC` and `GOMEMLIMIT` trade memory for less garbage collection, as for any Go program.
- In hot code, prefer arrays of primitives and `while` loops: generic collections box their
  elements, and each box is an allocation.
- `synchronized` costs about 55 ns uncontended; it is safe but not free in hot loops.

## Building and shipping

- **Cross-compiling:** `def goBuildEnv = Map("GOOS" -> "linux", "GOARCH" -> "arm64")`. The
  generated code and runtime are pure Go.
- **Release binaries:** `def goBuildFlags = Seq("-trimpath", "-ldflags=-s -w")`.
- **Static binaries:** add `"CGO_ENABLED" -> "0"` to `goBuildEnv` unless your Go facades need cgo.
- **Libraries for Go:** `ScalaGoLibraryModule` links `@GoExport`ed definitions into a Go module
  that Go code imports (see the README).
- The binary embeds Go's time zone database only when the program uses named time zones.
