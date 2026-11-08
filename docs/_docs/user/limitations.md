---
title: Limitations
---

# Limitations

What scala-go cannot do, or not yet, by area. Each item says what is missing, how to work around
it, and where it is described in full. The [specification](../spec/index.md) marks every
difference from the JVM as a **Deviation**; the design document's
[Roadmap](../contributing/design.md#roadmap) lists the missing pieces by priority, and its
[Known bugs](../contributing/design.md#known-bugs) table the deviations that are defects.

Using something scala-go does not implement is a link error that names it (see
[Troubleshooting](troubleshooting.md#link-errors)), not a silent difference at run time.

## Language and runtime

- **Runtime reflection** (`getMethods`, `Method.invoke`, annotations at run time, dynamic proxies,
  class loading) does not exist in a Go binary. Use macros, givens and `Mirror`s.
  [Runtime: reflection](../spec/runtime.md#reflection-and-class-loading).
- **Type tests with a `ClassTag`** (`case x: T` given a `ClassTag[T]`) throw `NoSuchMethodError`,
  and `Class.forName` never throws `ClassNotFoundException`. Match on concrete types instead.
  [Roadmap: type tests by `Class`](../contributing/design.md#1-usable-outside-this-repository).
- **Structural types** (`reflectiveSelectable`) throw. Use a trait.
  [Roadmap](../contributing/design.md#2-jdk-and-scala-library-coverage-that-common-programs-hit).
- **Stack overflow and out of memory** end the process; `StackOverflowError` and
  `OutOfMemoryError` are never thrown. [Runtime: stack](../spec/runtime.md#stack).
- **Signals and fatal Go errors** end the program without running shutdown hooks or flushing
  standard output. Flush `System.out` yourself where output matters.
  [Runtime: signals](../spec/runtime.md#signals).
- **Data races** on reference fields can crash the program, and `final` fields have no
  publication guarantee. Synchronize as the Java memory model requires; build with `-race` to
  find races. [Runtime: memory model](../spec/runtime.md#memory-model).
- **Finalizers** never run; **weak and soft references** hold their referents strongly.
  [Runtime: garbage collection](../spec/runtime.md#garbage-collection-and-memory).
- **Arrays of references** do not know their element type (their class is `Object[]`), and
  **boxed primitives** have no identity. [Data representation](../spec/data.md).
- **Floating point:** `a * b + c` may be fused into one multiply-add on arm64, and
  `Math.round` is off by one in rare cases. [Known bugs](../contributing/design.md#known-bugs).
- The other differences users meet are in [Differences from the JVM](differences.md).

## JDK and Scala libraries

- **Missing JDK classes** include `Scanner`, `java.util.Formatter`, `EnumSet`/`EnumMap`,
  `Calendar`, `java.util.logging`, `java.text`'s number and date formats, TLS configuration and
  ciphers, UDP and NIO socket channels. Use another API, or call Go through a
  [facade](../interop/facades.md). [The JDK](../libraries/jdk.md),
  [Roadmap](../contributing/design.md#2-jdk-and-scala-library-coverage-that-common-programs-hit).
- **`String.format` and `f"..."`** support only part of `java.util.Formatter`'s syntax (no `%,d`,
  no argument indices, no `%t`). [Differences from the JVM](differences.md#behaving-differently).
- **Regular expressions** are RE2's: no lookaround, backreferences or possessive quantifiers.
- **Locales:** no locale data; every locale formats as English. **Charsets:** UTF-8, ISO-8859-1
  and US-ASCII only. [Runtime: locales](../spec/runtime.md#locales-text-and-charsets).
- **Classpath resources** are not in the binary: `getResourceAsStream` returns `null`. Read
  files, or embed data in code.
- **Concurrency:** the atomic field updaters are missing, and parallel streams run
  sequentially. [Concurrency](concurrency.md#limitations).
- **`scala.collection.concurrent.TrieMap`** does not link: its nodes are Java classes of the
  Scala library, with no TASTy. Use `java.util.concurrent.ConcurrentHashMap`.
- **Libraries with Java code**, Scala 2 libraries and libraries built on runtime reflection do not
  link. cats-effect, ZIO, fs2-io, circe and zio-json, scribe, scopt, requests-scala, munit and
  utest are blocked today; [Roadmap: Scala libraries](../contributing/design.md#3-scala-libraries)
  lists why. Use Go libraries in place of Java ones. [Scala libraries](../libraries/scala.md).
- When a library's unused code needs a missing API, `def goMissingApis = "throw"` links anyway.
  [Troubleshooting](troubleshooting.md#link-errors).

## Go interop

- **Programs that call Go run only as Go:** facade members throw on the JVM. Keep Go calls behind
  a trait of your own to test the rest on the JVM. [Calling Go](../interop/facades.md#on-the-jvm).
- **No Go struct types from Scala:** Go sees a Scala class only through Go interfaces, never as
  a struct with fields and struct tags. Go code that binds or scans into structs (`encoding/json`, gin's
  `ShouldBindJSON`, argus's `Bind[T]`, database scanners) cannot fill a Scala class. Bind into
  `any` and convert with [`GoData.toScala`](../interop/go-data.md#scala-data-for-go-code), or
  parse with a Scala library ([gin example](../examples/gin.md)).
  [Roadmap: Go interop](../contributing/design.md#4-go-interop).
- **Generic Go code:** generic Go types, and generic functions whose constraints have type sets
  (`~int | string`, `comparable`, most of `slices`, `maps`, `cmp`), are left out of generated
  facades. A hand-written facade can declare a generic function once per instantiation
  (`slices.Max` for `[]int`, see [Calling Go](../interop/facades.md#generic-functions)); generic
  types cannot be declared.
- **Go data shapes left out of generated facades:** unnamed struct and interface types (gin's
  `Context.Done()` returns a `<-chan struct{}`), arrays, complex numbers, unexported types in
  exported APIs (gin's `Context.Errors`), types of `internal` packages, funcs stored in Go data
  (struct fields of func type, slices of funcs), variadic func types, Go funcs with more than 3
  parameters returned to Scala, and constants beyond `Long`. Each is a `// not generated:`
  comment in the generated source; declare the member in a hand-written facade where the
  [conversions](../interop/facades.md#conversions) can express it.
  [Generated facades](../interop/generated-facades.md#what-is-left-out).
- **Named slice and map types** (`gin.H`, `type H map[string]any`) are facade classes, not
  `GoSlice`s or `GoMap`s: they are built from elements or entries (`gin.H("k" -> v)`) or from a
  `GoMap`, but their elements cannot be read from Scala.
- **No `select`** over several Go channels from Scala. Receive on each from its own thread, or
  write the `select` in a small Go function of a [local module](../interop/go-libraries.md).
- **Errors:** a generated facade throws a trailing `error` result as a `GoException`. To get the
  error as a value, declare the function by hand with a `(T, GoError)` result.
- **Strings** passed to Go are transcoded from UTF-16; use `GoString` in hot paths.
- **`GoMap`** written concurrently without synchronization ends the process (Go's
  `concurrent map writes`). [Data representation: Go interop types](../spec/data.md#go-interop-types).
- **Named numeric types** (`time.Duration`) are facade classes, not Scala numbers: no arithmetic.
  Convert with `time.Duration(nanos)` and `d.Nanoseconds()`.
- **cgo:** Go modules that need cgo (such as `mattn/go-sqlite3`) need a C compiler, and rule out
  `CGO_ENABLED=0` static builds unless they have a fallback without cgo (go-sqlite3 has a stub,
  which the [argus example](../examples/argus.md) can use). Facades of packages that use cgo
  directly, and stack traces through C calls, are untested.
  [Roadmap: Go interop](../contributing/design.md#4-go-interop).
- **Go versions:** Go modules can require a newer Go than scala-go's minimum (gin and argus need
  Go 1.25); Go's default `GOTOOLCHAIN=auto` downloads it.
- **`scalago-gostd`** has facades of 21 standard library packages; generate the others
  (`net/http`, `os/exec`, `regexp`, ...) with `goFacadePackages`.
  [Go standard library](../libraries/gostd.md).
- **Exports** (Go calling Scala): no exported traits, `val`s or fields, secondary constructors,
  tuple results, overloads with the same Go name or generic methods; one Scala library per Go
  binary. [Calling Scala from Go](../interop/exports.md#limitations).

## Tooling

- **Scala 3.9.0 only:** the compiler plugin and the standard library's IR are built for exactly
  that version. [Roadmap](../contributing/design.md#1-usable-outside-this-repository).
- **Mill only:** no sbt plugin, no scala-cli support.
  [Roadmap](../contributing/design.md#1-usable-outside-this-repository).
- **Not published:** build the artifacts with `./mill __.publishLocal` (see
  [Getting started](../getting-started.md)).
- **Tests run on the JVM only:** test frameworks do not run as Go. Test on the JVM and run the
  Go binary in CI as well. [Roadmap: tests as Go](../contributing/design.md#1-usable-outside-this-repository).
- **Build speed:** every link regenerates the whole Go module (about 1,000 files for a small
  program), and `go build` recompiles it: a change takes tens of seconds. Develop on the JVM
  (`mill app.run`) when the program does not call Go.
  [Roadmap: development loop](../contributing/design.md#1-usable-outside-this-repository).
- **Editors:** link errors appear in Mill's output, not in Metals. Delve debugs the generated
  code, but shows Scala values as Go structures. [Roadmap: tooling](../contributing/design.md#7-tooling).
- **Generating facades** needs `go` on the `PATH`, and network access the first time a
  `goRequires` module is used.
- **`goRun` has no terminal:** Mill runs it in its daemon, so terminal UIs fail there; run
  `goBuild`'s executable directly. [Build setup](build.md#tasks).

## Platforms

- **64-bit little-endian targets only** (amd64, arm64, ...): big-endian ones (s390x, ppc64, mips)
  are not supported, and 32-bit ones are untested. [Runtime: platform](../spec/runtime.md#platform).
- **Windows** builds, but file paths are Unix paths, there are no file locks, and the tests do not
  run there. **WebAssembly** (`wasip1`, `js`) is untested.
  [Roadmap: platforms](../contributing/design.md#5-platforms).
- Linux and macOS on amd64 and arm64 are tested. [Shipping binaries](shipping.md).
