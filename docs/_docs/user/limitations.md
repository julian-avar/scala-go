---
title: Limitations
---

# Limitations

What scala-go cannot do yet, by area, with workarounds. What runs but behaves differently is in
[Differences from the JVM](differences.md). The [Roadmap](../contributing/roadmap.md) orders the
missing pieces by priority and lists known bugs.

Using an API scala-go does not implement is a link error that names it, not a silent difference
at run time ([Troubleshooting](troubleshooting.md#link-errors)). Each limitation here is checked
by an end-to-end test (`tests/cases/limits-*`) that expects it to fail.

## Language and runtime

- **Runtime reflection** (`getMethods`, `Method.invoke`, annotations at run time, dynamic proxies,
  Java serialization, class loading) does not exist. `getClass`, class names and
  `isAssignableFrom` work. Use macros, givens and `Mirror`s, as most Scala 3 libraries do.
  [Runtime: reflection](../spec/runtime.md#reflection-and-class-loading).
- **Type tests with a `ClassTag`** (`case x: T` given a `ClassTag[T]`) throw `NoSuchMethodError`:
  `Class.isInstance` and `Class.cast` are missing. Match on concrete types.
- **Structural types** (`reflectiveSelectable`) throw. Use a trait.
- **Classpath resources** are not embedded: `getResourceAsStream` returns `null`. Read files, or
  embed data in code.

## JDK and Scala libraries

- **Missing JDK classes** include `Scanner`, `java.util.Formatter`, `EnumSet`/`EnumMap`,
  `Calendar`, `java.util.logging`, `java.text`'s number and date formats, TLS configuration and
  ciphers, UDP and NIO socket channels. Use another API, or call Go through a
  [facade](../interop/facades.md). [The JDK](../libraries/jdk.md).
- **`String.format` and `f"..."`** throw on argument indices (`%1$s`), the `,` flag (`%,d`) and
  `%t`. `String.formatted` is missing. Other output differences:
  [Differences](differences.md#text).
- **Regular expressions** are Go's RE2: no lookaround, backreferences or possessive quantifiers.
- **Charsets:** UTF-8, ISO-8859-1 and US-ASCII only.
  [Runtime: locales](../spec/runtime.md#locales-text-and-charsets).
- **HTTP client:** no proxies, cookies or authenticators.
- **Libraries with Java code**, Scala 2 libraries and libraries built on runtime reflection do not
  link. Blocked today: cats-effect, ZIO, fs2-io, circe, zio-json, scribe, scopt, requests-scala,
  munit, utest. Use Go libraries in place of Java ones. [Scala libraries](../libraries/scala.md).
- **Unused library code that needs a missing API:** `def goMissingApis = "throw"` links anyway.
  [Troubleshooting](troubleshooting.md#link-errors).

## Concurrency

- **Atomic field updaters** (`AtomicReferenceFieldUpdater`, ...) are missing.
- **`scala.collection.concurrent.TrieMap`** does not link: its nodes are Java classes of the Scala
  library, with no TASTy. Use `ConcurrentHashMap`, or an immutable map behind `synchronized` (as
  the [gin example](../examples/gin.md) does).
- **cats-effect and ZIO** do not link. Use gears or `scala.concurrent`
  ([Concurrency](concurrency.md)).
- **Parallel streams** run sequentially.
- **Missing:** `SubmissionPublisher`, `Thread.getAllStackTraces`, `ThreadGroup.activeCount` and
  `ThreadGroup.enumerate`.

## Go interop

- **Programs that call Go run only as Go:** facade members throw on the JVM. Put Go calls behind
  a trait of your own to test the rest on the JVM. [Calling Go](../interop/facades.md#on-the-jvm).
- **No Go struct types from Scala:** Go sees a Scala class only through Go interfaces, never as a
  struct with fields and struct tags. Go code that binds or scans into structs (`encoding/json`,
  gin's `ShouldBindJSON`, argus's `Bind[T]`, database scanners) cannot fill a Scala class. Bind
  into `any` and convert with [`GoData.toScala`](../interop/go-data.md#scala-data-for-go-code), or
  parse with a Scala library ([gin example](../examples/gin.md)).
- **Generic Go code:** generated facades leave out generic Go types, and generic functions whose
  constraints have type sets (`~int | string`, `comparable`, most of `slices`, `maps`, `cmp`). A
  hand-written facade can declare a generic function once per instantiation (`slices.Max` for
  `[]int`, see [Calling Go](../interop/facades.md#generic-functions)). Generic types cannot be
  declared.
- **Go data shapes left out of generated facades:**
  - unnamed struct and interface types (gin's `Context.Done()` returns `<-chan struct{}`);
  - arrays and complex numbers;
  - unexported types in exported APIs (gin's `Context.Errors`), types of `internal` packages;
  - funcs stored in Go data (struct fields of func type, slices of funcs), variadic func types;
  - Go funcs with more than 3 parameters returned to Scala;
  - constants beyond `Long`.

  Each is a `// not generated:` comment in the generated source. Declare the member in a
  hand-written facade where the [conversions](../interop/facades.md#conversions) can express it.
  [Generated facades](../interop/generated-facades.md#what-is-left-out).
- **Named slice and map types** (`gin.H`, `type H map[string]any`) are facade classes, not
  `GoSlice`s or `GoMap`s. Build them from elements or entries (`gin.H("k" -> v)`) or from a
  `GoMap`; their elements cannot be read from Scala.
- **Named numeric types** (`time.Duration`) are facade classes, not Scala numbers: no arithmetic.
  Convert with `time.Duration(nanos)` and `d.Nanoseconds()`.
- **No `select`** over several Go channels from Scala. Receive on each from its own thread, or
  write the `select` in a small Go function of a [local module](../interop/go-libraries.md).
- **`GoMap` written concurrently** without synchronization ends the process (Go's
  `concurrent map writes`). [Data representation](../spec/data.md#go-interop-types).
- **Errors:** a generated facade throws a trailing `error` result as a `GoException`. To get the
  error as a value, declare the function by hand with a `(T, GoError)` result.
- **Strings** passed to Go are transcoded from UTF-16. Use `GoString` in hot paths.
- **cgo:** Go modules that need cgo (such as `mattn/go-sqlite3`) need a C compiler, and rule out
  `CGO_ENABLED=0` static builds unless they have a fallback without cgo (go-sqlite3 has a stub,
  which the [argus example](../examples/argus.md) can use). Facades of packages that use cgo
  directly, and stack traces through C calls, are untested.
- **Go versions:** Go modules can require a newer Go than 1.27. Go's default `GOTOOLCHAIN=auto`
  downloads it.
- **`scalago-gostd`** covers part of the standard library. Generate facades for the other
  packages (`net/http`, `os/exec`, `regexp`, ...) with `goFacadePackages`.
  [Go standard library](../libraries/gostd.md).
- **Exports** (Go calling Scala): no exported traits, `val`s or fields, secondary constructors,
  tuple results, overloads with the same Go name or generic methods; one Scala library per Go
  binary. [Calling Scala from Go](../interop/exports.md#limitations).

## Tooling

- **Scala 3.9.0 only:** the compiler plugin and the standard library's IR are built for exactly
  that version.
- **Mill only:** no sbt plugin, no scala-cli support.
- **Not published:** build the artifacts with `./mill __.publishLocal`
  ([Getting started](../getting-started.md)).
- **Tests run on the JVM only:** test frameworks do not run as Go. Test on the JVM and run the Go
  binary in CI.
- **Build speed:** every link regenerates the whole Go module (about 1,000 files for a small
  program), and `go build` recompiles it: a change takes tens of seconds. Develop on the JVM
  (`mill app.run`) when the program does not call Go.
- **Editors:** link errors appear in Mill's output, not in Metals. Delve debugs the generated code
  but shows Scala values as Go structures.
- **`goRun` has no terminal:** run terminal UIs from `goBuild`'s executable.
  [Build setup](build.md#tasks).

## Platforms

- **64-bit little-endian targets only** (amd64, arm64, ...). Big-endian ones (s390x, ppc64, mips)
  are not supported; 32-bit ones are untested. [Runtime: platform](../spec/runtime.md#platform).
- **Windows** builds, but file paths are Unix paths, there are no file locks, and the tests do not
  run there.
- **WebAssembly** (`wasip1`, `js`) is untested.
- Tested: Linux and macOS on amd64 and arm64.
