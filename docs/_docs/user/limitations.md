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
- **Type tests with a `ClassTag`** (`case x: T` given a `ClassTag[T]`) do not link:
  `Class.isInstance` and `Class.cast` are missing (with `--missing-apis throw`, they throw
  `NoSuchMethodError`). User-defined `TypeTest` givens used in patterns fail the same way. Match
  on concrete types. Also missing: `getSuperclass`, `getInterfaces`, `isInterface`, `isEnum`,
  `getEnumConstants`, `getCanonicalName`, `Enum.valueOf(Class, String)` and `getDeclaringClass`
  of Java-style enums.
- **Structural types** (`reflectiveSelectable`) do not link. Use a trait.
- **Classpath resources** are not embedded: `getResourceAsStream` returns `null`, and
  `Source.fromResource` does not link. Read files, or embed data in code.
- **No Go counterpart:** Java serialization, JMX (`java.lang.management`), `SecurityManager`,
  `System.loadLibrary` and method handles do not link.

## JDK and Scala libraries

- **Missing JDK classes** include `Scanner`, `java.util.Formatter`, `EnumSet`/`EnumMap`,
  `Calendar`, `Timer`, `Currency`, `ServiceLoader`, `ResourceBundle`, `java.util.logging`,
  `java.text`, `java.util.jar`, TLS configuration and ciphers, UDP and NIO socket channels,
  `PhantomReference` and `Cleaner`. Use another API, or call Go through a
  [facade](../interop/facades.md). [The JDK](../libraries/jdk.md).
