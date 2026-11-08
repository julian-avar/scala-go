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
   cannot recompile it from TASTy. **Other Scala 3 libraries** are compiled the same way
   (`TastyIR`, `ScalaGoModule.scalaGoDependencyIR`): every dependency jar with TASTy and no IR.
   So published Scala libraries such as gears need no scala-go specific artifact, unlike
   Scala.js and Scala Native, where each library is published per platform. A library that
   needs JVM-only APIs can be patched by overriding single classes: a module compiled with the
   plugin and `-P:scala-go:rename` emits replacements under the library's own names, and its IR
   comes first on the link classpath (`scalago-gears` does this for one gears class).
4. **javalib** (`javalib/`). JDK classes written in Scala: exceptions, `StringBuilder`,
   `System`, `java.io`, `java.nio` (buffers, channels, files, charsets; see [I/O](#io)),
   `ProcessBuilder` and `Process` (see [Subprocesses](#subprocesses)), `java.net` and
   `java.net.http` (see [Networking](#networking)), `java.security`'s digests and `SecureRandom`,
   `javax.crypto.Mac`, `java.util.zip` (see [Digests and compression](#digests-and-compression)),
   `HexFormat`, `java.util`
   (collections, `Arrays`, `Collections`, `Optional`, `Random`, `UUID`,
   `java.util.function`, `java.util.stream`, ...), `java.util.concurrent` (see
   [Concurrency](#concurrency-and-the-memory-model)), `java.time` (see [java.time](#javatime)),
   `MathContext` and `RoundingMode`, references, and so on. It also replaces the
   few Java-written classes of the Scala library (`*Ref`, `LazyVals`, `JProcedureN`,
   `ScalaNumber`), and `Enumeration`, which uses reflection (see below). Packages other than `java.lang` are compiled under their own names
   (`package java.util`): a class compiled from source takes precedence over the JDK's, so
   javalib and the JDK class files it is type-checked against agree on one symbol per class.
   The Scala compiler refuses to redefine core `java.lang` classes, so those sources live in
   `scalago.javalib.lang` (and the Scala library's in `scalago.scalalib`), and the plugin renames
   them in the IR. Static methods of JDK classes and interfaces are methods of Scala objects:
   with `-P:scala-go:static-forwarders`, the plugin adds static forwarders to the companion
   class (or a mirror class), as the JVM backend does, except that only a method of the class
   with the same signature prevents one (JDK classes overload static and instance methods:
   `Year.isLeap(long)` and `isLeap()`). javalib is always compiled whole, since
   classes of a previous compilation lose against the JDK's. It is only ever linked: its jar
   must not be on a compile or run classpath. When a class appears more than once on the link
   classpath, the first occurrence wins, so javalib must come before the stdlib IR.

   Much of `java.util`, `java.util.concurrent` and `java.io` is adapted from Scala Native's
   javalib (Apache-2.0,
   `javalib/LICENSE-scala-native.md`; each such file says so), changed where programs can
   observe a difference from the JDK: `HashMap` lays out its table as the JDK does (capacities,
   growth, order within buckets), so iteration orders match (except in buckets of more than 8
   colliding hash codes, which the JDK turns into trees); `HashSet`, `LinkedHashSet` and
   `Hashtable` follow it; iterators are fail-fast (`modCount`, also for programs' subclasses of
   `AbstractList`); `Random`, `SplittableRandom` and `Collections.shuffle` give the JDK's
   sequences. The operations of `Arrays` on primitive arrays are natives, generic Go functions
   standing in for Scala Native's specialization.

   **java.util.stream.** The interfaces come from Scala Native, but the implementation follows
   the JDK's design (Scala Native's pulls every element through a chain of spliterators, with a
   closure per element and stage). A stage of a pipeline (`AbstractPipeline` and its four
   element types) knows how to wrap the sink of the next stage in its own, and a terminal
   operation pushes the source spliterator's elements through the chain of sinks with one
   `forEachRemaining` call (or `tryAdvance` until cancellation, when an operation
   short-circuits). A traversal allocates one sink per stage and nothing per element, and
   primitive streams pass `int`s, `long`s and `double`s unboxed (`IntStream.range(0, n).sum()`
   is a loop of interface calls). `iterator()` and `spliterator()` pull through the same sinks,
   a source element at a time. Pipelines track the JDK's flags (sized, sorted, distinct,
   short-circuiting, size-adjusting), so observable behavior matches: the order of side
   effects in `peek`, `sorted` as a barrier, `count()` without traversal when the size is known
   (also through `skip`/`limit`), `sorted()`/`distinct()` passing through already sorted or
   distinct sources, short-circuits reaching into `flatMap`'s streams, the "already operated
   upon or closed" errors, close handlers, compensated double sums. `Collectors` accumulate as
   the JDK's (`groupingBy` with `computeIfAbsent` into a `HashMap`, so groups iterate in the
   JDK's order). Parallel streams run sequentially: `parallel()` only sets the flag
   `isParallel()` reports. Strings get `chars()` and `codePoints()` from `CharSequence`'s
   default methods: a method of a hijacked class without a native falls back to a default
   method of one of its interfaces (`Program.hijackedDefault`).
5. **Linker** (`linker/`):
   - **Lambdas** (`Lambdas.scala`): `Closure` nodes become synthetic classes. Their single
     abstract method calls the lifted target method, with boxing and cast adaptation. Bridges are
     added for other abstract methods with the same name, as `LambdaMetafactory` does.
   - **Reachability** (`Analyzer.scala`): a worklist from the entry point. Virtual calls are
     resolved by name over instantiated classes, which over-approximates, as in Scala.js. Only
     reachable classes and methods are emitted. Errors carry a "called from" chain.
   - **Natives** (`Natives.scala`): Go implementations of JDK methods (`String`, boxes, `Math`,
     `System`, `Character`, `BigInteger`, `BigDecimal`, `Arrays`, ...). They take precedence over IR.
     They also implement the `scalago.runtime` objects through which javalib reaches the Go
     runtime (`Threads`, `AtomicArrays`, `Regexes`, `Console`, `OS`, `Process`, `Subprocesses`, `Net`, `Crypto`,
     `Zip`): Scala objects whose
     methods throw on the JVM and become Go calls when linked.
   - **`String` members written in Scala.** `String` is a Go value whose methods are natives;
     the members that are easier in Scala (`lines()`, `CASE_INSENSITIVE_ORDER`,
     `join(delimiter, Iterable)`) are in javalib's `java.lang.String$` object
     (`scalago.javalib.hijacked.lang.String`). A static member without a native is the object's,
     and an instance method without a native is the object's method taking the receiver first
     (`Program.staticMethodViaCompanion`, `hijackedCompanionMethod`); this works for any
     hijacked class.
   - **Static constants of javalib objects.** Java's static fields (`Collections.EMPTY_LIST`,
     `Attributes.Name.MAIN_CLASS`) are usually members of the companion object in javalib's
     Scala. A read of a static field that a class does not have becomes a call of the companion
     object's getter of the same name and type (`Program.staticFieldViaCompanion`).
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
| `BigDecimal`                     | `*rt.BigDecimal`: an `int64` unscaled value, or a `big.Int` when it does not fit, and a scale |
| Go facade of a struct type `T`   | `*T` inside an `any` (see [Go interop](#go-interop))            |
| Go facade of any other Go type   | the Go value inside an `any`                                    |
| `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go slice, map, channel or pointer inside an `any` |
| `Array[prim]` / `Array[AnyRef]`  | `*rt.Array[prim]` / `*rt.Array[any]` (covariance works)         |
| class `C`                        | struct `S_C` embedding the superclass's struct (root classes embed `rt.ObjectHeader`, which holds the monitor) |
| reference of static type `C`     | interface `C_C`: embeds parents' interfaces, lists the methods called on `C` receivers |
| trait `T`                        | interface `C_T`, with a marker method `I_T()`                   |
| method body                      | free function `Impl_C_m(this C_C, ...)` (`this *S_D` if only class `D` can be `this`) |
| virtual method                   | forwarding method on each instantiated struct                   |
| `object O`                       | singleton `Mod_O_S()` (a `*S_O_S`), constructed on first use under an `rt.InitLock` |
| `@static` fields, `<clinit>`     | package variables, plus `Clinit_C()`, run once under an `rt.InitLock` |
| `@volatile` field                | `sync/atomic` value (`rt.VolatileInt32`, ...) or `rt.Volatile[T]` for references |

- **Calls through `this` stay virtual.** Method bodies take `this` as an interface, so calls on it
  stay virtual even in inherited code. Go's struct embedding alone cannot do that.
- **Exact receivers.** When only one instantiated class `D` is or extends `C` (objects, lambdas,
  case classes and other leaves, traits with one implementation), `C`'s method bodies take
  `this` as `*S_D` (`LinkContext.exactReceiver`): field accesses and calls on `this` are then
  static Go calls, which Go inlines. Super and private calls convert their receiver with an
  `Exact_D` helper (only null can fail it: an NPE). Object accessors return the struct pointer
  too, so calls on objects are static. Together these made allocation- and call-heavy
  benchmarks 15–40% faster.
- **Forwarders.** An instantiated class gets a forwarding Go method for each method its
  ancestors' interfaces declare, plus each method called without a static class (through
  dispatchers, by Go code or the runtime), with the implementation the vtable resolves,
  including trait default methods (JVM resolution rules: class methods win, then the most
  specific default). `C_C` declares only the methods called on receivers of static type `C`, so
  a method called on a `List` does not give every collection class a Go method (this cut the
  generated code by a quarter). An abstract slot for `toString`/`equals`/`hashCode` falls back to
  `java.lang.Object`'s implementation.
- **Exact type tests.** Accessors (`A_C`) and markers (`I_T`) are unique per class, so a Go type
  assertion is an exact `isInstanceOf`.
- **Hijacked classes.** Strings, boxes, `BoxedUnit`, `Class`, `BigInteger` and `BigDecimal` are Go values, not
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
  returns, after the `finally` block if there is one. An uncaught exception prints
  `Exception in thread "main" ` and its stack trace to standard error and exits with status 1,
  as on the JVM (in other threads: the thread's handler, or the same report).
- **Stack traces** look like the JVM's (`at Main$.deep(Main.scala:5)`):
  - *Positions.* The linker writes Go line directives into method bodies
    (`/*line Main.scala:5:3*/`, see `LineDirectives.scala`), so Go reports Scala files and
    lines: in stack traces, but also in Go panics and Go compiler and `go vet` messages. A
    directive is only written where a line's Scala position differs from the one Go would count
    on to, and lines that cannot fail (braces, jumps, declarations) need none: about 5% more
    generated code, with no measurable effect on `go build` time. Code around method bodies
    keeps its Go positions. `Linker.Config.lineDirectives` (`--line-directives false`, Mill's
    `goLineDirectives`, `SCALAGO_LINE_DIRECTIVES=false` in the tests) turns them off, to see
    Go positions when debugging the generated code.
  - *Capture.* `Throwable`'s constructor calls `fillInStackTrace` (unless
    `writableStackTrace` is false: `ControlThrowable`s, `NoStackTrace` and `boundary`'s
    `Break` capture nothing), which records the stack as Go program counters by following
    frame pointers (amd64 and arm64; `runtime.Callers` elsewhere): about a nanosecond per
    frame. A Go runtime error becomes a Java exception while its panic is being recovered,
    before the stack unwinds, so its trace starts at the fault; those use `runtime.Callers`,
    since a faulting leaf function has no frame record of its own (as do programs that have
    called C code).
  - *Symbolization* happens on the first `getStackTrace` or `printStackTrace`, from the
    program counters: method implementations (`Impl_<class>__<method>...`, see `Mangling`)
    demangle back to Scala class and method names, so no table is needed. Frames the JVM would
    not show are dropped: Go runtime and `rt` frames, generated helpers (forwarders,
    dispatchers, allocators, module accessors), lambda classes, the Throwable's own
    `fillInStackTrace` and constructors, and the enclosing function of a `try` block's closure
    (the closure's frame stands for it, as the JVM has one frame per invocation). An object's
    entry point is called through a static forwarder, `Main.main`, which the JVM shows without
    a line number (`at Main.main(Main.scala)`), so whole traces of user code match the JVM's.
  - `printStackTrace` prints the JVM's format, with `Caused by:` and `Suppressed:` blocks,
    `... n more` for frames in common with the enclosing trace, and circular causes.
  - Differences from the JVM: frames of JDK classes are missing where scala-go implements them
    in Go (`Integer.parseInt`, `String` methods) and are javalib's Scala methods elsewhere
    (`java.lang.Thread.run(Thread.scala:23)`, not `java.base/...(Thread.java:...)`); module
    initialization shows no `<clinit>` frame; lines are those of statements (an exception in a
    call on the second line of a multi-line statement reports the statement's first line);
    inlined code reports its own source file and line; and `NullPointerException`s have no
    message (the JVM's "helpful" messages name the null expression).
  - `SCALAGO_TRACE=1` keeps every frame, with Go function names and Go positions: for
    debugging the runtime and the generated code.
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
  the network for third-party modules: first for the facades' packages, then, once the code is
  written, for the modules they import (`go build` wants those listed as indirect requirements).
- **Conversions** (`GoInterop.toGo`/`fromGo`):

  | Scala                      | Go                                                                 |
  |----------------------------|--------------------------------------------------------------------|
  | numeric primitives         | any numeric type, with Go conversions (`int(x)`)                   |
  | `String`                   | `string`, transcoded (UTF-16 ↔ UTF-8)                              |
  | `GoString`                 | `string`, as is                                                    |
  | facade class/trait         | its Go type. Struct types are held as pointers: `T` values are copied in and out, `*T` passed as is |
  | facade struct class, `Any` | a Go interface (or `any`): a struct goes by value if its value implements the interface (for `any`: if it has no pointer-receiver methods), otherwise as the pointer (see below) |
  | Scala class                | a Go interface it implements                                       |
  | `Array[T]`                 | `[]T`. Memory is shared when the representations match (`Array[Byte]`/`[]byte`, `Array[Long]`/`[]int64`, ...); otherwise elements are copied |
  | `T*`, `Array[T]`           | variadic `...T`. Literal varargs skip the `Seq`                    |
  | `(A, B) => C`              | `func(A, B) C`, or a named func type (`type Cmd func() Msg`): calls `apply` through a dispatcher. A Go func *returned* to Scala becomes a `GoFunctionN` wrapping a Go closure that converts with the facade's types |
  | `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go value as is (no copy), if `T` is the canonical view of the Go element type (see below); directional channels and named types are converted |
  | `(A, B)` result            | two results                                                        |
  | `Any`                      | `any`, or a named interface without methods (`type Msg interface{}`); Scala strings become Go strings |

- **Errors.** A trailing `error` result that the facade's result type leaves out is checked, and
  a non-nil error is thrown as `scalago.go.GoException` (its `error` field is the Go error). A
  facade can keep it instead: `(Boolean, GoError)`. Go panics become `RuntimeException`s (runtime
  errors become the usual Java exceptions). Scala exceptions unwind through Go frames as panics.
- **Go interfaces implemented in Scala.** A Scala class extending a facade trait gets Go methods
  (with the Go names) on its struct. They convert arguments, call the Scala method, and convert the
  result; several Go results come from a Scala tuple (bubbletea's `Update(Msg) (Model, Cmd)` is
  `def Update(msg: Msg): (Model, Cmd)`). If the Go method returns an `error` that the Scala method
  does not, an exception becomes that error (`rt.RecoverAsError`): a `GoException` gives back the
  error it wraps, anything else becomes an `*rt.ScalaError`.
- **Go values in Scala code** are opaque. `isInstanceOf`/`asInstanceOf` are Go type assertions.
  Go interfaces can hold struct values (`tea.KeyMsg` in a `tea.Msg`), but facades hold struct
  pointers: a type test against a struct facade accepts both, and the cast copies a value to a new
  pointer (`rt.IsStruct`, `rt.CastStruct`). In the other direction, passing such a struct to a Go
  interface passes the value again where Go code would (`rt.ToGoInterface`, `rt.ToGoAny`): Go
  code receiving it tests for `KeyMsg`, not `*KeyMsg`. The choice depends on the type alone, by
  its method sets, and is cached per type: a struct with pointer-receiver methods
  (`bytes.Buffer`, `url.URL`) stays a pointer in an `any`.
  `toString` uses `Error()`/`String()` when available. `getClass.getName` is the Go type
  (`*strings.Builder`). `==` is Go `==`, or identity for maps, slices and funcs. `new T` is the zero
  value.
- **Output ordering.** Scala's stdout is buffered. It is flushed before each Go call and after
  each Go-to-Scala callback, so it stays ordered with what Go code writes.
- **Imports.** Packages are imported with aliases (`gp_url "net/url"`) so they cannot clash with
  generated names or each other.

- **Conversions to named types** are package members named as the type, with one parameter:
  `def Color(value: String): Color` in a facade of `lipgloss` is Go's `lipgloss.Color(value)`,
  with the argument converted to the underlying type.
- **Generic Go functions** are called through monomorphic facades: `def Max(xs: GoSlice[GoInt]):
  GoInt` for `slices.Max`. The Go types that mention type parameters are taken from the facade's
  Scala types, and the call passes values of those types, so Go infers the type arguments. A
  function needs one facade member per instantiation (`@GoName` allows several).

### Generated facades

`FacadeGen` (`scalago.linker.FacadeGen`; `goFacadePackages` in `ScalaGoModule`) writes facades
from Go packages' APIs, as ScalablyTyped does from `.d.ts` files. Each package becomes one
`@GoImport` object, and its types become classes and traits:

- Parameters and results get the cheapest exact Scala types: Go numbers become Scala numbers
  (`int` is `Long`), `string` is `String`, `error` is `GoError`, Go types are the generated
  facades, funcs are Scala functions, and several results are tuples. A trailing `error` is
  thrown, except where the error is the function's value (`errors.New`, `Errorf`, `ctx.Err()`).
- Go func types without methods are type aliases of Scala function types (`type Cmd = () =>
  Any`), and interfaces without methods of `Any` (`type Msg = Any`), so that Scala lambdas and
  values are passed as they are; aliases of function types are erased in the IR, so the plugin
  records facade types as seen before erasure, aliases followed. Other named non-struct types
  are classes, with a conversion (`def Color(value: String): Color`).
- Generic functions whose type parameters are constrained by interfaces (`key.Matches[K
  fmt.Stringer](k K, ...)`) take the constraint (`k: fmt.Stringer`); the linker passes values of
  that type, so Go infers it as the type argument. Constraints with type sets (`~int | string`,
  `comparable`) have no such type, and these functions are left out.
- The types of other packages that the generated members use are generated too, transitively
  (`time.Duration` for `tea.Tick`, `io.Reader` for `tea.WithInput`), but only the types: a
  package's functions, variables and constants need the package itself in the list. `goapi`
  runs again with the newly needed packages until none is missing. `internal` packages, which
  the generated Go module could not import, are left out.
- Inside Go data, the canonical views apply (`GoSlice[GoString]`, `GoMap[GoString, GoInt]`), so
  slices are shared and the right Go types are created.
- Go typing is structural and Scala's is nominal, so a generated type extends every generated
  interface whose methods it has (`class Buffer extends io.Writer, io.Reader, fmt.Stringer, ...`).
- Members whose types cannot be represented are left out with a comment saying why: generics
  with type sets, unnamed structs, arrays, funcs inside Go data, complex numbers, types of
  `internal` packages, and constants beyond `Long`.

Generated facades are checked against Go at link time like handwritten ones, but only where
used, so unused members cost nothing. `scalago-gostd` (`gostd/` in this build) has the facades
of 21 standard library packages and the types of `io/fs`, `reflect` and `syscall` that they use,
about 1,700 members. The `go-std` test case uses them; `go-facadegen` generates facades of a
local module written in the style of bubbletea and checks the rules above; and `gotui/` (`mill
gotui.check`) uses bubbletea, bubbles and lipgloss.

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

## I/O

java.io and java.nio (buffers, channels, files, charsets) are Scala code in javalib over a small
native layer on Go's `os` package (`scalago.runtime.OS`, `rt/files.go`). The Java semantics
(which exception, which message) are in Scala; the layer is byte-oriented and knows nothing of
Java's classes:

- **Handles**: `open(path, mode)` (read, write, append, create, create-new, truncate) gives an
  `*os.File` held as an `Object`; `read`, `write`, `readAt`, `writeAt` (positional, for file
  channels), `seek`, `size`, `available`, `truncate`, `sync`, `lock`/`unlock` (POSIX record locks
  with fcntl, as the JDK's; none on Windows) and `close` work on it. Read and write errors throw
  `IOException` with the C library's message.
- **Paths**: `stat`, `access`, `mkdir`, `remove`, `rename`, `readDir`, `createTemp`, `chmod`,
  `setModified`, `symlink`, `link`, `readLink`, `realPath`. They return their result or a Go error;
  `errorKind` classifies it (no such file, exists, not empty, access denied, not a directory, is
  a directory, loop) and `errorReason` gives the C library's text ("No such file or directory").
  javalib builds the JDK's exceptions from them: `FileNotFoundException("<path> (<reason>)")` in
  java.io, `NoSuchFileException`, `FileAlreadyExistsException`, `DirectoryNotEmptyException`,
  `AccessDeniedException` or `FileSystemException` in java.nio.file.

The stream, reader and writer classes are adapted from Scala Native's javalib. Paths are Unix
paths (`/`), normalized as by the JDK.

- **Buffers** (`java.nio`) are heap buffers: `ByteBuffer` on a byte array, with multi-byte values
  in either byte order and views (`asIntBuffer`, ...); `CharBuffer`, `ShortBuffer`, `IntBuffer`,
  `LongBuffer`, `FloatBuffer` and `DoubleBuffer` on arrays, as views of byte arrays, or (chars)
  on read-only text (`CharBuffer.wrap(CharSequence)`). States, exceptions, `equals`,
  `compareTo`, `mismatch` and `hashCode` are the JDK's, and so are the concrete classes' names
  (`HeapByteBuffer`, `HeapIntBufferR`, `ByteBufferAsLongBufferL`, `StringCharBuffer`), which
  `toString` shows. Each element type's buffer is a class with abstract element access (`load`,
  `store`) and the backing array when there is one, which bulk operations copy with
  `System.arraycopy`; the classes for all element types but bytes and the implementations
  (heap, read-only, byte-array views in both orders) are generated from
  `javalib/templates/*.template` by javalib's `generatedSources`. `allocateDirect` makes a heap
  buffer (`isDirect` is false, `hasArray` true).
- **Charsets** are UTF-8 (the default), ISO-8859-1 and US-ASCII. Their coders work on arrays
  (`decodeArray`/`encodeArray`, which `InputStreamReader`, `OutputStreamWriter`, `Files` and
  `new String(bytes, ...)` use directly), returning underflow, overflow or an error at the
  input in error; malformed UTF-8 is replaced or reported in the JDK's units (each maximal prefix
  of a valid sequence). `CharsetDecoder` and `CharsetEncoder` have the JDK's configuration and
  buffer API on top (`CoderResult`, the reset/coding/end/flushed state machine, `Charset.encode`
  and `decode`), copying buffers that have no accessible array. Programs cannot define charsets.
- **Channels**: `FileChannel` (`FileChannel.open`, `Files.newByteChannel`, and `getChannel` of
  file streams and `RandomAccessFile`, which share the stream's file and position, as on the JDK)
  with scattering and gathering, positional reads and writes, `transferTo`/`transferFrom`,
  `truncate`, `force` and locks (overlapping locks within the process are refused, as on the
  JDK). `map` reads the region into a heap `MappedByteBuffer`: `READ_ONLY` and `PRIVATE` mappings
  behave as the JDK's (but later changes to the file are not seen), `READ_WRITE` is not
  supported. `Channels` adapts streams and channels both ways, and readers and writers.
- **File systems**: `FileSystems.getDefault()` (also `Path.getFileSystem`) is the only one:
  `getPath`, `getSeparator`, `getRootDirectories`, and `getPathMatcher` with `glob:` (translated
  as the JDK's `Globs`, into a regular expression for Go's engine) and `regex:` syntaxes.

**Standard streams.** `System.out` and `System.err` are `PrintStream`s whose print methods hand
the value to the runtime (`Console.write`), which transcodes it straight into a 32 KB stdout
buffer (stderr is unbuffered): one call and one lock per `print` or `println`, so lines from
different threads never interleave. Standard output is flushed at exit, before writing to
stderr, before reading stdin, and around calls into Go code; unlike the JDK's autoflushing
`System.out`, not after each line. `System.in` is a `BufferedInputStream` over `os.Stdin`.
`System.setOut/setErr/setIn` replace the streams. `Predef.println` goes through the Scala
library's `Console`, so `Console.withOut` works: it needs `InheritableThreadLocal`, and javalib
replaces `scala.util.DynamicVariable` with a version that skips the thread-local lookup until a
variable is first set, so `Console.out` costs one atomic load while nothing is redirected.

Not like the JDK: the console streams' print methods do not take the stream's monitor, so a
thread holding `System.out`'s lock does not keep other threads from printing; `FileTime`s have
millisecond precision; file attributes given at creation are ignored; directories list in name
order (`Files.list`, `walk`, `newDirectoryStream`; the JDK follows the file system's order);
`Files.walk` with `FOLLOW_LINKS` does not detect cycles; blocking channel operations are not
interrupted by `Thread.interrupt` (no `ClosedByInterruptException`); `FileLock`'s class is
`java.nio.channels.FileLockImpl`; `ByteOrder.nativeOrder()` is little-endian (Go's big-endian
platforms are not supported); direct and `READ_WRITE`-mapped buffers, socket channels,
selectors, asynchronous channels, other file systems and providers (`FileSystemProvider`,
zip file systems), `FileStore`, watch services, `Scanner` and other charsets are not
implemented.

## Subprocesses

`java.lang.ProcessBuilder`, `Process` and `ProcessHandle` are Scala (javalib) over Go's `os/exec`
(`scalago.runtime.Subprocesses`, `rt/subprocess.go`):

- **Starting.** `start` passes the command, the directory, the environment (only if the program
  touched `environment()`, a copy of `System.getenv()` checked as the JDK checks it) and the three
  redirects to the native layer. Pipes are `os.Pipe`s: the child gets one end, the parent keeps
  the other as a handle of the file layer, so a process's streams are java.io file streams,
  buffered as the JDK's (`getOutputStream` must be flushed or closed). `INHERIT` gives the child
  the program's own standard streams (standard output is flushed first, so what the program
  printed comes before what the child prints); files (`from`, `to`, `appendTo`, `DISCARD`) are
  opened by the parent. As on the JDK, the program is looked up on the parent's `PATH`, a
  relative program path is relative to the directory, and failures are `IOException`s with the
  JDK's messages (`Cannot run program "x" (in directory "d"): Exec failed, error: 2 (No such file
  or directory)`).
- **Waiting.** A goroutine per process waits for it and records the exit status (128 plus the
  signal's number when a signal ended it, as on the JDK). `waitFor`, with or without timeout,
  blocks on a channel and is interruptible (`InterruptedException`). `destroy` sends `SIGTERM`
  and `destroyForcibly` `SIGKILL` (both kill on Windows), and close the pipes, as the JDK.
- `ProcessHandle.current()` (pid, parent), `Process.toHandle`, `pid`, `inputReader` and
  `outputWriter`, and `Runtime.exec` work. `onExit`, `ProcessHandle.of` for other processes,
  `allProcesses`, `children`, `descendants`, `info` (whose values are empty) and
  `startPipeline` are not implemented.

`scala.sys.process` (`!`, `!!`, `#|`, `#>`, `#&&`, `lazyLines`, `ProcessLogger`) is the Scala
library's, unchanged: its pumping threads are goroutine-backed `Thread`s, its pipes
`java.io.PipedInputStream`s (javalib, as the JDK's), and `System.getProperties` is a
`Properties` object as on the JDK. os-lib (`os.proc(...).call()`, `spawn`, and its file
operations) runs unchanged too (see the `ecosystem` module).

## Networking

`java.net` and `java.net.http` are Scala (javalib) over a native layer on Go's `net` and
`net/http` (`scalago.runtime.Net`, `rt/net.go`, `rt/http.go`), built like the file layer:
addresses are byte arrays, connections, listeners and HTTP exchanges are handles, and failures
are Go errors that the layer classifies (refused, reset, timed out, closed, unknown host, address
in use, TLS, ...) and javalib turns into the JDK's exceptions and messages (`ConnectException:
Connection refused`, `SocketTimeoutException: Read timed out`, `BindException: Address already
in use`).

- **URIs and URLs.** `URI` follows the JDK's parser: RFC 2396 with its deviations (IPv6 literals
  with scope ids, empty authorities and paths), registry- and server-based authorities, the
  JDK's messages and error indices, normalization, resolution, relativization, the quoting of
  the component constructors, `toASCIIString`, and comparisons that ignore the case of escapes.
  It was checked against JDK 21 and 25 on 1,700 cases. `URL` parses as the JDK's stream
  handlers (context URLs, IPv6 hosts, ports, refs) for http, https, file, jar, ftp and mailto
  URLs, and opens the first three. `URLEncoder` and `URLDecoder` take charsets or their names.
- **Addresses.** `InetAddress` parses IPv4 literals (with the C library's short forms: `127.1`)
  and IPv6 literals (IPv4-mapped ones are `Inet4Address`es) as the JDK does, prints and hashes as
  it, and resolves names with Go's resolver (`/etc/hosts`, DNS), IPv4 addresses first as on the
  JDK. `InetSocketAddress` is the JDK's, unresolved addresses included.
- **Sockets.** `Socket` and `ServerSocket` are Go TCP connections and listeners. `setSoTimeout`
  sets a deadline before each blocking read or accept, and the socket stays usable after a
  timeout. Closing a socket from another thread closes the Go connection or listener, which
  ends a blocked read or accept with `SocketException: Socket closed`, as on the JDK. Options
  (`TCP_NODELAY`, off by default as on the JDK; keep-alive, linger, buffer sizes) go to the
  connection. Listening on the wildcard address accepts IPv4 and IPv6 connections.
- **HTTP.** `HttpURLConnection` (from `URL.openConnection`) and `java.net.http.HttpClient` share
  one `net/http` transport: connections are kept alive and reused, requests are HTTP/1.1, no
  proxy is used (the JDK ignores `HTTP_PROXY` too), responses are not decompressed, and TLS is
  Go's `crypto/tls` with the system's root certificates. A request is sent whole when its
  response is first needed (a request body is buffered). `HttpURLConnection` adds the JDK's
  default headers (`User-Agent: Java/<version>`, `Accept: */*`, the form content type of a
  POST), follows redirects within a protocol up to 20 times, and fails as the JDK does
  (`FileNotFoundException` for a 404, "Server returned HTTP response code: 500 for URL: ...",
  with the body in `getErrorStream`). Timeouts are timers that cancel the exchange: the connect
  timeout, the read timeout (for the response's headers and each read of its body), and
  `HttpRequest.timeout`, until the headers arrive (`HttpTimeoutException`). A response's body is
  a stream of the same read native as sockets. `HttpClient` hands it to the response's
  `BodySubscriber` (`BodyHandlers.ofString`, `ofByteArray`, `ofInputStream`, `ofLines`, `ofFile`,
  `discarding`, `ofByteArrayConsumer`, `fromSubscriber`, `BodySubscribers.mapping`, or the
  program's own) on demand: a chunk is read in the thread that requests it, so `send` needs no
  thread of its own (`ofString` reads the body while it subscribes, `ofInputStream` as the
  stream is read). `sendAsync` runs `send` on the client's executor, by default a cached pool of
  daemon threads, as the JDK.

Not like the JDK: requests are HTTP/1.1 even when HTTP/2 is asked for (`HttpClient.Version.HTTP_2`
is the default; responses say `HTTP_1_1`, as the JDK's do from servers without HTTP/2); response
header names are Go's canonical ones (`content-type` arrives as `Content-Type`, so `X-b` and
`x-B` are one field) and come in name order; TLS trusts the system's certificates, not the JDK's
`cacerts`, and `HttpsURLConnection`, `SSLContext`, socket factories and hostname verifiers are
not available; proxies, `Authenticator`, `CookieHandler`, push promises and WebSockets are not
implemented; streaming modes buffer the request body (`setChunkedStreamingMode` sends a
`Content-Length`); `URL.equals` and `hashCode` compare host names without resolving them;
`InetAddress` caches no lookups, resolves no interface names in scope ids (numeric ids work),
and `isReachable` only tries a TCP connection to port 7; a socket stream's `available()` is 0;
`Thread.interrupt` does not end blocking socket operations (on the JDK it closes the socket of a
virtual thread); closing a socket does not interrupt its `connect`; `Socket.bind` takes effect
at `connect`; UDP (`DatagramSocket`), `NetworkInterface`, socket channels and selectors are not
implemented.

## Digests and compression

`MessageDigest` (MD5, SHA-1, SHA-224/256/384/512, SHA-512/224 and /256, with the JDK's names,
aliases and OIDs), `Mac` (HMACs of those) and `SecureRandom` are Scala over Go's crypto packages
(`scalago.runtime.Crypto`, `rt/crypto.go`). A digest in progress is a Go hash, cloned through its
marshalled state; HMAC is written over those hashes so that `Mac.clone` works. `SecureRandom`
reads Go's crypto/rand whatever its algorithm, so seeds add nothing (a seeded SHA1PRNG is not
reproducible, unlike the JDK's).

`java.util.zip` is Scala except for checksums (hash/crc32) and the deflate state machines on
compress/flate (`scalago.runtime.Zip`, `rt/zip.go`). A `Deflater` writes through flate writers
into a buffer of pending output, with the zlib header and trailer written by hand, so a level
change mid-stream ends the current writer's blocks and continues the stream with a new one. Go's
flate reader pulls its input and cannot resume after running out of it, while `Inflater` is fed
piece by piece: the reader runs on a goroutine, as a coroutine of the `inflate` calls (control
passes over channels, so the two never run at once). It reads a byte at a time, never beyond the
compressed data, so `getRemaining()` is exact, which GZIP and zip trailers rely on. A finalizer
stops the goroutine of an inflater that is dropped without `end()`. `GZIP*Stream`, `Zip*Stream`,
`ZipEntry` and `ZipFile` follow the JDK: data descriptors, UTF-8 names, DOS times in the local
zone with extended and NTFS timestamps, concatenated GZIP members, ZIP64 when reading.

Not like the JDK: compressed bytes differ from zlib's (valid data that the JDK reads, but not the
same bytes or sizes); corrupt deflate data gives Go's messages ("flate: corrupt input before
offset N"); an inflater may deliver output at block ends rather than mid-block; `FULL_FLUSH` does
not reset the dictionary; the `FILTERED` strategy is the default one.

## java.time

`java.time` (`javalib/src/java/time`) is [scala-java-time](https://github.com/cquiroz/scala-java-time)
2.9.0, the Scala port of the ThreeTen backport that Scala.js and Scala Native use, vendored as
source (BSD-3-Clause, `javalib/LICENSE-scala-java-time.md`). Its published artifacts were not
an option: the JVM artifact is empty (the JVM has `java.time`), and the Scala.js and Scala
Native ones have `java.time` only as compiled output (their sources are generated by the
build), depend on platform libraries and on scala-java-locales for `java.text`, and would leave
users to add a dependency for part of the JDK. As source, it is part of javalib like
`java.util`, and the changes scala-go needs are in place, marked `scala-go:`:

- **Time zones are Go's.** The tz database is Go's: the system's zoneinfo, or Go's embedded
  copy (`time/tzdata`), which the linker imports (in `internal/scala/tzdata.go`, about 450 KB)
  when a program can reach a named zone (`scalago.runtime.Time.zone`). So binaries that use
  zones also work where there is no zoneinfo (Windows, minimal containers), and programs that
  only use instants, local dates and fixed offsets do not carry it (a `DateTimeFormatter`
  pattern can parse zone IDs, so using patterns reaches it). Without a system zoneinfo, zone
  IDs are parsed as the longest name Go can load. A region's `ZoneRules`
  (`zone/GoZoneRules.scala`) wrap its `*time.Location`, which Go does not let programs walk
  through: Go gives the offset at an instant and the period around it during which the offset
  does not change (`Time.ZoneBounds`). Transitions are the ends of periods with different
  offsets. A `LocalDateTime` is resolved by looking at the periods within 18 hours of it (the
  largest offset): each period whose offset maps the local date-time back into itself gives a
  valid offset, so there is one, none (a gap) or two (an overlap), as with the JDK's rules,
  from the same data, with no copy of the database in Scala. `getStandardOffset` is the offset
  of the latest period that is not daylight saving time.
- **The system zone** (`ZoneId.systemDefault`, `TimeZone.getDefault`) is found as the JDK does
  on Linux: the `TZ` variable, `/etc/timezone`, then the target of the `/etc/localtime` link
  or the zoneinfo file with its contents, and `GMT` when none names a known zone.
- **The clock** has nanosecond precision (`time.Now`), as the JDK's on Linux.
- **No locale data**: month and day names, AM/PM, eras and `ofLocalized*` patterns are those
  of the JDK's English locale (`en_US`, with CLDR's narrow no-break space before AM/PM) whatever
  the locale; numbers use ASCII digits. Zone names (`z`, `zzzz`, `TimeZone.getDisplayName`,
  `Date.toString`) are the tz database's abbreviations ("EST", "CEST"), and `GMT+03:00` where
  the database has none ("+03"), as the JDK writes zones it has no name for.
- Caches that threads would race on are synchronized or built once; JDK 9 to 18 methods that
  the backport lacks are added (`LocalDate.ofInstant`, `Duration.truncatedTo`, `toXxxPart`,
  `dividedBy(Duration)`, `Instant.until(Instant)`, `LocalDate.datesUntil`, ...); exception messages
  are the JDK's; `LocalDate.plusDays` stays within the month when it can, as the JDK does.
- `java.util.Date` (an instant in milliseconds, `toString` in the default zone), `TimeZone` on
  java.time's zones, and the base classes of `java.text` that the API mentions (`Format`,
  `ParsePosition`, `FieldPosition`, `ParseException`) come with it.

Not like the JDK: no long zone names ("Eastern Standard Time"), locale-specific texts or
patterns; the Japanese calendar is missing (the other non-ISO calendars are there); `getTransitions`
lists transitions up to 2037 and `getTransitionRules` is empty; daylight saving time is what
the zoneinfo files say, so in zones that use negative DST (Europe/Dublin's winter time) the
standard offset can differ from the JDK's; `ZoneId.getAvailableZoneIds` lists the system's
zoneinfo (it is empty where there is none, though every zone can still be loaded);
`DateTimeFormatter`s parsing zone names (`z`) choose among the zones sharing an abbreviation
arbitrarily; the deprecated calendar methods of `Date`,
`Calendar` and `SimpleDateFormat` are not implemented.

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
  Go values use a global table, whose entries are never removed. An uncontended
  enter/exit pair costs about 55 ns.
- **Goroutine ids.** Monitors, `Thread.currentThread` and interruptible channel operations need
  the current goroutine's id, which Go does not expose. On amd64 and arm64 the runtime reads it
  from the goroutine's runtime structure (`rt/goid.go`: a two-instruction assembly `getg`, and
  the field's offset, found at startup by matching the ids that `runtime.Stack` reports for
  several goroutines), which costs about 4 ns. If the offset is not found (a future Go version
  laying the structure out differently, or another architecture), it falls back to parsing
  `runtime.Stack` (about 3.5 µs); a runtime unit test fails in that case on amd64 and arm64.
- **Volatile fields** (`@volatile`, and the state field of each `lazy val`) are sequentially
  consistent, which is what the JMM requires of volatiles. Fields of primitive types are
  `sync/atomic` values (`rt.VolatileInt32`, `rt.VolatileInt64`, `rt.VolatileBool`, ...), which
  never allocate. Go cannot access an interface atomically, so a field of a reference type is an
  `rt.Volatile[T]`, an atomic pointer to an immutable box holding the value: a store allocates a
  box, except for null and when it stores the object it stored last (a lock's owner alternating
  between a thread and null), whose box it reuses.
- **Atomic intrinsics.** javalib's atomics and concurrent classes do atomic read-modify-write
  operations on volatile fields through `scalago.runtime.Intrinsics`: `compareAndSet`,
  `getAndSet` and `getAndAdd` (the first argument must be the field, or its getter), which the
  plugin turns into IR `CompareAndSet`, `GetAndSet` and `GetAndAdd`, and the linker into the
  `sync/atomic` operations (`CompareAndSwap`, `Swap`, `Add`). Rarer operations (`getAndBitwiseOr`,
  ...) are compare-and-set loops. Array elements are accessed atomically through
  `scalago.runtime.AtomicArrays`: `Int` and `Long` arrays in place, arrays of references as Go
  slices of atomic pointers (`scalago.runtime.VolatileArray`).
- **Benign races.** The JDK's concurrent classes read some plain reference fields racily, which
  the JMM makes safe but Go does not (a torn read). In the ported classes such fields are
  volatile: the tables of `ConcurrentHashMap` and the cells of `LongAdder` are `VolatileArray`s,
  and fields such as a lock's owner thread, the waiters of `AbstractQueuedSynchronizer` and the
  actions of `CompletableFuture`'s stages are `@volatile`. All of `java.util.concurrent` is
  exercised under the race detector by the test cases.
- **Lazy vals** follow Scala 3's scheme: compare-and-set on the volatile field, and goroutines
  that find the value being computed wait on a `Waiting` marker (a monitor).
- **Threads.** `java.lang.Thread` runs on a goroutine. `start`, `join` (with timeouts),
  `sleep`, `interrupt`, `currentThread`, names, daemon status, priorities, thread groups (names
  and handlers), `getState`, `holdsLock`, builders (`Thread.ofPlatform()`, `Thread.ofVirtual()`)
  and uncaught-exception handlers work. Every thread is goroutine-backed, so virtual threads are
  threads like the others (daemons, unnamed by default, `isVirtual`), and Go's scheduler
  multiplexes all of them. A goroutine that did not start as a `Thread` (the main goroutine, or
  one started by Go code) gets a `Thread` object the first time it asks for one, and keeps it
  for as long as the goroutine runs. Go does not tell when a goroutine ends, so when the number
  of such `Thread`s doubles, the runtime drops those of ended goroutines (from a full stack
  dump's goroutine ids, which are never reused): a Go server calling Scala code from a goroutine
  per request does not accumulate them. `Thread.currentThread` is a lookup by goroutine
  id, through a direct-mapped cache (an atomic load and a comparison). As on the JVM, `Main()`
  waits for the non-daemon threads before returning, and an exception in a thread is reported
  (`Exception in thread "name" ...`) without ending the program.
- **Thread locals.** As in the JDK, each `Thread` holds a table of its `ThreadLocal` values, and
  a new thread copies its creator's `InheritableThreadLocal`s (through `childValue`). Tables
  hold their keys strongly, so a value lives until `remove` or the end of its thread.
- **Parking** (`LockSupport`), the basis of `java.util.concurrent`'s synchronizers: each thread
  has a permit, a one-slot Go channel. `park` takes it or blocks until `unpark` gives it, the
  thread is interrupted (which leaves the interrupt status set, as on the JVM) or the timeout
  expires. Parked threads report `WAITING`/`TIMED_WAITING` from `getState`.
- **`java.util.concurrent`** is Scala Native's port of JSR-166 (Doug Lea's public-domain code),
  adapted to the intrinsics above: `AbstractQueuedSynchronizer` and the locks and synchronizers
  built on it (`ReentrantLock` and its conditions, `ReentrantReadWriteLock`, `Semaphore`,
  `CountDownLatch`, `CyclicBarrier`, `Phaser`, `Exchanger`), `StampedLock`, the atomics and
  adders, executors (`ThreadPoolExecutor`, `ScheduledThreadPoolExecutor`, `Executors`,
  `FutureTask`), `CompletableFuture`, `ConcurrentHashMap` (with the JDK's table layout, so the
  same iteration order), and the concurrent queues and deques. Three classes are written for
  scala-go instead: `CopyOnWriteArrayList`/`CopyOnWriteArraySet` (Scala Native's are
  single-threaded), `ConcurrentSkipListMap`/`Set` (a `TreeMap` under a lock shared by all its
  views, with snapshot iterators that never throw `ConcurrentModificationException`, rather
  than a lock-free skip list), and `ForkJoinPool`.
- **`ForkJoinPool`.** The JDK's pool is a large lock-free design for platform threads; Go's
  scheduler already does much of its work. scala-go's keeps its API and behavior on a simpler
  structure: workers are goroutine-backed `ForkJoinWorkerThread`s (from the pool's factory, so
  Scala's global `ExecutionContext` installs its `BlockContext` as on the JVM), each with a deque
  under a spin lock (`fork()` pushes there; the owner takes LIFO, or FIFO in async mode; idle
  workers steal FIFO), plus a lock-free queue for submissions from other threads. Workers are
  started on demand up to the parallelism, park when idle (registering first, then scanning
  once more, so that no wake-up is lost) and exit after the keep-alive time. Blocking is
  compensated: a worker in `managedBlock` (Scala's `blocking {}`) or blocked in `join` does not
  count against the parallelism, so another worker runs the queued tasks. `join` helps: it runs
  the joined task if it is still queued, then other local or stolen tasks, before blocking.
  `ForkJoinTask`, `RecursiveTask`, `RecursiveAction` and `CountedCompleter` are Scala Native's.
  `ForkJoinPool.commonPool()` has `availableProcessors() - 1` workers, as on the JVM.
- **`scala.concurrent`** runs unchanged on these: `ExecutionContext.global` is a
  `ForkJoinPool`, `Await` uses `AbstractQueuedSynchronizer`, and `blocking {}` is a managed
  block.
- **`Runtime`.** `availableProcessors` is `GOMAXPROCS`.
- **Performance** (24 cores, warm JVM 25 for comparison; a loaded machine, so ranges): an
  uncontended `ReentrantLock` lock/unlock takes about 95 ns (JVM 20–30), `synchronized` about
  50 ns, `AtomicInteger.incrementAndGet` about 10 ns (as the JVM). A chain of 10⁵
  `Future.map`s runs at 0.7–1.1 µs per step (JVM 0.4–0.7), `Future(x + 1)` chained through
  `flatMap` at 2.3–3.9 µs (JVM 0.6–2.1), `Future.sequence` of 10⁵ futures at 3–4.7 µs per future
  (JVM 2–3.8). `ConcurrentHashMap.merge` from 8 threads on 1,000 keys takes about 120 ns (JVM
  55–75), `get` 13–16 ns (JVM 5–25). `LongAdder` from 8 threads is the weak spot, 45–120 ns
  (JVM about 10).
- **Runtime state.** The stdout buffer (one lock per write, so lines do not interleave), string
  interning, the `Class` table and `String.hashCode`'s cache are goroutine-safe.

What is *not* guaranteed: ordinary fields are plain Go memory, so a data race on them is a bug,
as on the JVM. But the JMM gives racy programs some guarantees, and Go does not: a racy read of a
reference (an interface or a slice header in Go) can see a torn value and crash. `final` fields
get no special publication guarantee either: share objects through `synchronized`, volatiles,
atomics, `Thread.start`/`join` or Go channels, which are all happens-before edges. Programs can
be checked with Go's race detector (`go build -race`). The `concurrency` test case runs under it.

## gears

The concurrency API is [gears](https://github.com/lampepfl/gears), used as published. Its JVM
implementation runs each `Future` on a virtual thread and suspends by blocking it, with
`ReentrantLock`/`Condition`. Under scala-go a virtual thread is a goroutine-backed
`java.lang.Thread`, so the same code runs on goroutines:

- gears is compiled from its TASTy like any dependency. The `scalago-gears` module overrides
  `gears.async.VThreadScheduler`, the one class that needs `Thread.ofVirtual` and a `VarHandle`.
  `import gears.async.default.given` works unchanged.
- javalib's `java.util.concurrent` provides what gears uses: `ReentrantLock` and `Condition`
  (on `AbstractQueuedSynchronizer` and parking), atomics, `ConcurrentLinkedQueue` and
  `TimeUnit`. Cancellation interrupts the thread (`Thread.interrupt`),
  which wakes sleeps, waits, conditions and blocked Go channel operations.
- Go integration (`scalago.gears`): `GoChan.awaitReceive`/`awaitSend` are cancellable channel
  operations in gears code, and `withGoContext(ctx)(body)` cancels `body` when a Go
  `context.Context` is done. Exported code uses this to honour its Go callers' contexts.

The `gears` test case runs the same program on JVM virtual threads and on goroutines (also under
the race detector). `gears-go-context` cancels gears code with a Go deadline.

## Semantics handled explicitly

- **JVM numeric semantics:** wrapping arithmetic, masked shift counts, `>>>`, saturating
  float-to-int conversions, and float `%`. Literals are folded where Go would reject constant
  overflow.
- **Equality and strings:** `==` is null-safe and numerically cooperative (`rt.Equals`), and
  identity on Java enums (whose `equals` is final), including Scala 3 enums extending
  `java.lang.Enum`. `##` matches `Statics.anyHash`. `toString` matches the JDK, including `Double.toString`. String
  `hashCode` and `compareTo` work on UTF-16.
- **Scala 3 enums and tuples.** The cases of an enum extending `java.lang.Enum` are static
  fields of the companion object's class, set by its constructor (the JVM's static
  initializer): reading one constructs the object first. Scala 3 compiles tuples of primitives
  to the Scala 2-specialized classes of the JVM's library (`Tuple2$mcJD$sp`, `_1$mcJ$sp`), which
  the library's TASTy does not have: the plugin uses the generic classes, with boxing.
- **`Enumeration` names.** On the JVM, an `Enumeration` value created without a name
  (`val Mon, Tue = Value`) is named after the public `val` holding it, which `Enumeration` finds
  by reflection. Scala.js's compiler rewrites such `Value` calls into `Value(name)`; scala-go's
  plugin instead follows the assignment of each such field in a subclass of `Enumeration`
  (`EnumerationNames`) with a call registering the name, `scalago$registerName`, which javalib's
  `Enumeration` (the Scala library's, without reflection) records. So, as on the JVM, every value
  held by a public `val` gets its name, also values made with `new Val` or a subclass of it
  (`case class PlanetVal(...) extends Val`), an explicit name or `nextName` still wins, and other
  values print `<Invalid enum: no field for #i>`. This happens in `genGoIR`, after the fields are
  assigned in constructors, so the JVM bytecode is unchanged, and it also applies to libraries
  compiled from TASTy (`BigDecimal.RoundingMode`). Values held by `val`s of traits are not named.
- **`BigDecimal`** (`rt/bigdecimal.go`) is written in Go to the JDK's specification, like the
  JDK with a `long` fast path: money-like values need no `math/big` and one allocation per
  operation. Results, scales, string forms, exception messages (down to `"/ by zero"` versus
  `"BigInteger divide by zero"`, which depends on the JDK's internal representation) and
  `hashCode` match the JDK; this was checked against JDK 25 on 700,000 random operations
  (`MathContext` arithmetic in every rounding mode, `sqrt`, `pow`, conversions, parsing, extreme
  scales). Where JDK versions differ, it follows the newest: `sqrt` is correctly rounded in every
  rounding mode (JDK 21's is not for the directed modes), and `valueOf(NaN)` says `Infinite or
  NaN`. `doubleValue`/`floatValue` round correctly, through `strconv.ParseFloat` beyond the fast
  paths. `MathContext`s reach the natives through one generated call that packs them into an
  `int64` (`MathContext.goBits`). `scala.math.BigDecimal` is the Scala library's, unchanged.

## Status

Supported and covered by end-to-end tests:
- classes and traits (fields, `super[T]`, self types)
- objects and `@main`
- exceptions (`try`/`catch`/`finally`, runtime errors), with JVM-like stack traces
- pattern matching, including case classes, `Option`, tuples, guards, and switches on ints and
  strings
- lambdas (captured `var`s, SAM types, `andThen`/`compose`)
- `lazy val`
- standard-library collections (`List`, `Vector`, `Map`, ranges, `sorted`, `groupBy`, ...),
  string interpolation, `StringBuilder`, `BigInt`
- `scalago.go.GoString`
- `java.util`: lists, deques, queues, hash, linked and tree maps and sets (with their iteration
  orders, navigation, views and fail-fast iterators), `Collections`, `Arrays` for every
  primitive type, `Objects`, `Optional*`, `Random`/`SplittableRandom` sequences, `UUID`,
  `StringJoiner`, `StringTokenizer`, `BitSet`, `Base64`, summary statistics, `Comparator`
  combinators, `java.util.function`, `scala.jdk.CollectionConverters` both ways, and Scala
  classes extending `AbstractList`/`AbstractMap`
- `java.util.stream`: reference and primitive streams from collections, arrays, ranges,
  `iterate`/`generate`/`concat`/builders, `Optional`, `BitSet`, strings (`chars`,
  `codePoints`), readers and files (`lines`, `Files.list`/`walk`/`find`), `Random` and
  `Pattern`; every intermediate and terminal operation, `Collectors` (grouping, partitioning,
  maps, joining, statistics, `teeing`, ...), laziness and short-circuiting as on the JDK, close
  handlers, and `scala.jdk.StreamConverters`
- Go interop: handwritten and generated facades of standard-library packages and of a local Go module, Go interfaces
  implemented in Scala, errors and callbacks, Go slices, maps, channels, pointers and funcs
  shared with Go, and generic Go functions. Mismatched facades are tested as link errors.
- gears: futures, races, cancellation, timeouts, channels, and Go channels and contexts, on
  goroutines (and the same program on JVM virtual threads)
- exports: a Scala library used by a Go program (functions, an exported class hierarchy,
  errors, panics, callbacks, identity, and calls from 100 goroutines under the race detector)
- `java.time`: dates, times, instants, durations, periods, time zones with DST gaps and
  overlaps, `DateTimeFormatter` patterns and ISO formats, parsing and its exceptions, adjusters,
  `java.util.Date` and `TimeZone`
- regular expressions: `scala.util.matching.Regex`, `java.util.regex` and the regex `String`
  methods, on Go's `regexp` (RE2), with Java's UTF-16 offsets and replacement syntax
- `java.math.BigDecimal` (the whole API, with `MathContext` and `RoundingMode`) and
  `scala.math.BigDecimal`, with its rounding modes and ranges
- `scala.Enumeration`: names, `values`, `withName`, `ValueSet`s, ordering, `Val` subclasses
- I/O: java.io's streams, readers and writers (buffered, data, pushback, print, string and
  array ones), charsets (UTF-8, ISO-8859-1, US-ASCII, with the JDK's malformed-input handling),
  files through `java.io.File`, file streams, `RandomAccessFile` and `java.nio.file`
  (`Files`, `Path`, `walkFileTree`, `lines`, `walk`, the JDK's exceptions and messages), standard input
  (`StdIn`, `System.in`, end of input), `Console.withOut`, `System.setOut`, `scala.io.Source`,
  `scala.util.Using`, system properties, environment, exit statuses and shutdown hooks;
  java.nio buffers of every type with views and byte orders, the charset coders on buffers,
  file channels (positional I/O, transfers, locks, mappings), `Channels`, glob and regex path
  matchers
- networking: `URI` (parsing, errors, resolution, relativization, normalization, component
  constructors), `URL` (parsing, context URLs, `file:` and `http:` connections), `URLEncoder` and
  `URLDecoder`, `InetAddress` literals and `InetSocketAddress`; sockets on the loopback interface
  (echo, shutdowns, options, read and accept timeouts, closing from another thread, refused
  connections, addresses in use, bulk transfers); `HttpURLConnection` (methods, request
  properties, bodies, error statuses and streams, redirects, chunked responses, read timeouts)
  and `HttpClient` (body publishers and handlers, redirects, timeouts, `sendAsync`, request
  validation) against an HTTP server in the program; all also under Go's race detector
- digests (`MessageDigest`, `DigestInput/OutputStream`), HMACs, `SecureRandom`, `HexFormat`;
  `java.util.zip`: CRC32, CRC32C, Adler32, `Deflater`/`Inflater` (raw and zlib, flush modes,
  dictionaries, level changes), the deflater and inflater streams, GZIP,
  `ZipInputStream`/`ZipOutputStream`/`ZipFile`, and data compressed by the JDK
- subprocesses: `ProcessBuilder` (redirects, environment, directory, merged errors), `Process`
  (pipes, interactive use, timeouts, exit statuses, signals), `Runtime.exec`, and
  `scala.sys.process` (`!`, `!!`, pipes, `lazyLines`, loggers, `#>`/`#<`, `#&&`/`#||`), also
  under Go's race detector
- concurrency: threads, `synchronized`, `wait`/`notify`, volatile fields, atomics, interrupts,
  and concurrent first use of modules and lazy vals; thread locals, thread builders and virtual
  threads; `scala.concurrent` (futures, promises, `Await`, `blocking`, the global
  `ExecutionContext` and ones on executors); `java.util.concurrent`: executors and scheduled
  executors, `ForkJoinPool` with recursive tasks, `CompletableFuture`, locks and synchronizers,
  concurrent maps, queues and copy-on-write collections, atomics and adders (all also under
  Go's race detector)

Known gaps:
- Regular expressions that RE2
  cannot run (lookaround, backreferences, possessive quantifiers), `String.format` beyond the
  common conversions, the parts of I/O listed in [I/O](#io), and those of `java.time` listed
  in [java.time](#javatime).
- `java.util`: `Stream.gather` (Java 24's gatherers), the concurrent collectors
  (`groupingByConcurrent`, `toConcurrentMap`, waiting for `java.util.concurrent`'s maps),
  parallel execution of parallel streams, the reversed views of
  sequenced maps (`LinkedHashMap.reversed()`, `sequencedKeySet()`, ...), `Formatter`,
  `Calendar`/`Timer`, `EnumSet`/`EnumMap`, `ServiceLoader`,
  `Arrays.compare`/`mismatch` and the range forms of `Arrays.equals`, and `Properties` keys
  continued over several lines.
  Arrays of references do not know their element type, so `toArray(new Array[String](0))` and
  `Arrays.copyOf` return arrays whose `getClass` is `Object[]`, and an array of references is
  an `Array[Unit]` (to type tests, which the Scala library makes before those for
  `Array[AnyRef]`) when it is not empty and holds only `()`.
- Scala libraries are compiled from their TASTy, so classes of a library written in Java (which
  have none) are missing; natives cover those of popular libraries that reachable code uses
  (ujson's float formatting). `WeakHashMap` holds its keys strongly (Go's weak pointers need
  Go 1.24).
- Locales: there is no locale data. `java.util.Locale` carries identifiers only, and every
  locale formats and maps case as `Locale.ROOT` does (so `String.format(Locale.GERMANY, "%.1f",
  x)` uses a `.`).
- Reflection: `getClass`, `Class` names and `isAssignableFrom` work (for the latter, the linker
  gives the runtime the program's class hierarchy when the program uses it); nothing else.
- `BigInteger` and `BigDecimal` cannot be subclassed (they are Go values).
- Networking: the parts listed in [Networking](#networking) (HTTP/2, proxies, cookies,
  authentication, `HttpsURLConnection` and the TLS configuration classes, WebSockets, UDP,
  network interfaces, socket channels and selectors).
- Security and compression: SHA-3 and MD2 digests, `Cipher` and the rest of `javax.crypto` beyond
  `Mac`, providers (`getProvider`, `Provider`), reproducible seeded `SecureRandom`s; writing ZIP64
  archives, encrypted zip entries, `java.util.jar`'s `JarFile`/`JarInputStream`.
- Concurrency: the atomic field updaters (`AtomicIntegerFieldUpdater`, ... need reflection),
  `SubmissionPublisher`, thread groups beyond names and handlers (`activeCount`, `enumerate`),
  `Thread.getAllStackTraces`.

When reachable code uses something missing, linking fails with a "called from" chain naming
the API. Reachability over-approximates what runs (virtual calls are resolved by name), so a
program can fail to link because of code it never runs. With `--missing-apis throw` (Mill:
`goMissingApis`), such uses are warnings instead, and each throws the JVM's linkage error
(`NoClassDefFoundError`, `NoSuchMethodError`, `NoSuchFieldError`; `UnsupportedOperationException`
for code the plugin could not translate) where it is used, if it runs. These are `LinkageError`s,
so `NonFatal` does not catch them, as on the JVM.

## Roadmap

The goal is full two-way interop between Scala and Go, as Scala.js has with JavaScript. Scala
calls Go (facades), and Go calls Scala (exports, library builds). Both share data without
copying wherever the representations allow. Scala.js's features map to Go as follows:

| Scala.js                                  | scala-go                                                  | Status |
|-------------------------------------------|-----------------------------------------------------------|--------|
| `@JSImport` native objects and classes    | `@GoImport` facades of packages and types                 | done   |
| facades checked against the target only at run time | facades checked against Go's API at link time   | done   |
| non-native JS classes (Scala classes JS can use) | Scala classes implementing Go interfaces           | done   |
| `js.Array`, `js.Dictionary`, `js.Function`, `js.Promise` | `GoSlice`, `GoMap`, Go funcs, `GoChan`/gears | done   |
| `@JSExportTopLevel`, `@JSExport`          | `@GoExport` functions, objects and classes                | done   |
| ES module output, module splitting        | library builds: a Go package other Go code imports        | done   |
| ScalablyTyped (facades generated from .d.ts) | facades generated from Go packages (`goapi`)           | done   |

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
5. **Concurrency API: gears on goroutines** (done, see [gears](#gears)). gears runs as published,
   compiled from its TASTy, with one class overridden: futures on goroutines, cancellation by
   thread interruption, cancellable Go channel operations, and Go contexts cancelling gears
   code. This needed steps 2 and 3. It also brought Scala library dependencies in general
   (compiled from TASTy) and regex-based `String` methods on Go's `regexp`.
6. **Facade generation** (done, see [Generated facades](#generated-facades)) from Go packages'
   APIs (`goapi`), with the step 3 types. `ScalaGoModule.goFacadePackages` generates facades for
   any Go package, and `scalago-gostd` publishes those of 21 standard library packages.
7. **Library coverage** and **publishing**, in parallel with the steps above. Done so far:
   regular expressions on Go's `regexp` (`java.util.regex`, Scala's `Regex`),
   `java.util.concurrent` and `scala.concurrent`, `java.util` (collections and utilities), Scala
   library dependencies compiled from TASTy, `BigDecimal`, I/O (files, streams, charsets,
   stdin), JVM-like stack traces, `java.util.stream`, `java.time`, networking (sockets, HTTP),
   digests and compression. Still to do: publishing
   (organization `io.github.scalago` is a
   placeholder).

## Open questions

- **Missing members.** Unresolvable but statically reachable members are link errors by default,
  and runtime linkage errors with `--missing-apis throw`. Should `throw` become the default once
  javalib is broad enough that missing APIs are rare in practice?
- **Facades on the JVM.** Facade members throw on the JVM, so programs using Go cannot be run or
  tested there. Facades could take an optional JVM implementation.
- **Errors in generated facades.** A trailing `error` is thrown, which suits most functions but
  not all. Generated facades could instead return `(T, GoError)` everywhere and leave the choice
  to the caller, or be configurable per member.
- **Shared or copied slices.** `Array[T]` ↔ `[]T` shares memory only when the representations
  match, and `GoSlice[T]` always does. Should `Array` conversions stay as a convenience (current
  behavior) or require `GoSlice`?
