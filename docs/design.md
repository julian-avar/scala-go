# scala-go design

Compiles Scala 3 programs to Go, following the architecture of Scala.js and Scala Native.

## Pipeline

```
 .scala ──scalac + plugin──▶ .class + .tasty + .goir        (per module, incremental)

 scala-library.jar (TASTy) ──scalac -from-tasty + plugin──▶ stdlib .goir     (built once)
 javalib/ (Scala sources)  ──scalac + plugin─────────────▶ java.* .goir      (built once)

            all .goir on the classpath (program + javalib + stdlib)
                                     ▼
          linker: lambdas → classes, reachability, Go codegen ──▶ Go module ──go build──▶ binary
```

1. **Compiler plugin** (`plugin/`). Runs as phase `genGoIR`, after the last tree transform
   (`repeatableAnnotations`) and before `genBCode`. Trees are erased, pattern matching is lowered
   to labeled blocks, lambdas are lifted, classes are flattened and mixins expanded, so the plugin
   handles a small Java-like core. Each class becomes one `.goir` file next to its `.class` file.
   Options:
   - `-P:scala-go:lenient`: untranslatable code becomes `Unsupported` IR instead of a compile
     error. Linking fails only if that code is reachable.
   - `-P:scala-go:rename:a.b:c.d`: emits classes of package `a.b` as `c.d` (see javalib).
2. **IR** (`ir/`). Typed, erased trees. Methods are identified by name plus erased signature, as
   on the JVM. Binary format with a string table and a format version. The plugin jar compiles
   these sources in, so `-Xplugin:<jar>` is self-contained.
3. **Standard library IR** (`stdlib`). Since 3.8 the Scala library ships TASTy, so it is
   recompiled from TASTy with the plugin in lenient mode, stopping after `genGoIR`. This takes
   about 20 s and produces about 2,800 classes. `scala.quoted` is skipped, because the compiler
   cannot recompile it from TASTy.
4. **javalib** (`javalib/`). JDK classes written in Scala: exceptions, `StringBuilder`,
   `System.out`, `java.util.Arrays`, `MathContext`, references, and so on. It also replaces the
   few Java-written classes of the Scala library (`*Ref`, `LazyVals`, `JProcedureN`,
   `ScalaNumber`). The Scala compiler refuses to redefine core `java.lang` classes, so the
   sources live in `scalago.javalib.*`/`scalago.scalalib.*` and the plugin renames them in the IR.
   The `.class` files keep those names, so the jar is harmless on a JVM classpath. When a class
   appears more than once on the link classpath, the first occurrence wins, so javalib must come
   before the stdlib IR.
