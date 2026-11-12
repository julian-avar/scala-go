---
title: Limitations
---

# Limitations

What scala-go cannot do yet, with workarounds. What runs but behaves differently is in
[Differences from the JVM](differences.md); the [Roadmap](../contributing/roadmap.md) orders the
missing pieces and lists the [known bugs](../contributing/roadmap.md#known-bugs). An API
scala-go does not implement is a link error naming it, never a silent difference at run time
([Troubleshooting](troubleshooting.md#link-errors)). Each limitation is pinned by a
`tests/cases/limits-*` case that expects it to fail.

## Language and runtime

- **Runtime reflection** (`getMethods`, `Method.invoke`, run-time annotations, dynamic proxies,
  class loading) does not exist; `getClass`, class names and `isAssignableFrom` work. Use macros,
  givens and `Mirror`s. [Runtime: reflection](../spec/runtime.md#reflection-and-class-loading).
- **Type tests with a `ClassTag`** (`case x: T`), user-defined `TypeTest` givens in patterns,
  `Future.mapTo` and `PartialFunction.elementWise` do not link: `Class.isInstance`/`cast` are
  missing, with other `Class` members
  ([roadmap](../contributing/roadmap.md#silent-wrong-output)). Match on concrete types.
- **Structural types** (`reflectiveSelectable`) do not link. Use a trait.
- **Classpath resources** are not embedded: `getResourceAsStream` returns `null`, and
  `Source.fromResource` does not link. Read files, or embed data in code.
- **No Go counterpart:** Java serialization, JMX (`java.lang.management`), `SecurityManager`,
  `System.loadLibrary` and method handles do not link.

## JDK and Scala libraries

- **Missing JDK classes** include `Scanner`, `java.util.Formatter`, `EnumSet`/`EnumMap`,
  `Calendar`, `Timer`, `Currency`, `ServiceLoader`, `ResourceBundle`, `java.util.logging`,
  `java.text` (so `StdIn.readf*`), `java.util.jar`, TLS configuration and ciphers, UDP and NIO
  socket channels, HTTP proxies, cookies and authenticators, `PhantomReference` and `Cleaner`.
  Use another API, or call Go through a [facade](../interop/facades.md).
  [The JDK](../libraries/jdk.md).
- **Missing methods** of common classes (`String`, `Character`, the boxes, `Math`,
  `BigInteger`, `System`, `Thread`): listed in [roadmap M3](../contributing/roadmap.md#m3-common-programs)
  and pinned by the `limits-jdk-*` cases. Some break Scala library methods: `math.ulp`,
  `Random.between(Double, Double)`, `BigInt.probablePrime`, `Source.fromResource`.
- **`String.format` and `f"..."`** throw on argument indices (`%1$s`, `%<s`), the `,` flag, `%h`,
  `%t`, `%a`, and `BigDecimal` with `%f`, with an `IllegalArgumentException` that names the
  JDK's exception. Output differences: [Differences](differences.md#text).
- **Regular expressions** are Go's RE2: no lookaround, backreferences, possessive quantifiers or
  atomic groups. Such a pattern throws `UnsupportedOperationException` when compiled.
- **Charsets:** UTF-8, ISO-8859-1 and US-ASCII only; `Charset.isSupported` is `false` for others,
  and using one throws `UnsupportedOperationException`.
- **Libraries with Java code**, Scala 2 libraries and libraries built on runtime reflection do not
  link: today cats-effect, ZIO, fs2-io, circe, zio-json, scribe, scopt, requests-scala, munit,
  utest. Use Go libraries in place of Java ones. [Scala libraries](../libraries/scala.md).
- **Unused library code that needs a missing API:** `def goMissingApis = "throw"` links anyway
  ([Troubleshooting](troubleshooting.md#link-errors)).

## Concurrency

- **Atomic field updaters** (`AtomicReferenceFieldUpdater`, ...), `SubmissionPublisher`,
  `Thread.getAllStackTraces`, `ThreadGroup.activeCount` and `ThreadGroup.enumerate` are missing.
- **`TrieMap`** does not link (its nodes are Java classes without TASTy). Use
  `ConcurrentHashMap`, or an immutable map behind `synchronized` ([gin example](../examples/gin.md)).
- **cats-effect and ZIO** do not link. Use gears or `scala.concurrent` ([Concurrency](concurrency.md)).
- **Parallel streams** run sequentially; `IntStream.parallel()` and the other primitive streams'
  do not link.
- **`StringBuffer` is not synchronized** ([known bug](../contributing/roadmap.md#known-bugs)):
  guard a shared one with a lock, or give each thread its own `StringBuilder`.

## Go interop

- **Programs that call Go run only as Go**: facade members throw on the JVM. Keep Go calls behind
  a trait to test the rest on the JVM. [Calling Go](../interop/facades.md#on-the-jvm).
- **No Go structs from Scala:** Go sees Scala classes only through interfaces, so `encoding/json`,
  gin's `ShouldBindJSON` and database scanners cannot fill one. Bind into `any` and convert with
  [`GoData.toScala`](../interop/go-data.md#scala-data-for-go-code), or parse in Scala.
- **Generics:** Scala does not check Go constraints (a [known bug](../contributing/roadmap.md#known-bugs));
  some type arguments must be explicit; no type tests against generic facades.
  [Generics](../interop/facades.md#generics).
- **Values of types Scala cannot name** (unexported, `internal`) are typed `Any` or as an
  interface; calls taking them use reflection, so a wrong argument is a `ClassCastException` at
  run time. [Generated facades](../interop/generated-facades.md#what-is-left-out).
- **Go data shapes left out** of generated facades (arrays and funcs inside Go data, unnamed
  struct types, pointer methods of non-struct types, ...): declare the member in a hand-written
  facade. [What is left out](../interop/generated-facades.md#what-is-left-out).
- **Named slice, map and numeric types** (`gin.H`, `time.Duration`) are facade classes: no
  element access, no arithmetic. [Go data](../interop/go-data.md#limitations),
  [Conversions](../interop/facades.md#conversions).
- **No `select`** over several Go channels: receive on each from its own thread, or write the
  `select` in Go in a [local module](../interop/go-libraries.md).
- **A `GoMap` written concurrently** without synchronization ends the process.
  [Go data](../interop/go-data.md#limitations).
- **Errors:** generated facades throw a trailing `error`; declare the function by hand with a
  `(T, GoError)` result to get it as a value. [Errors](../interop/facades.md#errors).
- **`scalago-gostd`** leaves out about 40 of 16,700 members, follows Linux/amd64's API (members
  other platforms lack are Go compile errors there), and its `import scalago.gostd.*` hides
  Scala's `io`, `math`, `reflect` and `runtime`. [Go standard library](../libraries/gostd.md#limitations).
- **Exports** (Go calling Scala): no traits, `val`s, fields, secondary constructors, tuple
  results, generic methods or overloads sharing a Go name; one Scala library per Go binary.
  [Calling Scala from Go](../interop/exports.md#limitations).
- **cgo:** Go modules that need cgo (`mattn/go-sqlite3`) need a C compiler and rule out
  `CGO_ENABLED=0` builds unless they have a fallback. Facades of cgo packages are untested.
  [cgo](../interop/go-libraries.md#cgo).
- **Go versions:** a Go module can require a newer Go than 1.27; Go's default
  `GOTOOLCHAIN=auto` downloads it.

## Tooling

- **Scala 3.9.0 only**: the plugin and the standard library's IR are built for that version.
- **Mill only**: no sbt plugin or scala-cli support.
- **Not published**: build the artifacts with `./mill __.publishLocal`
  ([Getting started](../getting-started.md)).
- **Tests run on the JVM only**: test on the JVM, and run the Go binary in CI.
- **Build speed**: every link regenerates the whole Go module and `go build` recompiles it, so a
  change takes tens of seconds. Develop on the JVM (`mill app.run`) when the program does not
  call Go.
- **Editors**: link errors appear in Mill's output, not in Metals. Delve shows Scala values as Go
  structs.
- **`goRun` has no terminal**: run terminal UIs from `goBuild`'s executable
  ([Build setup](build.md#tasks)).

## Platforms

- **64-bit little-endian only** (amd64, arm64, ...): big-endian targets are unsupported, 32-bit
  ones untested. [Runtime: platform](../spec/runtime.md#platform).
- **Windows** builds, but paths are Unix paths, there are no file locks, and tests do not run
  there. **WebAssembly** (`wasip1`, `js`) is untested.
- Tested: Linux and macOS on amd64 and arm64.