- **Missing methods** of present classes include:
  - `String`: `formatted`, `strip{Leading,Trailing}`, `indent`, `codePointBefore`,
    `offsetByCodePoints`, `splitWithDelimiters`, `transform`, constructors from `int[]`,
    `StringBuilder` and `StringBuffer`. `StringBuilder`: the `codePoint*` methods, `repeat`.
    `StringBuffer` has few methods beyond `append` and `toString`.
  - `Character`: `getType`, `isTitleCase`, `isDefined`, `toString(int)`, `highSurrogate`,
    `lowSurrogate`, `isBmpCodePoint`, `isSupplementaryCodePoint`, `codePointAt`/`Count`.
  - Boxes: `Integer.valueOf(s, radix)`, `decode`, `getInteger`, `parseUnsignedInt`,
    `toUnsignedString(i, radix)`; `Long.compareUnsigned`; `Short.parseShort`, `Byte.parseByte`;
    `Double.toHexString`; `Boolean.valueOf(String)`, `logicalAnd`/`Or`/`Xor`.
  - `Math`: `fma`, `copySign`, `ulp`, `clamp` of `float`/`double`, `absExact`, `ceilMod`,
    `multiplyFull`, `nextUp(float)`, `floorMod(long, int)`. `BigInteger`: `sqrt`, `*ValueExact`,
    `probablePrime`, `nextProbablePrime`, the random constructor.
  - `System.gc` (`Runtime.gc()` works), `System.runFinalization`, `Thread.getStackTrace`,
    `ReferenceQueue.poll`, `reflect.Array.getInt`/`setInt` (and the other typed accessors),
    `ServerSocketChannel.open`.

  The full list is in the `limits-jdk-gaps`, `limits-jdk-missing` and `limits-jdk-missing-expr`
  test cases; the [roadmap](../contributing/roadmap.md#m3-common-programs) orders them.
- **Scala library methods on a missing JDK API** do not link: `Future.mapTo`,
  `PartialFunction.elementWise` (`Class.cast`/`isInstance`), `Random.between(Double, Double)`
  (`Math.nextAfter`), `math.ulp`, `BigInt.probablePrime`, `StdIn.readf*` (`java.text`).
- **`String.format` and `f"..."`** throw on argument indices (`%1$s`, `%<s`), the `,` flag,
  `%h`, `%t`, `%a`, and `BigDecimal` with `%f`. The exception is an
  `IllegalArgumentException` naming the JDK's (`UnknownFormatConversionException: ...`), not
  the JDK's subclass. Other output differences: [Differences](differences.md#text).
- **Regular expressions** are Go's RE2: no lookaround, backreferences, possessive quantifiers or
  atomic groups. Such a pattern throws `UnsupportedOperationException` when compiled.
- **Charsets:** UTF-8, ISO-8859-1 and US-ASCII only. Others throw
  `UnsupportedOperationException` (not `UnsupportedEncodingException`), and
  `Charset.isSupported` is `false`. [Runtime: locales](../spec/runtime.md#locales-text-and-charsets).
- **HTTP client:** no proxies, cookies or authenticators (`HttpClient.Builder.proxy`,
  `cookieHandler`, `authenticator`, `CookieManager` do not link).
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
- **Parallel streams** run sequentially; `parallel()` of primitive streams (`IntStream`) does
  not link.
- **Missing:** `SubmissionPublisher`, `Thread.getAllStackTraces`, `ThreadGroup.activeCount` and
  `ThreadGroup.enumerate`.
- **`StringBuffer` is not synchronized** (a [known bug](../contributing/roadmap.md#known-bugs)):
  guard a shared one with a lock, or give each thread its own `StringBuilder`.

## Go interop

- **Programs that call Go run only as Go:** facade members throw on the JVM. Put Go calls behind
  a trait of your own to test the rest on the JVM. [Calling Go](../interop/facades.md#on-the-jvm).
- **No Go struct types from Scala:** Go sees a Scala class only through Go interfaces, never as a
  struct with fields and struct tags. Go code that binds or scans into structs (`encoding/json`,
  gin's `ShouldBindJSON`, argus's `Bind[T]`, database scanners) cannot fill a Scala class. Bind
  into `any` and convert with [`GoData.toScala`](../interop/go-data.md#scala-data-for-go-code), or
  parse with a Scala library ([gin example](../examples/gin.md)).
- **Generic Go code** ([Generics](../interop/facades.md#generics)):
  - Scala does not check Go's constraints: `slices.Max` of a `GoSlice[Any]` links, then fails
    `go build` ([known bug](../contributing/roadmap.md#known-bugs)).
  - A type argument that no parameter determines must be given (`reflect.TypeFor[GoInt]()`);
    omitted, it is a compile error.
  - `GoSlice.of(...)` in an argument of a generic member needs its element type
    (`slices.Max(GoSlice.of[GoInt](...))`), otherwise its `GoType` is ambiguous.
  - Type tests against generic facade types are link errors, and casts to them are unchecked:
    their type arguments are erased.
  - A struct facade as a type argument stands for the pointer type: `atomic.Pointer[bytes.Buffer]`
    is Go's `atomic.Pointer[*bytes.Buffer]`.
- **Values of types Scala cannot name** (unexported, or of `internal` packages:
  `binary.BigEndian`, `json/v2` options) are typed `Any` or as the interface they implement with
  the most methods; reach their other interfaces with a cast
  (`binary.BigEndian.asInstanceOf[binary.AppendByteOrder]`). Calls taking them go through Go's
  reflection: slower, and an argument of the wrong type is a `ClassCastException` at run time,
  not a compile error. Their fields are read-only.
- **Go data shapes left out of generated facades:**
  - unnamed struct and interface types other than `struct{}`;
  - arrays inside Go data (`[][32]byte`), `complex64` inside Go data, `unsafe.Pointer` (not
    planned);
  - methods with pointer receivers of non-struct types (`net.IP.UnmarshalText`);
  - funcs inside Go slices and maps (struct fields of func type are generated), variadic func
    types;
  - Go funcs with more than 3 parameters returned to Scala;
  - integer constants beyond `uint64`.

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
- **`scalago-gostd`** has the whole standard library, but leaves out about 40 of its 16,700
  members (the shapes above). It is generated from Linux/amd64's API: on other platforms, a
  member they lack (in `syscall`, parts of `os` and `net`) is a Go compile error where it is
  used. `import scalago.gostd.*` hides Scala's `io`, `math`, `reflect` and `runtime`.
  [Go standard library](../libraries/gostd.md#limitations).
- **Exports** (Go calling Scala): no exported traits, `val`s or fields, secondary constructors,
  tuple results, overloads with the same Go name or generic methods; one Scala library per Go
  binary. Most are compile or link errors; `@GoExport` on a `val` or field is ignored, and
  overloads exported under one Go name give Go code that does not compile
  ([known bugs](../contributing/roadmap.md#known-bugs)).
  [Calling Scala from Go](../interop/exports.md#limitations).

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