5. **Linker** (`linker/`):
   - **Lambdas** (`Lambdas.scala`): `Closure` nodes become synthetic classes. Their single
     abstract method calls the lifted target method, with boxing and cast adaptation. Bridges are
     added for other abstract methods with the same name, as `LambdaMetafactory` does.
   - **Reachability** (`Analyzer.scala`): a worklist from the entry point. Virtual calls are
     resolved by name over instantiated classes, which over-approximates, as in Scala.js. Only
     reachable classes and methods are emitted. Errors carry a "called from" chain.
   - **Natives** (`Natives.scala`): Go implementations of JDK methods (`String`, boxes, `Math`,
     `System`, `Character`, `BigInteger`, `Arrays`, ...). They take precedence over IR.
   - **Go interop** (`GoInterop.scala`, `GoApi.scala`): uses of Go facades become Go code (see
     [Go interop](#go-interop)).
6. **Runtime** (`runtime/rt`). Go support code, bundled into the linker jar and copied into
   every output module. `runtime/goapi` is the helper that reads Go package APIs for the linker.
7. **Mill plugin** (`mill-build/src/scalago/mill/ScalaGoModule.scala`). It adds the compiler
   plugin and provides `goLink`, `goBuild` and `goRun`. The linker runs in its own JVM, because
   Mill runs on Scala 3.8.

The plugin uses compiler internals, so it is built for exactly one Scala version (3.9.0) and
published with the full Scala version as suffix.

## Object model

| Scala                            | Go                                                              |
|----------------------------------|-----------------------------------------------------------------|
| `Int`, `Long`, `Double`, ...     | `int32`, `int64`, `float64`, ... (`Char` = `uint16`)            |
| boxed primitive                  | the Go primitive inside an `any`                                |
| `String`                         | `*rt.String`: UTF-16 code units, like the JVM (`nil` = null)    |
| `scalago.go.GoString` (opt-in)   | Go `string` (UTF-8); a byte array on the JVM                    |
| `BigInteger`                     | `*rt.BigInteger` (`math/big`)                                   |
| Go facade of a struct type `T`   | `*T` inside an `any` (see [Go interop](#go-interop))            |
| Go facade of any other Go type   | the Go value inside an `any`                                    |
| `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go slice, map, channel or pointer inside an `any` |
| `Array[prim]` / `Array[AnyRef]`  | `*rt.Array[prim]` / `*rt.Array[any]` (covariance works)         |
| class `C`                        | struct `S_C` embedding the superclass's struct (root classes embed `rt.ObjectHeader`, which holds the monitor) |
| reference of static type `C`     | interface `C_C`: embeds parents' interfaces, lists called methods |
| trait `T`                        | interface `C_T`, with a marker method `I_T()`                   |
| method body                      | free function `Impl_C_m(this C_C, ...)`                         |
| virtual method                   | forwarding method on each instantiated struct                   |
| `object O`                       | singleton `Mod_O_S()`, constructed on first use under an `rt.InitLock` |
| `@static` fields, `<clinit>`     | package variables, plus `Clinit_C()`, run once under an `rt.InitLock` |
| `@volatile` field                | `rt.Volatile[T]` (atomic loads, stores, compare-and-set)        |

- **Calls through `this` stay virtual.** Method bodies take `this` as an interface, so calls on it
  stay virtual even in inherited code. Go's struct embedding alone cannot do that.
- **Forwarders.** Every instantiated class gets forwarders for each called method in its vtable,
  including trait default methods (JVM resolution rules: class methods win, then the most
  specific default). An abstract slot for `toString`/`equals`/`hashCode` falls back to
  `java.lang.Object`'s implementation.
- **Exact type tests.** Accessors (`A_C`) and markers (`I_T`) are unique per class, so a Go type
  assertion is an exact `isInstanceOf`.
- **Hijacked classes.** Strings, boxes, `BoxedUnit`, `Class` and `BigInteger` are Go values, not
  structs, as Scala.js calls them "hijacked". So references typed by their supertypes
  (`Object`, `Comparable`, `CharSequence`, `Number`, ...) are Go `any`. Virtual calls on such
  receivers go through generated `Disp_<method>` type switches, and type tests through
  `InstanceOf_<class>` helpers.
- **Never-instantiated classes** are typed `any`. Values flowing from them into interface-typed
  locations are converted explicitly.
- **One Go package.** All generated code goes in one Go package (`internal/scala`), because Scala
  packages may be cyclic. Names are mangled injectively (`Mangling.scala`). The module's root
  package is a thin layer over it: `main` for programs. Later it will hold the Go API of exported
  Scala code.

Output module:

```
go.mod                  module path, plus the Go modules that facades need
main.go                 package main: scala.Main()
internal/scala/         the generated code (classes, support.go, entry.go with Main and hooks)
internal/rt/            the runtime
```

## Control flow

- **Expressions into statements.** Expressions are emitted against a destination (discard,
  return, or assign to a variable), so `if`/`match`/blocks in expression position need no
  temporaries in the common case. Left-to-right evaluation order is kept by spilling earlier
  operands when a later one needs statements.
- **Labeled blocks** (from pattern matching and `@tailrec`) become labeled `for` loops only when
  something `break`s to them. Jumps in tail position become `return`s.
- **Exceptions.** `throw` is `panic(rt.Exc(e))`. `try` runs its body in a closure under
  `rt.Try`, which `recover`s and translates Go runtime errors into Java exceptions: nil
  dereference becomes `NullPointerException`, index out of range `ArrayIndexOutOfBoundsException`,
  division by zero `ArithmeticException`, and a failed type assertion `ClassCastException`. A
  `return` or `break` from inside the closure sets `ctl` and is replayed after the closure
  returns, after the `finally` block if there is one. Uncaught exceptions print
  `Exception in thread "main" ...` and exit with status 1.
- **`lazy val`** relies on JVM `VarHandle`s. The plugin rewrites the handle's compare-and-set
  into IR `CompareAndSet` on the (volatile) lazy val field, so lazy vals are thread-safe as on
  the JVM (see [Concurrency](#concurrency-and-the-memory-model)).

## Go interop

Go code is called through **facades**, Scala declarations of Go APIs, in the style of Scala.js's
JS facades:

```scala
import scalago.go.*

@GoImport("net/url")
object url:
  def Parse(rawURL: String): URL = native   // func Parse(string) (*URL, error)
  class URL:                                // type URL struct { ... }
    def Host: String = native               // a field
    def Path_=(p: String): Unit = native    // field assignment
    def Query(): Values = native            // a method
  class Values:                             // type Values map[string][]string
    def Get(key: String): String = native
```

- **Declarations** (`library/src/scalago/go/Interop.scala`). `@GoImport(path)` on an object is a
  Go package. Its methods are functions, or variables and constants when declared without a
  parameter list (`def Pi: Double`). Classes and traits nested in it are the package's types.
  `@GoImport(path, name)` declares a type elsewhere. Traits are Go interfaces. `@GoName` renames.
  Members are `= native`, which throws on the JVM, so programs that use Go code are Go-only.
- **The plugin** checks the declarations (`def` only, `= native`, no constructor parameters, no
  type parameters) and records the Go name and the Scala signature *before erasure* in the IR
  (`GoMember`, `ScalaType`). Type arguments (`Int => Boolean`, `(Int, GoError)`, `Seq[String]`)
  and opaque types (`GoString`) are erased, but conversions need them.
- **Go signatures from the Go toolchain.** The linker does not trust the facades. It runs the
  `goapi` helper (bundled source, `go run`), which reads the used packages' export data
  (`go list -export`, so the Go build cache) and prints their exported API. Each facade use is
  checked against it, and conversions are driven by both types. So facades use natural Scala
  types (`Int` for Go's `int`, `String` for `string`), and a mismatch is a link error at the Scala
  call site, not a Go compile error in generated code. Packages resolve in the output Go module,
  whose `go.mod` lists the configured requirements (`--go-require`, `--go-replace`; in Mill,
  `goRequires` and `goLocalModules`). `go list -mod=mod` completes `go.mod`/`go.sum`, which needs
  the network for third-party modules.
- **Conversions** (`GoInterop.toGo`/`fromGo`):

  | Scala                      | Go                                                                 |
  |----------------------------|--------------------------------------------------------------------|
  | numeric primitives         | any numeric type, with Go conversions (`int(x)`)                   |
  | `String`                   | `string`, transcoded (UTF-16 ↔ UTF-8)                              |
  | `GoString`                 | `string`, as is                                                    |
  | facade class/trait         | its Go type. Struct types are held as pointers: `T` values are copied in and out, `*T` passed as is |
  | Scala class                | a Go interface it implements                                       |
  | `Array[T]`                 | `[]T`. Memory is shared when the representations match (`Array[Byte]`/`[]byte`, `Array[Long]`/`[]int64`, ...); otherwise elements are copied |
  | `T*`, `Array[T]`           | variadic `...T`. Literal varargs skip the `Seq`                    |
  | `(A, B) => C`              | `func(A, B) C`: calls `apply` through a dispatcher. A Go func *returned* to Scala becomes a `GoFunctionN` wrapping a Go closure that converts with the facade's types |
  | `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go value as is (no copy), if `T` is the canonical view of the Go element type (see below); directional channels and named types are converted |
  | `(A, B)` result            | two results                                                        |
  | `Any`                      | `any`; Scala strings become Go strings                             |

- **Errors.** A trailing `error` result that the facade's result type leaves out is checked, and
  a non-nil error is thrown as `scalago.go.GoException` (its `error` field is the Go error). A
  facade can keep it instead: `(Boolean, GoError)`. Go panics become `RuntimeException`s (runtime
  errors become the usual Java exceptions). Scala exceptions unwind through Go frames as panics.
- **Go interfaces implemented in Scala.** A Scala class extending a facade trait gets Go methods
  (with the Go names) on its struct. They convert arguments, call the Scala method, and convert the
  result. If the Go method returns an `error` that the Scala method does not, an exception becomes
  that error (`rt.RecoverAsError`): a `GoException` gives back the error it wraps, anything else
  becomes an `*rt.ScalaError`.
- **Go values in Scala code** are opaque. `isInstanceOf`/`asInstanceOf` are Go type assertions.
  `toString` uses `Error()`/`String()` when available. `getClass.getName` is the Go type
  (`*strings.Builder`). `==` is Go `==`, or identity for maps, slices and funcs. `new T` is the zero
  value.
- **Output ordering.** Scala's stdout is buffered. It is flushed before each Go call and after
  each Go-to-Scala callback, so it stays ordered with what Go code writes.
- **Imports.** Packages are imported with aliases (`gp_url "net/url"`) so they cannot clash with
  generated names or each other.

- **Generic Go functions** are called through monomorphic facades: `def Max(xs: GoSlice[GoInt]):
  GoInt` for `slices.Max`. The Go types that mention type parameters are taken from the facade's
  Scala types, and the call passes values of those types, so Go infers the type arguments. A
  function needs one facade member per instantiation (`@GoName` allows several).

### Go data in Scala

`GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]` and `GoPtr[T]` (`library/src/scalago/go/`) are opaque
types whose values are Go slices, maps, channels and pointers, shared with Go code without
copying (Scala.js's `js.Array` and `js.Dictionary`). As in Scala.js, the elements have
**canonical** Scala representations, decided by the Go element type alone, so erasure loses
nothing:

| Go element type                          | Scala view                                |
|------------------------------------------|-------------------------------------------|
| `int`, `int64`, `uint`, `uint64`, `uintptr` | `Long` (or `GoInt`, `GoUint`, ...)     |
| `int32`, `uint32`                        | `Int` (or `GoUint32`)                     |
| `int16`, `uint16`, `int8`, `uint8`       | `Short`, `Char`, `Byte` (or `GoByte`)     |
| `float32`, `float64`, `bool`             | `Float`, `Double`, `Boolean`              |
| `string`                                 | `GoString`                                |
| struct values                            | the facade (a pointer to a copy)          |
| anything else                            | the Go value: facades, `GoSlice`, ..., `Any` |

The runtime (`rt/gotypes.go`) converts by the actual Go type (with fast paths for common slice
types, reflection otherwise). Facades are checked at link time: `GoSlice[String]` for a Go
`[]string` is an error that names the right type, `GoSlice[GoString]`. Creating Go data needs
the Go element type, which a `GoType[T]` given supplies: `GoSlice.make[GoInt](n)` makes an
`[]int`, `GoSlice.make[Long](n)` an `[]int64`. `GoInt`, `GoByte` and the other Go numeric types are
opaque over Scala numbers, so they only matter there and in facades. Every operation has a JVM
implementation with Go's semantics (`GoNatives`), so code that uses Go data without facades also
runs on the JVM. The `go-data` test case checks that both agree.

Not supported yet: generic Go *types*, `GoType` instances for facade types, facades using
`GoSlice`/`GoMap` for *named* slice and map types, `select` over channels, unnamed struct and
interface types, variadic callbacks, Go funcs with more than 3 parameters returned to Scala, and
Scala implementations of Go methods with several non-error results. Go code may call Scala code from any
goroutine (see [Concurrency](#concurrency-and-the-memory-model)).

## Exports and library builds

Go code calls Scala code through **exports**, in the style of Scala.js's `@JSExportTopLevel`:

```scala
object Api:
  @GoExport def greet(name: String): String = s"Hello, $name!"     // func Greet(name string) string
  @GoExport @throws[NumberFormatException]
  def parse(s: String): Int = s.trim.toInt                         // func Parse(s string) (int32, error)

@GoExport class Counter(start: Int):                              // type Counter (interface)
  private var count = start                                       // func NewCounter(start int32) Counter
  @GoExport def increment(): Unit = synchronized { count += 1 }   // Increment()
  @GoExport("Value") def current: Int = synchronized(count)       // Value() int32
```

- **Library links** (`Linker.Config.library`, `--library true`, `ScalaGoLibraryModule` in Mill)
  have no entry point: the roots are the exported definitions. The output module's root package
  (named after the module path) is the library's public Go API, in `exports.go`. Other Go modules
  require the output module and import it. Modules and static initializers run on first use, as
  in programs.
- **Exported functions** are methods of top-level objects. **Exported classes** become Go
  interface types (`type Counter = scala.X_Counter`) whose methods are the class's `@GoExport`
  methods (and those of exported superclasses). Their primary constructor becomes `New<Name>`.
  Go names are the Scala names capitalized, unless given.
- **Identity.** The interface value holds the generated struct pointer, so a Scala object is the
  same Go value every time it crosses the boundary (`==` works in Go), with no wrappers. Each
  instantiated subclass's struct gets its own Go methods, which call the Scala methods virtually.
  Methods promoted from an embedded struct would lose the outer receiver.
- **Conversions** follow the facade rules, with the Go types chosen from the Scala types
  (`GoInterop.goTypeOf`): `Int` is `int32`, `GoInt` is `int`, `String` and `GoString` are
  `string`, `Array[T]` and `GoSlice[T]` are `[]T`, `GoMap`, `GoChan` and `GoPtr` are Go's,
  exported classes are their interfaces, facades are their Go types, and Scala functions are Go
  funcs (a Go func passed in becomes a `GoFunctionN`).
- **Errors.** A method annotated with `@throws` gets a trailing `error` result: a Scala exception
  becomes it (`rt.RecoverAsError`; a `GoException` gives back its Go error). Otherwise the
  exception is a Go panic whose value is an `error` describing it.
- **Goroutines.** Exported code may be called from any goroutine (see
  [Concurrency](#concurrency-and-the-memory-model)). Buffered Scala output is flushed when each
  call returns.
- **No import cycles.** Go forbids them, so a Go package that the library imports through a
  facade cannot import the library. Such Go code receives Scala code as values instead: Go
  interfaces implemented in Scala, or Go funcs.
- **One Scala library per Go binary.** Linking is whole-program, so two separately linked Scala
  libraries in one binary would each have their own runtime and standard library, as with
  separately linked Scala.js modules.

Not supported yet: exported traits (Go code implementing Scala traits), exported vals and fields,
secondary constructors, tuple results, overloaded exports with the same Go name, and generic
exported methods.

## Concurrency and the memory model

Go code calls whatever Scala code it is given from any goroutine, so the runtime and the
generated code are goroutine-safe, with the JVM's guarantees where Scala code relies on them:

- **Initialization.** Module construction and static initializers run once, under an
  `rt.InitLock`: a goroutine that finds one in progress in another goroutine waits for it, and
  the initializing goroutine re-entering (a module referring to itself during construction, or
  initialization cycles) sees the partially initialized state, as on the JVM. If an initializer
  throws, the exception propagates, and later uses throw `NoClassDefFoundError`. The fast path
  is one atomic load.
- **Monitors.** `synchronized` is a reentrant per-object monitor, and `wait`/`notify`/`notifyAll`
  work as on the JVM (with timeouts and interrupts). The plugin lowers `synchronized` to
  enter/exit around `try`/`finally`, so `return` and exceptions release the monitor. Scala
  objects carry their monitor in an `rt.ObjectHeader`, created on first use. Strings, boxes and
  Go values use a global table, whose entries are never removed. Reentrancy needs the current
  goroutine's id, which Go does not expose: the runtime parses it from `runtime.Stack`, which
  costs about a microsecond per monitor operation.
- **Volatile fields** (`@volatile`, and the state field of each `lazy val`) are `rt.Volatile[T]`:
  sequentially consistent, which is what the JMM requires of volatiles. javalib's
  `java.util.concurrent.atomic` classes (`AtomicInteger`, `AtomicLong`, `AtomicBoolean`,
  `AtomicReference`) are volatile fields plus the `scalago.runtime.Intrinsics.compareAndSet`
  intrinsic, which becomes IR `CompareAndSet`.
- **Lazy vals** follow Scala 3's scheme: compare-and-set on the volatile field, and goroutines
  that find the value being computed wait on a `Waiting` marker (a monitor).
- **Threads.** `java.lang.Thread` runs on a goroutine. `start`, `join` (with timeouts),
  `sleep`, `interrupt`, `currentThread`, names, daemon status and uncaught-exception handlers
  work. A goroutine that did not start as a `Thread` (the main goroutine, or one started by Go
  code) gets a `Thread` object the first time it asks for one. As on the JVM, `Main()` waits for
  the non-daemon threads before returning, and an exception in a thread is reported
  (`Exception in thread "name" ...`) without ending the program.
- **Runtime state.** The stdout buffer (one lock per write, so lines do not interleave), string
  interning, the `Class` table and `String.hashCode`'s cache are goroutine-safe.

What is *not* guaranteed: ordinary fields are plain Go memory, so a data race on them is a bug,
as on the JVM. But the JMM gives racy programs some guarantees, and Go does not: a racy read of a
reference (an interface or a slice header in Go) can see a torn value and crash. `final` fields
get no special publication guarantee either: share objects through `synchronized`, volatiles,
atomics, `Thread.start`/`join` or Go channels, which are all happens-before edges. Programs can
be checked with Go's race detector (`go build -race`). The `concurrency` test case runs under it.

## Semantics handled explicitly

- **JVM numeric semantics:** wrapping arithmetic, masked shift counts, `>>>`, saturating
  float-to-int conversions, and float `%`. Literals are folded where Go would reject constant
  overflow.
- **Equality and strings:** `==` is null-safe and numerically cooperative (`rt.Equals`). `##`
  matches `Statics.anyHash`. `toString` matches the JDK, including `Double.toString`. String
  `hashCode` and `compareTo` work on UTF-16.

## Status

Supported and covered by end-to-end tests:
- classes and traits (fields, `super[T]`, self types)
- objects and `@main`
- exceptions (`try`/`catch`/`finally`, runtime errors)
- pattern matching, including case classes, `Option`, tuples, guards, and switches on ints and
  strings
- lambdas (captured `var`s, SAM types, `andThen`/`compose`)
- `lazy val`
- standard-library collections (`List`, `Vector`, `Map`, ranges, `sorted`, `groupBy`, ...),
  string interpolation, `StringBuilder`, `BigInt`
- `scalago.go.GoString`
- Go interop: facades of standard-library packages and of a local Go module, Go interfaces
  implemented in Scala, errors and callbacks, Go slices, maps, channels, pointers and funcs
  shared with Go, and generic Go functions. Mismatched facades are tested as link errors.
- exports: a Scala library used by a Go program (functions, an exported class hierarchy,
  errors, panics, callbacks, identity, and calls from 100 goroutines under the race detector)
- concurrency: threads, `synchronized`, `wait`/`notify`, volatile fields, atomics, interrupts,
  and concurrent first use of modules and lazy vals (also under Go's race detector)

Known gaps:
- `BigDecimal` (`java.math.BigDecimal` is not implemented), regular expressions,
  `String.format` beyond the common conversions, `java.util` collections, `java.time`, and I/O
  beyond stdout/stderr.
- Reflection: `getClass` and `Class` names work; nothing else.
- Concurrency: `java.util.concurrent` beyond the atomics (executors, locks, concurrent
  collections, `CompletableFuture`), `ThreadLocal`, thread groups. `scala.concurrent.Future`
  needs an `ExecutionContext` on `java.util.concurrent`.
- `Enumeration` (it uses reflection).
- Stack traces: exceptions carry none.

When reachable code uses something missing, linking fails with a "called from" chain naming
the API.

## Roadmap

The goal is full two-way interop between Scala and Go, as Scala.js has with JavaScript. Scala
calls Go (facades), and Go calls Scala (exports, library builds). Both share data without
copying wherever the representations allow. Scala.js's features map to Go as follows:

| Scala.js                                  | scala-go                                                  | Status |
|-------------------------------------------|-----------------------------------------------------------|--------|
| `@JSImport` native objects and classes    | `@GoImport` facades of packages and types                 | done   |
| facades checked against the target only at run time | facades checked against Go's API at link time   | done   |
| non-native JS classes (Scala classes JS can use) | Scala classes implementing Go interfaces           | done   |
| `js.Array`, `js.Dictionary`, `js.Function`, `js.Promise` | `GoSlice`, `GoMap`, Go funcs, `GoChan`/gears | step 3 |
| `@JSExportTopLevel`, `@JSExport`          | `@GoExport` functions, objects and classes                | step 4 |
| ES module output, module splitting        | library builds: a Go package other Go code imports        | step 4 |
| ScalablyTyped (facades generated from .d.ts) | facades generated from Go packages (`goapi`)           | step 6 |

The order follows the dependencies. Each step builds on the previous ones, and nothing done
earlier has to be redone:

1. **Output layout** (done). Generated code lives in `internal/scala`, not in `package main`, and
   the root package is a thin layer over it. Exports and library builds add a public root package
   here. Everything later emits into this layout, so it came first.
2. **A goroutine-safe runtime** (done, see
   [Concurrency](#concurrency-and-the-memory-model)). Go code calls whatever it is given from
   any goroutine (HTTP handlers, callbacks, exported functions). Scala.js never needed this,
   since JavaScript is single-threaded, so this is where scala-go goes beyond it: init locks for
   modules and static initializers, atomic lazy vals, monitors, volatiles and atomics,
   `java.lang.Thread` on goroutines, a goroutine-safe runtime, and a documented memory model.
   It came before exports, which would otherwise be unsafe by default, and before gears.
3. **Go types as Scala types** (done, see [Go data in Scala](#go-data-in-scala)). Zero-copy types
   for Go's own data: `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]`, Go numeric types, and
   Go funcs as Scala functions. They are opaque types with canonical element representations and
   a JVM implementation. Facades use them for maps, channels, pointers and returned funcs, and
   call generic Go functions through monomorphic facades. This vocabulary had to exist before
   exports, so that exported signatures can say "this is a Go slice" instead of copying `Array`s.
4. **Exports and library builds** (done, see [Exports](#exports-and-library-builds)). `@GoExport`
   on methods of top-level objects and on classes. A library link emits a public root package
   with typed Go functions and interface types, converted with the facade rules in the other
   direction, and `ScalaGoLibraryModule` produces a Go module other Go modules require. Exported
   objects keep their identity across the boundary, Scala exceptions become `error` results
   (`@throws`) or panics, and there are no import cycles: Go code that Scala imports gets Scala
   code as interfaces and funcs.
5. **Concurrency API: gears on goroutines.** `Async`/`Future` on goroutines, channels on `GoChan`,
   and cancellation bridged to `context.Context` at the boundary. This needs steps 2 and 3, and is
   independent of step 4.
6. **Facade generation** from Go packages, using `goapi` and the step 3 types. Facades for the
   most-used standard-library packages, published as a library.
7. **Library coverage** (`BigDecimal`, `java.util` collections, regex on Go's `regexp`, more of
   `java.lang`) and **publishing** (organization `io.github.scalago` is a placeholder). These
   can happen in parallel with the steps above.

## Open questions

- **Missing members.** Should unresolvable but statically reachable members become link errors
  (current behavior) or runtime errors, as on the JVM, with a warning? The JVM behavior is more
  permissive, but it hides gaps.
- **Facades on the JVM.** Facade members throw on the JVM, so programs using Go cannot be run or
  tested there. Facades could take an optional JVM implementation, or be generated from Go
  packages (the `goapi` helper already reads their APIs).
- **Shared or copied slices.** `Array[T]` ↔ `[]T` shares memory only when the representations
  match, and `GoSlice[T]` always does. Should `Array` conversions stay as a convenience (current
  behavior) or require `GoSlice`?
