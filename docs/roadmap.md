# Roadmap

Milestones run in order of what blocks adoption: wrong results first, then installability, then
the edit–test loop, then coverage. Each milestone has an exit criterion that a test or CI job
checks. Work inside a milestone is independent unless **Needs** says otherwise.

Sizes: **S** is a few hundred lines in one place. **M** is a new subsystem, or a port of a few
thousand lines. **L** is several subsystems.

| #  | Milestone                  | Exit criterion                                                                 |
|----|----------------------------|--------------------------------------------------------------------------------|
| 0  | [Correct](#m0-correct)     | No known deviation from the JVM except the [by-design ones](design.md#status) |
| 1  | [Released](#m1-released)   | An outside project builds from Maven Central artifacts in CI                   |
| 2  | [Fast loop](#m2-fast-loop) | Tests run as Go; a one-line change relinks and rebuilds in under 5 s           |
| 3  | [Common programs](#m3-common-programs) | A `javap` diff of javalib against the JDK lists no gap that common code hits |
| 4  | [Ecosystem](#m4-ecosystem) | cats-effect, fs2, circe and ZIO programs match the JVM in `examples/ecosystem` |
| 5  | [Go interop depth](#m5-go-interop-depth) | gin binds JSON into a Scala-declared Go struct              |
| 6  | [Performance](#m6-performance) | Benchmarks tracked in CI; no benchmark over 3× a warm JVM                  |
| 7  | [Platforms and tools](#m7-platforms-and-tools) | End-to-end tests pass on Linux, macOS and Windows; sbt plugin |

M0 and M2's development loop can run in parallel with M1, which waits mostly on
[decisions](#decisions).

## Decisions

These block work. Each names what waits on it.

| Decision | Options | Blocks |
|----------|---------|--------|
| Organization and license | `io.github.scalago` is a placeholder; no `LICENSE` file (the build declares Apache-2.0) | M1 publishing |
| Scala versions | 3.9 (LTS) only, or also the latest (3.10.0). Each version is a separate plugin and standard-library IR, since the plugin uses compiler internals | M1 |
| JDK version javalib claims | Its API follows JDK 21–25 in places. `java.version` and `java.specification.version` are unset, and libraries test them | M1 |
| Build tools | Mill only, or also sbt and scala-cli | M7 |
| Locale data | Everything formats as `Locale.ROOT`. Bundling CLDR (optional, as Scala.js's scala-java-locales) costs binary size | `java.text`, `String.format(Locale, …)` in M3 |
| Library overrides | Keep cats-effect and ZIO override modules here (as `scalago-gears`), or upstream a scala-go platform, as Scala.js and Scala Native did | M4 |

Design questions, decided by default unless changed:

| Question | Default | Alternative |
|----------|---------|-------------|
| Missing members | Link error; runtime `LinkageError` with `--missing-apis throw` | Make `throw` the default once javalib is broad: libraries' reachable code often names APIs programs never call |
| Facades on the JVM | Facade members throw, so Go-using programs are Go-only | An optional JVM implementation per facade |
| Errors in generated facades | A trailing `error` is thrown | Return `(T, GoError)` everywhere, or configure per member |
| `Array[T]` ↔ `[]T` | Shares memory when representations match, copies otherwise | Require `GoSlice` for Go slices |

## M0: Correct

Every item is a silent or surprising deviation from the JVM. The spec marks those it describes as
**Deviation (bug)**. The `known-bugs*` end-to-end cases pin each bug's current behavior, so a fix
makes its case fail: move the check to a case run on both the JVM and Go.

### Known bugs

All **S**. The `known-bugs*` case that pins each bug is named after its area: `known-bugs-lang`,
`-expr`, `-runtime`, `-jdk`, `-scala-lib`, `-interop`, and the original `known-bugs`. Cases whose
name ends in `-go-errors`, `-links` or the like expect `go vet`/`go build` or the linker to fail.

#### Code the Go compiler rejects

| Bug | Fix in |
|-----|--------|
| `try throw e finally return x`, `try throw a finally throw b`, and a `try` around an inlined `throw` passed as a by-name argument emit code `go vet` rejects (unreachable code) | `FunctionEmitter` |
| A `throw` typed as a primitive and boxed (`def h(): Any = (throw e): Int`, also `boundary[Int] { throw e }` used as `Any`) emits `any()` with no argument | `FunctionEmitter` |
| A `throw` as an argument followed by other arguments (`pair(throw e, 1)`) emits a Go syntax error | `FunctionEmitter` |
| Constant arguments to natives make Go reject constant expressions: `Integer.sum(Int.MaxValue, 1)`, `Long.sum` (overflow), `Integer.divideUnsigned(1, 0)`, `Long.remainderUnsigned(1L, 0L)` (division by zero), `Character.isISOControl(-1)`, `Float.intBitsToFloat(0xff800000)`, `Double.longBitsToDouble(0x8000000000000000L)` (constant overflows `uint32`/`uint64`) | Natives emitted as Go expressions: convert through variables, not constants |
| A string literal containing `math.` (`println("math.abs")`) adds an unused `import "math"` to `support.go` | `Linker.goFile`: scan code, not literals |
| A class named `Entry` in the empty package collides with the linker's `entry.go` (Go rejects file names that differ only in case); `support.go` is reserved the same way | `Linker`: reserve the names |
| A facade of a pointer-receiver method on a non-struct Go type links, then fails `go build` | `GoInterop`: reject at link time, or copy to an addressable variable |
| A generic facade whose Scala types disagree with Go's inference (`f: Long => GoString` for `func(T) U` with `T = int`) links, then fails `go vet` | `GoInterop`: a link error |
| A Scala subclass of `BigInteger` or `BigDecimal` links, then fails `go build` (`cannot use this (variable of type *S_…) as *rt.BigInteger`) | Linker: a link error naming the class |
| Two methods exported under one Go name (`@GoExport("Twice")` on overloads) link, then fail `go build` (`Export_Twice redeclared`) | `Exports`: a link error |

#### Language

| Bug | Fix in |
|-----|--------|
| `a * b + c` on `Double` fuses into one FMA on arm64 (and amd64 `GOAMD64=v3`): one rounding instead of two | `FunctionEmitter`: wrap products in explicit `float64(…)`/`float32(…)` |
| `i + inc()`, where `inc` changes a captured `var i`, reads `i` after the call | Go leaves operand evaluation order unspecified: spill operands that read mutable fields |
| A boxed `Int` or `Long` `==` a boxed `Float` (`val l: Any = 16777217L; val f: Any = 16777216f; l == f`) is `false` (JVM: `true`, compared as `Float`). Literal operands are folded correctly | `rt.Equals` |
| `(1: Any) == BigInt(1)` is `false` (JVM: `true` via `ScalaNumber`) | `rt.Equals`: `ScalaNumber` on the right |
| `==` with a null `String` throws `NullPointerException` (JVM: `false`); string matches on a null selector throw | `genArithmetic`: `UnivEq` operands reach `rt.Equals` as a nil `*rt.String` in an `any`. Check `BigInteger`/`BigDecimal` too |
| `().##` and `().hashCode` are identity hashes (JVM: 0) | `rt`: hash of the unit value |
| `eq` on Go slices, maps or funcs panics | `refEq` emits `any(a) == any(b)` on uncomparable types |
| A Go struct with a slice, map or func field is not `==` to itself; an interface field holding an uncomparable value makes `==` throw | `rt.foreignEquals` |
| `asInstanceOf[Integer]` of a `String` (any pointer-typed operand to a box class) does not throw | `LinkContext.castTo`: pointer source, `any` target |
| A cast to a class never instantiated, or to a trait no instantiated class implements, lets any value through when the operand is typed `Any` (JVM: `ClassCastException`) | `LinkContext.castTo`: such casts pass only `null` |
| A user-defined `given TypeTest` used by `case x: T` does not link (it reaches `ClassTag.unapply`, which needs `Class.isInstance`) | Follows `Class.isInstance` ([Silent wrong output](#silent-wrong-output)) |
| On a Java-style Scala enum, `getDeclaringClass` and `java.lang.Enum.valueOf(classOf[E], name)` do not link | javalib `Enum` |
| The stack trace line of a `throw` inside an `inline` method is the inlined source's line (JVM: the call site's) | Plugin: positions of inlined trees |
| `getSimpleName` of a local class is `""`; of an operator-named class keeps only the text after the last `$` (`List(1).getClass.getSimpleName` is `colon`, JVM `$colon$colon`) | `rt.ClassGetSimpleName` |
| `Array(Array(1, 2), Array(3, 4)).transpose` throws `NullPointerException` (reference arrays do not know their element type) | `ClassTag` arrays of arrays |
| A negative array index throws `RuntimeException`, not `ArrayIndexOutOfBoundsException` | `rt.Caught`: gc's message for negative indices has no length |
| `@GoExport` on a `val` or a constructor field is ignored, without an error | Plugin: reject, as for traits |
| A tuple result of an export is rejected with a misleading message (`cannot instantiate a generic Go function with Scala type scala.Tuple2[…]`) | `Exports`: say that tuple results are not supported |

#### Runtime and threads

| Bug | Fix in |
|-----|--------|
| Uncaught exceptions in `main` skip `Thread.setDefaultUncaughtExceptionHandler` | `rt.RunMain` |
| An overridden `ThreadGroup.uncaughtException` is never called; with a default handler set, `getUncaughtExceptionHandler` returns it (JVM: the group); a default handler that throws ends the program with a Go panic, exit 2 (JVM: prints the exception, continues) | javalib `Thread.uncaught` |
| `getState` is `RUNNABLE` in `sleep`, `wait`, `join` and while blocked on a monitor (JVM: `TIMED_WAITING`, `WAITING`, `BLOCKED`); only `LockSupport` parking is reported | javalib `Thread` |
| The main `Thread` stays alive after `main` returns or throws: `isAlive` is `true`, and `join()` on it never returns | `rt.RunMain` |
| A new thread's priority is 5, not its creator's; a `ThreadGroup`'s maximum priority does not cap it | javalib `Thread` |
| `NonFatal(StackOverflowError())` and `NonFatal(OutOfMemoryError())` are `true`: no `VirtualMachineError` | javalib |
| A throwing object initializer gives no `ExceptionInInitializerError`; the later `NoClassDefFoundError` has no cause | javalib, module initialization |
| `initCause` on an exception that already has a cause overwrites it (JVM: `IllegalStateException`) | javalib `Throwable` |
| `System.gc()` is a link error (`Runtime.gc()` works) | javalib |
| `classOf[Array[Int]].getTypeName` is `[I` (JVM: `int[]`); `toString` of an interface's `Class` says `class` (JVM: `interface`) | `rt` class natives |

#### JDK and Scala library

| Bug | Fix in |
|-----|--------|
| `Math.round(0.49999999999999994)` is 1 (JVM: 0); large odd doubles round off by one | `rt.MathRound`: not `floor(d + 0.5)` |
| `Math.pow(1, NaN)` and `Math.pow(-1, Infinity)` are 1.0 (JVM: NaN). `Math.pow(10, 308)` is `1.0000000000000006E308`, beyond the last-bit allowance. `StrictMath.cbrt(0.5)` is not fdlibm's result | `rt` math natives: Java's special cases; fdlibm for `StrictMath` |
| `scala.math.BigDecimal(1.5)` and `BigDecimal("1.5") == 1.5` throw `ClassCastException` (`*S_java_dmath_dMathContext is not interface { M_goBits_p_rJ() int64 }`) | javalib `BigDecimal` and its `MathContext` |
| `BigInteger.toByteArray` of negative powers of two has an extra byte (-128 gives `ff 80`); radix 1 throws `RuntimeException` and radix 37 parses (JVM: `NumberFormatException`); `new BigInteger(signum, magnitude)` accepts a signum inconsistent with the magnitude | javalib `BigInteger` |
| `Double.doubleToRawLongBits(Double.NaN)` is `0x7ff8000000000001` (Go's `math.NaN()`; JVM: `0x7ff8000000000000`) | `rt`: the canonical NaN |
| `parseDouble` rejects `"2.5F"` and `"-NaN"`, and accepts `"1_000"`, `"infinity"`, `"nan"` and a leading U+00A0; `parseFloat` rounds through `double` (`"1.00000017881393432617187499"` gives `1.0000002`) | javalib `Double`/`Float` parsing |
| `Integer.toString(i, radix)` and `Long.toString` with a radix out of range throw (JVM: radix 10); `parseInt` of non-ASCII digits (`"١٢٣"`) throws | javalib `Integer`/`Long` |
| `Character.digit` of non-ASCII digits is -1, and accepts radixes out of range; `isUpperCase`/`isLowerCase` ignore Other_Uppercase/Other_Lowercase (U+2160, `ª`); `isAlphabetic` is `isLetter` | javalib `Character` |
| `" ".isBlank` is `true` (JVM: `false`); `"abc".indexOf("", 20)` is -1 (JVM: 3); `intern()` of a computed string is not `eq` to the equal literal | javalib `String` |
| `lastIndexOf` of a supplementary code point is -1; `indexOf(0x110000)` finds U+FFFD | `rt` string search natives |
| `replaceAll("a*", "X")` on `"aaa"` gives `X` (JVM: `XX`); a replacement naming a missing group (`$2`) is ignored (JVM: `IndexOutOfBoundsException`); an invalid pattern (`"("`) throws `UnsupportedOperationException` (JVM: `PatternSyntaxException`) | javalib `regex` on Go's `regexp` |
| UTF-8 bytes of a surrogate (`ED A0 80`) decode to three U+FFFD (JVM: one), in `new String` and in `CharsetDecoder` | `rt` UTF-8 decoder |
| `StringBuilder.append(cs, 2, 1)` does nothing (JVM: `IndexOutOfBoundsException`); `appendCodePoint(-1)` appends U+FFFF (JVM: `IllegalArgumentException`); `append(chars, 1, 5)` throws `ArrayIndexOutOfBoundsException`, not `IndexOutOfBoundsException` | javalib `StringBuilder` |
| `StringBuffer` is not synchronized: concurrent appends lose characters or throw. Not pinned (the result is random) | javalib `StringBuffer` |
| Exception messages are JDK 8's or Go's: index errors (`index 5, length 3`, JVM `Index 5 out of bounds for length 3`), `parseInt` (no `under radix N`, radix and null messages), `parseDouble` (`empty String`, `multiple points`), `BigInteger` parsing, `Thread.sleep(-1)`, `Thread.Builder.priority` | javalib: JDK 17+'s messages |
| String charsets other than the supported three throw `UnsupportedOperationException`, not `UnsupportedEncodingException`/`UnsupportedCharsetException` | javalib charsets |

#### Go interop

| Bug | Fix in |
|-----|--------|
| A `GoSlice`/`GoMap` passed for a defined Go slice or map type (`type Names []string`) is a link error (`cannot pass Scala type … as Go type …`), though the spec converts it | `GoInterop.toGoNormalized`: the Go-data case before `GoType.Named` |
| A Scala number passed for a `time.Duration` parameter is a link error (`unknown Go type gp_time.Duration`) unless some facade imports `time` | `GoInterop`: import the named type's package |
| A Scala `String` is an instance of a Go interface it does not implement (`("s": Any).isInstanceOf[fmt.Stringer]` is `true`; JVM and Go code: `false`) | `rt` type tests on `*rt.String` |
| `GoType.of[Any].name` is `interface {}` on Go, `any` on the JVM (also inside composite types) | `GoType` natives: Go's `any` spelling |

### Silent wrong output

| Item | Problem | Size |
|------|---------|------|
| `String.format`, `f"…"` | `rt.StringFormat` maps onto Go's `fmt`. Wrong: `%x`/`%o` of negatives (`-1`, not `ffffffff`), `%g`, `BigInt`/`BigInteger` arguments (a Go struct dump), `%(d`. Throws: `%1$s`, `%<s`, the `,` flag, `%h`, `%t`, `%a`, `BigDecimal` with `%f`, as an `IllegalArgumentException` whose message names the JDK's exception. `java.util.Formatter` and `String.formatted` are missing. Port Scala.js's or Scala Native's `Formatter` to javalib | M |
| Signals and fatal errors | SIGINT, SIGTERM and SIGHUP exit without shutdown hooks and without flushing standard output (up to 32 KB lost). Go's fatal errors (stack overflow, out of memory, concurrent map writes, deadlock) lose it too. Fix: an `os/signal` handler that runs hooks and exits 128+signal; flush at line ends on a terminal and on a timer otherwise | S |
| `Class` type tests | `Class.isInstance`/`cast` are missing, so `ClassTag` patterns (`case x: T`) throw `NoSuchMethodError`. `Class.forName` returns a `Class` for any name, so `Try(Class.forName(…))` probes conclude wrongly. Also missing: `getSuperclass`, `getInterfaces`, `isInterface`, `isEnum`, `getEnumConstants`, `getCanonicalName`. Build on the class hierarchy the linker already emits for `isAssignableFrom` | S |

## M1: Released

| Item | Work | Size | Needs |
|------|------|------|-------|
| Publishing | Real organization; `LICENSE`; POM developers and SCM; signing; a tag-triggered release workflow to Maven Central | S | organization, license |
| Version policy | Plugin and stdlib IR per supported Scala version; CI matrix; `java.version`/`java.specification.version` set; `go` directive at the chosen minimum | M | Scala, Go, JDK decisions |
| Changelog and compatibility | `CHANGELOG.md`; IR format version tied to the release; what a patch release may change | S | — |

## M2: Fast loop

| Item | Work | Size | Needs |
|------|------|------|-------|
| Incremental linking | Each link starts a JVM, reads all IR and rewrites the whole Go module: about 1,000 files and 100,000 lines for a small program, so a one-line change costs ~18 s of `go build`. Keep the linker in a Mill worker (as Scala.js does); rewrite only changed files | M | — |
| Precompiled Go packages | Emit javalib and the standard library as separate Go packages, cached across programs. Needs a cycle-free package split of the generated code | L | incremental linking |
| Reflective instantiation | Create objects and modules by class name. The linker emits constructors of classes marked or listed for it (as Scala.js) | M | — |
| Tests as Go | `sbt.testing` in Scala (as Scala.js and Native); overrides of munit's platform classes; a utest runner; a Mill `ScalaGoTests` trait linking one binary for all suites | L | reflective instantiation |
| Link errors in editors | Report link errors as BSP diagnostics so Metals shows them | S–M | — |

## M3: Common programs

Start with a report that diffs javalib and natives against the JDK's API (`javap`). It turns the
`java.lang` row into a checklist and finds gaps before users do.

| Item | Missing | Size | Needs |
|------|---------|------|-------|
| API diff report | Tool comparing javalib's public API with JDK 21's, per class | S | — |
| `java.lang` | `String`: `formatted`, `strip{Leading,Trailing,Indent}`, `indent`, `translateEscapes`, `codePointCount`, `contentEquals`. `Character`: `getType`, `toTitleCase`, `toChars`, `getNumericValue`, `isValidCodePoint`, `UnicodeBlock`, `UnicodeScript`. `Math`: `copySign`, `fma`, `multiplyHigh`, `nextAfter`, `IEEEremainder`, `scalb`, `getExponent`, `ceilDiv`. `parseUnsigned*`, `System.console`, `System.gc`, `System.runFinalization`, `Thread.getStackTrace`, `getContextClassLoader` (breaks `Source.fromResource`), `IntStream.parallel`, `Math.ulp`/`nextAfter` (break `scala.math.ulp`, `Random.between`), `Class.cast`/`isInstance` (break `Future.mapTo`, `TypeTest`s). The rest is pinned by `limits-jdk-gaps` and `limits-jdk-missing-expr` | S each | — |
| `java.util` | `Scanner`, `EnumSet`/`EnumMap`, `Calendar`, `Timer`, `Currency`, `ServiceLoader` (resolve `META-INF/services` at link time), `ResourceBundle`, `Stream.gather`, concurrent collectors, reversed sequenced-map views, `Arrays.compare`/`mismatch`, multi-line `Properties` values | M | `getEnumConstants`, reflective instantiation |
| Classpath resources | `getResourceAsStream` returns null. The linker embeds resources selected by build patterns | S–M | — |
| `java.util.logging` | `Logger`, `Handler`, `Level`, `LogRecord`, `LogManager` (scribe, cask, ZIO use it) | S–M | — |
| `TrieMap`, field updaters | `TrieMap`'s node classes are Java sources without TASTy: override them in javalib. Atomic field updaters become intrinsics when the field name is a literal, as `VarHandle`s are | S–M | — |
| `java.text` | `NumberFormat`, `DecimalFormat`, `SimpleDateFormat`, `MessageFormat`, `Normalizer`, `Collator`, `BreakIterator` | M | locale data |
| Charsets | UTF-16 (BE, LE), Windows and ISO single-byte charsets | S | — |
| Weak references | `WeakReference`, `SoftReference`, `WeakHashMap` hold strongly; `ReferenceQueue` has no methods (`poll`, `remove`); no `PhantomReference`, `Cleaner`; the monitor table never shrinks. Long-running caches leak | M | Go ≥ 1.24 |
| Structural types | `reflectiveSelectable` needs `getMethod`. Compile calls by name and signature through generated dispatchers, as Scala.js | M | — |
| Archives | ZIP64 writing, encrypted entries, `JarFile`, `JarInputStream` | S–M | — |

## M4: Ecosystem

From scans of popular libraries' JVM jars against javalib. Each library gets an
`examples/ecosystem` case compared with the JVM.

| Library | Blockers | Approach | Size | Needs |
|---------|----------|----------|------|-------|
| circe, zio-json | `java.util.Currency` | `Currency` in javalib | S | — |
| jsoniter-scala | `ByteArrayAccess` (Java, on `VarHandle`s) | natives | S | — |
| scribe | `java.util.logging` | — | S | M3 logging |
| scopt | `Calendar`, `SimpleDateFormat` | — | M | M3 `java.text` |
| cats-effect 3 → fs2-core | Java classes (`IOFiberConstants`, padding classes, `Signal` on `sun.misc.Signal`), field updaters, JMX fiber dumps | Override module like `scalago-gears`: constants, signals on `os/signal`, a runtime on goroutines | M–L | field updaters, override decision |
| ZIO 2 | Java padding classes, field updaters, JMX, `sun.management`, `java.util.logging` | Override module | M–L | same |
| TLS and crypto | `SSLContext`, key and trust managers, `KeyStore`, X.509, `HttpsURLConnection`, `Cipher`, `Signature`, `KeyFactory`, `KeyPairGenerator`, SHA-3, seeded `SecureRandom` — all on Go's `crypto` | L | — |
| fs2-io, requests-scala | NIO socket and async channels, selectors, `SSLEngine`, UDP, watch services, `HttpCookie`, `ProxySelector`, HTTP/2, WebSockets | On Go's `net` and `crypto/tls` | L | TLS |
| munit, utest | — | M2 "Tests as Go" | — | — |

Not planned: ScalaTest (reflection throughout, `java.awt`), and cask, http4s-ember, Pekko/Akka
(Java servers or JVM runtimes). HTTP servers come from Go (gin, `net/http`).

## M5: Go interop depth

| Item | Missing | Size |
|------|---------|------|
| Go structs from Scala | Go sees Scala classes only as interfaces. No Scala declaration yields a Go struct with fields and tags, which `encoding/json`, gin binding and database scanners read | M–L |
| Generics | Generic Go *types*; functions constrained by type sets (most of `slices`, `maps`, `cmp`) are left out of generated facades; `GoType` instances for facade types | M |
| `select` | Over several Go channels, from Scala and from gears | S–M |
| Data shapes | `GoSlice`/`GoMap` facades of named slice and map types, unnamed struct and interface types, funcs in Go slices and maps, arrays, complex numbers, variadic callbacks, returned Go funcs with more than 3 parameters | M |
| Exports | Go implementing Scala traits, vals and fields, secondary constructors, tuple results, overloads sharing a Go name, generic methods | M |
| `scalago-gostd` | 21 packages. Add `net/http`, `os/exec`, `regexp`, `crypto/*`, `log/slog`, `database/sql`, `encoding/csv`, `slices`, `maps` | S, after generics |
| cgo | Test facades of cgo packages (SQLite) and stack traces after C calls | S |

## M6: Performance

Measure first: today, against a warm JVM, allocation-heavy recursion and primitive streams are
10–13× slower; boxed streams, immutable collections and `java.time` arithmetic 4–7×;
`DateTimeFormatter` parsing ~25×. Virtual calls and exceptions are faster. An 80-line program
links to ~100,000 lines of Go and a 19 MB binary.

| Item | Work | Size |
|------|------|------|
| Benchmarks in CI | Run `bench/` per commit; track regressions | S |
| PGO | Go's `default.pgo` applies to generated code unchanged: measure and document | S |
| Boxing | Generic code boxes primitives into `any` (an allocation beyond Go's small-integer cache); volatile reference stores allocate a box | M |
| Code size | Reachability resolves virtual calls by name, keeping unused methods. Use class-hierarchy analysis on instantiated classes | M |
| Object representation | References are Go interfaces: two words, itab calls Go cannot inline. Struct pointers with vtables, or whole-program devirtualization | L |

## M7: Platforms and tools

| Item | Missing | Size | Needs |
|------|---------|------|-------|
| CI coverage | End-to-end tests on Linux amd64 and macOS arm64 only; Linux arm64 runs the runtime's unit tests; Windows only `go vet` | S | — |
| Windows | Drive letters and `\` in paths; file locks | M | CI coverage |
| WebAssembly | `GOOS=wasip1` and `js` untested; file, network and process layers need build tags | S–M | — |
| 32-bit targets | Untested (big-endian targets are unsupported by design) | S | CI coverage |
| Debugging | Delve works (Scala lines via line directives, locals `l_<name>`) but shows Scala values as Go structs. Document the setup; add pretty-printers | S–M | — |
| sbt plugin, scala-cli | Only Mill; `scalago.linker.Main` is the CLI to wrap | M each | build-tool decision, M1 |
| Unicode 16 | Character data follows Go (Unicode 15, as JDK 21) | S, follows Go | — |
