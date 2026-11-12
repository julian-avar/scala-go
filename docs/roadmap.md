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

Every item is a silent or surprising deviation from the JVM. The spec marks each as
**Deviation (bug)**. Each fix adds an end-to-end case run on both the JVM and Go.

### Known bugs

All **S**.

| Bug | Fix in |
|-----|--------|
| `a * b + c` on `Double` fuses into one FMA on arm64 (and amd64 `GOAMD64=v3`): one rounding instead of two | `FunctionEmitter`: wrap products in explicit `float64(…)`/`float32(…)` |
| `Math.round(0.49999999999999994)` is 1 (JVM: 0); large odd doubles round off by one | `rt.MathRound`: not `floor(d + 0.5)` |
| `(16777217L: Any) == (16777216f: Any)` is `false` (JVM: `true`, compared as `Float`) | `rt.Equals` |
| `(1: Any) == BigInt(1)` is `false` (JVM: `true` via `ScalaNumber`) | `rt.Equals`: `ScalaNumber` on the right |
| `==` with a null `String` throws `NullPointerException` (JVM: `false`); string matches on a null selector throw | `genArithmetic`: `UnivEq` operands reach `rt.Equals` as a nil `*rt.String` in an `any`. Check `BigInteger`/`BigDecimal` too |
| `eq` on Go slices, maps or funcs panics | `refEq` emits `any(a) == any(b)` on uncomparable types |
| A Go struct with a slice, map or func field is not `==` to itself; an interface field holding an uncomparable value makes `==` throw | `rt.foreignEquals` |
| `asInstanceOf[Integer]` of a `String` (any pointer-typed operand to a box class) does not throw | `LinkContext.castTo`: pointer source, `any` target |
| `i + inc()`, where `inc` changes a captured `var i`, reads `i` after the call | Go leaves operand evaluation order unspecified: spill operands that read mutable fields |
| A negative array index throws `RuntimeException`, not `ArrayIndexOutOfBoundsException` | `rt.Caught`: gc's message for negative indices has no length |
| `lastIndexOf` of a supplementary code point is -1; `indexOf(0x110000)` finds U+FFFD | `rt` string search natives |
| Uncaught exceptions in `main` skip `Thread.setDefaultUncaughtExceptionHandler` | `rt.RunMain` |
| `NonFatal(StackOverflowError())` is `true`: no `VirtualMachineError` | javalib |
| A throwing object initializer gives no `ExceptionInInitializerError`; the later `NoClassDefFoundError` has no cause | javalib, module initialization |
| `System.gc()` is a link error (`Runtime.gc()` works) | javalib |
| `getSimpleName` of a local class is `""` | `rt.ClassGetSimpleName` |
| `try throw e finally return x` emits code `go vet` rejects (unreachable code) | `FunctionEmitter` |
| A facade of a pointer-receiver method on a non-struct Go type links, then fails `go build` | `GoInterop`: reject at link time, or copy to an addressable variable |

### Silent wrong output

| Item | Problem | Size |
|------|---------|------|
| `String.format`, `f"…"` | `rt.StringFormat` maps onto Go's `fmt`. Wrong: `%x`/`%o` of negatives (`-1`, not `ffffffff`), `%g`, `BigInt`/`BigInteger` arguments (a Go struct dump), `%(d`. Throws: `%1$s`, `%<s`, the `,` flag, `%h`, `%t`, `%a`, `BigDecimal` with `%f`. `java.util.Formatter` and `String.formatted` are missing. Port Scala.js's or Scala Native's `Formatter` to javalib | M |
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
| `java.lang` | `String`: `formatted`, `strip{Leading,Trailing,Indent}`, `indent`, `translateEscapes`, `codePointCount`, `contentEquals`. `Character`: `getType`, `toTitleCase`, `toChars`, `getNumericValue`, `isValidCodePoint`, `UnicodeBlock`, `UnicodeScript`. `Math`: `copySign`, `fma`, `multiplyHigh`, `nextAfter`, `IEEEremainder`, `scalb`, `getExponent`, `ceilDiv`. `parseUnsigned*`, `System.console`, `Thread.getStackTrace`, `getContextClassLoader` (breaks `Source.fromResource`) | S each | — |
| `java.util` | `Scanner`, `EnumSet`/`EnumMap`, `Calendar`, `Timer`, `Currency`, `ServiceLoader` (resolve `META-INF/services` at link time), `ResourceBundle`, `Stream.gather`, concurrent collectors, reversed sequenced-map views, `Arrays.compare`/`mismatch`, multi-line `Properties` values | M | `getEnumConstants`, reflective instantiation |
| Classpath resources | `getResourceAsStream` returns null. The linker embeds resources selected by build patterns | S–M | — |
| `java.util.logging` | `Logger`, `Handler`, `Level`, `LogRecord`, `LogManager` (scribe, cask, ZIO use it) | S–M | — |
| `TrieMap`, field updaters | `TrieMap`'s node classes are Java sources without TASTy: override them in javalib. Atomic field updaters become intrinsics when the field name is a literal, as `VarHandle`s are | S–M | — |
| `java.text` | `NumberFormat`, `DecimalFormat`, `SimpleDateFormat`, `MessageFormat`, `Normalizer`, `Collator`, `BreakIterator` | M | locale data |
| Charsets | UTF-16 (BE, LE), Windows and ISO single-byte charsets | S | — |
| Weak references | `WeakReference`, `SoftReference`, `WeakHashMap` hold strongly; `ReferenceQueue` never enqueues; no `Cleaner`; the monitor table never shrinks. Long-running caches leak | M | Go ≥ 1.24 |
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
| Data shapes | `GoSlice`/`GoMap` facades of named slice and map types, unnamed struct and interface types, funcs in Go data, arrays, complex numbers, variadic callbacks, returned Go funcs with more than 3 parameters | M |
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
