# scala-go design

scala-go compiles Scala 3 to Go with the architecture of Scala.js and Scala Native: a compiler
plugin emits IR per class, and a whole-program linker turns all IR into one Go module.

## Pipeline

```
 .scala ──scalac + plugin──▶ .class + .tasty + .goir        (per module, incremental)

 scala-library.jar (TASTy) ──scalac -from-tasty + plugin──▶ stdlib .goir     (built once)
 javalib/ (Scala sources)  ──scalac + plugin─────────────▶ java.* .goir      (built once)

            all .goir on the classpath (program + javalib + stdlib)
                                     ▼
          linker: lambdas → classes, reachability, Go codegen ──▶ Go module ──go build──▶ binary
```

| Component       | Code                         | Role                                                    |
|-----------------|------------------------------|---------------------------------------------------------|
| Compiler plugin | `plugin/`                    | phase `genGoIR`: one `.goir` per class, next to its `.class` |
| IR              | `ir/`                        | typed, erased trees; methods identified by name and erased signature, as on the JVM; binary format with a string table and a format version |
| Library IR      | `mill-build/.../TastyIR.scala` | IR of the Scala library and Scala dependencies, recompiled from TASTy |
| javalib         | `javalib/`                   | the JDK, written in Scala                               |
| Linker          | `linker/`                    | links all IR into a Go module (`Linker.link`; command line `scalago.linker.Main`) |
| Runtime         | `runtime/rt`                 | Go support code, bundled in the linker jar and copied into every module as `internal/rt` |
| goapi           | `runtime/goapi`              | helper the linker `go run`s to read Go package APIs     |
| Mill plugin     | `mill-build/src/scalago/mill/ScalaGoModule.scala` | adds the compiler plugin; `goLink`, `goBuild`, `goRun`. Links in its own JVM: Mill runs on Scala 3.8, the linker on 3.9 |

### Compiler plugin

Phase `genGoIR` (`ScalaGoPlugin`, `GoIRCodeGen`) runs after `repeatableAnnotations`, the last
tree transform, and before `genBCode`, which still runs. Trees are erased, pattern matching is
lowered to labeled blocks, lambdas are lifted, classes flattened and mixins expanded: the plugin
translates a small Java-like core. The plugin jar includes the IR sources, so `-Xplugin:<jar>`
is self-contained.

| Option                          | Effect                                                         |
|---------------------------------|----------------------------------------------------------------|
| `-P:scala-go:lenient`           | untranslatable code becomes `Unsupported` IR instead of a compile error; linking fails only if it is reachable |
| `-P:scala-go:rename:a.b:c.d`    | emits classes of package `a.b` (and subpackages) as `c.d`      |
| `-P:scala-go:static-forwarders` | adds static forwarders for objects' methods, as the JVM backend does (javalib) |

The plugin uses compiler internals, so it targets exactly one Scala version (3.9.0,
`ScalaGoModule.ScalaVersion`) and is published with the full version as suffix.

### Libraries from TASTy

The Scala library ships TASTy (since 3.8). The build recompiles it with `-from-tasty` and the
plugin in lenient mode, stopping after `genGoIR`: about 20 s, about 2,800 classes.
`scala.quoted` is skipped; the compiler cannot recompile it from TASTy.

Scala 3 dependencies go the same way (`TastyIR`, `ScalaGoModule.scalaGoDependencyIR`): every jar
with TASTy and no IR. Published libraries such as gears need no scala-go artifact, unlike
Scala.js and Scala Native, where each library is published per platform.

A library that needs JVM-only APIs is patched by overriding single classes. A module compiled
with `-P:scala-go:rename` emits replacements under the library's names, and its IR comes first
on the link classpath ([gears](#gears)).

Link classpath order, where the first definition of a class wins (`ScalaGoModule.goLink`): the
module's own IR and plugin-compiled dependencies, dependencies' IR from TASTy, javalib, the
Scala library's IR.

### javalib

The JDK in Scala; [The JDK](_docs/libraries/jdk.md) lists its coverage. It also replaces the
Java-written classes of the Scala library (`*Ref`, `LazyVals`, `JProcedureN`, `ScalaNumber`) and
`Enumeration`, which uses reflection ([Semantics](#semantics-handled-explicitly)).

- **Package names.** Packages other than `java.lang` compile as themselves (`package
  java.util`). A class compiled from source takes precedence over the JDK's class file, so
  javalib and the JDK it type-checks against agree on one symbol per class. For the same reason
  javalib is always compiled whole: classes of a previous compilation lose against the JDK's.
- **`java.lang`.** The compiler refuses to redefine core `java.lang` classes. Their sources live
  in `scalago.javalib.lang` and `scalago.javalib.hijacked.lang` (the Scala library's in
  `scalago.scalalib`), and `-P:scala-go:rename` maps them back.
- **Static members** are methods of Scala objects. `-P:scala-go:static-forwarders` adds static
  forwarders to the companion class (or a mirror class), as the JVM backend does, except that
  only a method with the same signature suppresses one: JDK classes overload static and
  instance methods (`Year.isLeap(long)`, `isLeap()`).
- **Only linked.** The javalib jar must never be on a compile or run classpath.

Much of `java.util`, `java.util.concurrent` and `java.io` is adapted from Scala Native's javalib
(Apache-2.0, `javalib/LICENSE-scala-native.md`; each such file says so), changed where programs
can observe a difference from the JDK:

- `HashMap` lays out its table as the JDK does (capacities, growth, bucket order), so iteration
  orders match, except in buckets of more than 8 colliding hash codes, which the JDK turns into
  trees. `HashSet`, `LinkedHashSet` and `Hashtable` follow it.
- Iterators are fail-fast (`modCount`), also in programs' subclasses of `AbstractList`.
- `Random`, `SplittableRandom` and `Collections.shuffle` produce the JDK's sequences.
- `Arrays` operations on primitive arrays are natives: generic Go functions instead of Scala
  Native's specialization.

**java.util.stream** has Scala Native's interfaces and the JDK's design. (Scala Native's
implementation pulls each element through a chain of spliterators, a closure per element and
stage.)

- A stage (`AbstractPipeline`, four element types) wraps the next stage's sink in its own. A
  terminal operation pushes the source's elements through the sinks with one
  `forEachRemaining`, or `tryAdvance` until cancellation when it short-circuits. `iterator()` and
  `spliterator()` pull through the same sinks, one source element at a time.
- A traversal allocates one sink per stage and nothing per element. Primitive streams stay
  unboxed: `IntStream.range(0, n).sum()` is a loop of interface calls.
- Pipelines track the JDK's flags (sized, sorted, distinct, short-circuiting, size-adjusting), so
  observable behavior matches: `peek` order, `sorted` as a barrier, `count()` without traversal
  for known sizes (also through `skip`/`limit`), `sorted()`/`distinct()` passing through sorted
  or distinct sources, short-circuits into `flatMap`'s streams, "already operated upon or
  closed" errors, close handlers, compensated `double` sums.
- `Collectors.groupingBy` uses `computeIfAbsent` on a `HashMap`, so groups iterate in the JDK's
  order. Parallel streams run sequentially; `parallel()` only sets `isParallel()`.
- `String.chars()`/`codePoints()` are `CharSequence` default methods: a method of a hijacked
  class without a native falls back to an interface's default method (`Program.hijackedDefault`).

### Linker

In `linker/src/scalago/linker/`:

| Step         | Code                         | What it does                                       |
|--------------|------------------------------|----------------------------------------------------|
| Loading      | `IRLoader`, `Program`        | reads all `.goir` from the classpath; the first definition of a class wins. Where an entry has class files, IR without its class file is stale (incremental compilation deletes class files, not IR) and ignored |
| Lambdas      | `Lambdas`                    | `Closure` nodes become synthetic classes whose single abstract method calls the lifted target, with boxing and cast adaptation; bridges for other abstract methods of the same name, as `LambdaMetafactory` adds |
| Reachability | `Analyzer`                   | worklist from the entry point; virtual calls resolve by name over instantiated classes (over-approximating, as Scala.js); only reachable classes and methods are emitted; errors carry a "called from" chain |
| Natives      | `Natives`                    | Go implementations of JDK methods (`String`, boxes, `Math`, `System`, `Character`, `BigInteger`, `BigDecimal`, `Arrays`, ...); they take precedence over IR |
| Emission     | `ClassEmitter`, `FunctionEmitter`, `LinkContext`, `Mangling`, `LineDirectives` | [Object model](#object-model), [Control flow](#control-flow) |
| Go interop   | `GoInterop`, `GoApi`         | [Go interop](#go-interop)                          |
| Exports      | `Exports`                    | [Exports](#exports-and-library-builds)             |
| Facades      | `FacadeGen`, `FacadeIndex`   | [Generated facades](#generated-facades)            |

- **Runtime objects.** javalib reaches the Go runtime through `scalago.runtime` objects whose
  methods throw on the JVM and are natives when linked: `Threads`, `AtomicArrays`, `Regexes`,
  `Console`, `OS`, `Process`, `Subprocesses`, `Net`, `Crypto`, `Zip`, `Time`, `StackTraces`.
- **Hijacked-class members in Scala.** `String` is a Go value with native methods. Members
  easier in Scala (`lines()`, `CASE_INSENSITIVE_ORDER`, `join(delimiter, Iterable)`) are in
  javalib's `java.lang.String$` (`scalago.javalib.hijacked.lang.String`). A static member without
  a native is the object's; an instance method without a native is the object's method taking
  the receiver first (`Program.staticMethodViaCompanion`, `hijackedCompanionMethod`). This works
  for any hijacked class.
- **Static fields.** Java static fields (`Collections.EMPTY_LIST`, `Attributes.Name.MAIN_CLASS`)
  are usually companion-object members in javalib. Reading a static field a class lacks calls
  the companion's getter of the same name and type (`Program.staticFieldViaCompanion`).

## Object model

| Scala                            | Go                                                              |
|----------------------------------|-----------------------------------------------------------------|
| `Int`, `Long`, `Double`, ...     | `int32`, `int64`, `float64`, ... (`Char` = `uint16`)            |
| boxed primitive                  | the Go primitive inside an `any`                                |
| `String`                         | `*rt.String`: UTF-16 code units, like the JVM (`nil` = null)    |
| `scalago.go.GoString` (opt-in)   | Go `string` (UTF-8); UTF-8 bytes on the JVM ([Go strings](#go-strings)) |
| `BigInteger`                     | `*rt.BigInteger` (`math/big`)                                   |
| `BigDecimal`                     | `*rt.BigDecimal`: a scale and an `int64` unscaled value, or a `big.Int` when it does not fit |
| Go facade of a struct type `T`   | `*T` inside an `any` ([Go interop](#go-interop))                |
| Go facade of any other Go type   | the Go value inside an `any`                                    |
| `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go slice, map, channel or pointer inside an `any` |
| `Array[prim]` / `Array[AnyRef]`  | `*rt.Array[prim]` / `*rt.Array[any]` (covariance works)         |
| class `C`                        | struct `S_C` embedding the superclass's struct; root classes embed `rt.ObjectHeader` (the monitor) |
| reference of static type `C`     | interface `C_C`: embeds the parents' interfaces, lists the methods called on `C` receivers |
| trait `T`                        | interface `C_T`, with a marker method `I_T()`                   |
| method body                      | free function `Impl_C__m(this C_C, ...)` (`this *S_D` if only class `D` can be `this`) |
| virtual method                   | forwarding method on each instantiated struct                   |
| `object O`                       | accessor `Mod_O_S()` returning a `*S_O_S`, constructed on first use under an `rt.InitLock` |
| `@static` fields, `<clinit>`     | package variables, plus `Clinit_C()`, run once under an `rt.InitLock` |
| `@volatile` field                | `sync/atomic` value (`rt.VolatileInt32`, ...), or `rt.Volatile[T]` for references |

Names are mangled injectively (`Mangling`): `$` becomes `_S`, `.` `_d`, `_` `_u`.

- **Calls through `this` stay virtual.** Method bodies take `this` as an interface, so calls on
  it stay virtual in inherited code, which Go's struct embedding alone cannot do.
- **Exact receivers.** When only one instantiated class `D` is or extends `C` (objects, lambdas,
  case classes and other leaves, traits with one implementation), `C`'s method bodies take
  `this` as `*S_D` (`LinkContext.exactReceiver`). Field accesses and calls on `this` are then
  static Go calls, which Go inlines. Super and private calls convert their receiver with an
  `Exact_D` helper, which only null fails (an NPE). Object accessors return the struct pointer,
  so calls on objects are static. This makes allocation- and call-heavy benchmarks 15–40% faster.
- **Forwarders.** An instantiated class gets a forwarding Go method for each method its
  ancestors' interfaces declare, and for each method called without a static class (dispatchers,
  Go code, the runtime). It calls the implementation the vtable resolves, trait defaults included
  (JVM rules: class methods win, then the most specific default). An abstract slot for
  `toString`/`equals`/`hashCode` falls back to `Object`'s.
- **Narrow interfaces.** `C_C` declares only the methods called on receivers of static type `C`,
  so a method called on a `List` does not give every collection class a Go method. This cuts the
  generated code by a quarter.
- **Exact type tests.** Accessors (`A_C`) and markers (`I_T`) are unique per class, so a Go type
  assertion is an exact `isInstanceOf`.
- **Hijacked classes** (Scala.js's term): strings, boxes, `BoxedUnit`, `Class`, `BigInteger` and
  `BigDecimal` are Go values, not structs. References typed by their supertypes (`Object`,
  `Comparable`, `CharSequence`, `Number`, ...) are therefore Go `any`. Virtual calls on them go
  through generated `Disp_<method>` type switches, type tests through `InstanceOf_<class>`.
- **Never-instantiated classes** are typed `any`. Values flowing from them into interface-typed
  locations are converted explicitly.
- **One Go package.** All generated code is in `internal/scala`, because Scala packages may be
  cyclic and Go packages may not. The root package is a thin layer over it: `main` for programs,
  the Go API of exported code for libraries ([Exports](#exports-and-library-builds)).

Output module (`Linker.scala`):

```
go.mod                  module path, Go 1.27, the Go modules that facades need
main.go                 package main: scala.Main()  (libraries: exports.go)
internal/scala/
  <class>.go            one file per reachable class
  support.go            string literals, dispatchers, type tests, Exact_ helpers
  entry.go              Main(): runs the entry point; runtime hooks
  tzdata.go             imports time/tzdata, if named time zones are reachable
internal/rt/            the runtime
```

## Control flow

- **Expressions into statements.** Expressions are emitted against a destination (discard,
  return, or assign to a variable), so `if`/`match`/blocks in expression position usually need
  no temporaries. Left-to-right evaluation is kept by spilling earlier operands into variables
  when a later operand needs statements.
- **Labeled blocks** (from pattern matching and `@tailrec`) become labeled `for` loops only when
  something `break`s to them. Jumps in tail position become `return`s.
- **Exceptions.** `throw` is `panic(rt.Exc(e))`. `try` runs its body in a closure,
  `excN := rt.Try(func() { ... })`, which `recover`s and translates Go runtime errors: nil
  dereference to `NullPointerException`, index out of range to `ArrayIndexOutOfBoundsException`,
  integer division by zero to `ArithmeticException`, failed type assertion to
  `ClassCastException`.
- **Jumps out of `try`.** A `return` or `break` inside the closure sets `ctl` to a jump code. The
  jump is replayed after the closure returns, after the `finally` block if there is one.
- **Uncaught exceptions** print `Exception in thread "main" ` and the stack trace to stderr and
  exit with status 1, as on the JVM. In other threads: the thread's handler, or the same report.
- **`lazy val`** uses JVM `VarHandle`s. The plugin rewrites the handle's compare-and-set into IR
  `CompareAndSet` on the volatile lazy val field, so lazy vals are thread-safe as on the JVM
  ([Concurrency](#concurrency-and-the-memory-model)).

### Stack traces

Stack traces have the JVM's format (`at Main$.deep(Main.scala:5)`).

- **Positions.** The linker writes Go line directives into method bodies
  (`/*line Main.scala:5:3*/`, `LineDirectives.scala`), so Go reports Scala files and lines in
  stack traces, panics, and compiler and `go vet` messages. A directive is written only where the
  Scala line differs from the one Go would count on to; lines that cannot fail (braces, jumps,
  declarations) need none. Cost: about 5% more code, no measurable effect on `go build` time.
- **Turning them off** shows Go positions, for debugging generated code:
  `Linker.Config.lineDirectives`, `--line-directives false`, Mill's `goLineDirectives`,
  `SCALAGO_LINE_DIRECTIVES=false` in the tests. `SCALAGO_TRACE=1` keeps every frame, with Go
  names and positions.
- **Capture.** `Throwable`'s constructor calls `fillInStackTrace` unless `writableStackTrace` is
  false (`ControlThrowable`, `NoStackTrace`, `boundary`'s `Break`). It records Go program
  counters by following frame pointers on amd64 and arm64 (about 1 ns per frame), elsewhere
  with `runtime.Callers`.
- **Runtime errors** become Java exceptions while their panic is recovered, before the stack
  unwinds, so the trace starts at the fault. They use `runtime.Callers`, because a faulting leaf
  function has no frame record of its own; so do programs that have called C code.
- **Symbolization** happens on the first `getStackTrace` or `printStackTrace`: method
  implementations (`Impl_<class>__<method>...`) demangle to Scala names, so no table is needed.
- **Dropped frames**, which the JVM would not show: Go runtime and `rt` frames, generated helpers
  (forwarders, dispatchers, allocators, module accessors), lambda classes, the Throwable's own
  `fillInStackTrace` and constructors, and the function enclosing a `try` closure (the
  closure's frame stands for it, as the JVM has one frame per invocation).
- **Whole traces of user code match the JVM's.** An object's `main` is called through the static
  forwarder `Main.main`, shown without a line number (`at Main.main(Main.scala)`).
  `printStackTrace` prints `Caused by:` and `Suppressed:` blocks, `... n more`, circular causes.
- **JDK frames** are missing where scala-go implements the method in Go (`Integer.parseInt`,
  `String` methods), and are javalib's Scala elsewhere (`java.lang.Thread.run(Thread.scala:23)`).
- **Other differences.** Module initialization has no `<clinit>` frame. Lines are per statement
  (a fault on a multi-line statement's second line reports its first). Inlined code reports its
  own file and line. `NullPointerException`s have no message.

## Go interop

Go code is called through **facades**, Scala declarations of Go APIs, as Scala.js's JS facades:

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
  Go package: methods are functions, members without a parameter list variables and constants
  (`def Pi: Double`), nested classes and traits the package's types (traits are interfaces).
  `@GoImport(path, name)` declares a type elsewhere; `@GoName` renames. Members are `= native`,
  which throws on the JVM, so programs that use Go code are Go-only.
- **The plugin** checks declarations (`def` only, `= native`, no constructor or type
  parameters) and records the Go name and the Scala signature *before erasure* in the IR
  (`GoMember`, `ScalaType`): conversions need type arguments (`Int => Boolean`,
  `(Int, GoError)`, `Seq[String]`) and opaque types (`GoString`) that erasure loses.
- **Go signatures come from the Go toolchain**, not from facades. The linker runs `goapi`, which
  reads the used packages' export data (`go list -export`, so the Go build cache) and prints
  their API. Each facade use is checked against it, and conversions are driven by both types.
  So facades use natural Scala types (`Int` for `int`, `String` for `string`), and a mismatch is
  a link error at the Scala call site, not a Go compile error in generated code.
- **Modules.** Packages resolve in the output module, whose `go.mod` lists the configured
  requirements (`--go-require`, `--go-replace`; Mill: `goRequires`, `goLocalModules`).
  `go list -mod=mod` completes `go.mod`/`go.sum`, which needs the network for third-party
  modules: for the facades' packages, then for the modules they import, which `go build` wants
  listed as indirect requirements (`GoApi.completeGoMod`).

**Conversions** (`GoInterop.toGo`/`fromGo`):

| Scala                      | Go                                                                 |
|----------------------------|--------------------------------------------------------------------|
| numeric primitives         | any numeric type, with Go conversions (`int(x)`)                   |
| `String`                   | `string`, transcoded (UTF-16 ↔ UTF-8)                              |
| `GoString`                 | `string`, as is                                                    |
| facade class/trait         | its Go type. Struct types are held as pointers: `T` values are copied in and out, `*T` passed as is |
| facade struct class, `Any` | a Go interface (or `any`): the struct by value if the value implements the interface (for `any`: if it has no pointer-receiver methods), else the pointer |
| Scala class                | a Go interface it implements                                       |
| `Array[T]`                 | `[]T`. Memory is shared when representations match (`Array[Byte]`/`[]byte`, `Array[Long]`/`[]int64`, ...); otherwise elements are copied |
| `T*`, `Array[T]`           | variadic `...T`. Literal varargs skip the `Seq`                    |
| `(K, V)*`, `Seq[(K, V)]`, `Array[(K, V)]` | `map[K]V`, a new map (Go's composite literal; a later key wins) |
| `(A, B) => C`              | `func(A, B) C` or a named func type (`type Cmd func() Msg`), calling `apply` through a dispatcher; results as for Go methods implemented in Scala (below) |
| Go func returned to Scala  | a `GoFunctionN` wrapping a Go closure that converts with the facade's types and throws the Go error |
| `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go value as is (no copy), if `T` is the canonical view of the Go element type ([Go data](#go-data-in-scala)); directional channels and named types are converted |
| `(A, B)` result            | two results                                                        |
| `Any`                      | `any`, or a named interface without methods (`type Msg interface{}`); Scala strings become Go strings |

- **Errors from Go.** A trailing `error` result that the facade's result type leaves out is
  checked, and a non-nil error is thrown as `scalago.go.GoException` (field `error`). A facade
  can keep it instead: `(Boolean, GoError)`. Go panics become `RuntimeException`s, runtime errors
  the usual Java exceptions. Scala exceptions unwind through Go frames as panics.
- **Go interfaces implemented in Scala.** A Scala class extending a facade trait gets Go methods
  with the Go names on its struct; they convert arguments, call the Scala method, and convert the
  result. A tuple gives several results: bubbletea's `Update(Msg) (Model, Cmd)` is
  `def Update(msg: Msg): (Model, Cmd)`.
- **Errors to Go.** If the Go method returns an `error` that the Scala method does not, an
  exception becomes that error (`rt.RecoverAsError`): a `GoException` gives back its Go error,
  anything else becomes an `*rt.ScalaError`.
- **Go values in Scala** are opaque. `isInstanceOf`/`asInstanceOf` are Go type assertions.
  `toString` uses `Error()`/`String()` when available; `getClass.getName` is the Go type
  (`*strings.Builder`); `==` is Go `==`, or identity for maps, slices and funcs; `new T` is the
  zero value.
- **Struct values.** Go interfaces can hold struct values (`tea.KeyMsg` in a `tea.Msg`), but
  facades hold pointers. A type test against a struct facade accepts both; the cast copies a
  value to a new pointer (`rt.IsStruct`, `rt.CastStruct`).
- **Structs back to Go** are passed by value where Go code would (`rt.ToGoInterface`,
  `rt.ToGoAny`), so Go code tests for `KeyMsg`, not `*KeyMsg`. The choice depends on the type's
  method sets and is cached per type: a struct with pointer-receiver methods (`bytes.Buffer`,
  `url.URL`) stays a pointer in an `any`.
- **Conversions to named types** are package members named as the type, with one parameter:
  `def Color(value: String): Color` is `lipgloss.Color(value)`, the argument converted to the
  underlying type. A named slice or map type's composite literal is such a conversion from
  varargs: `def H(entries: (String, Any)*): H` makes `gin.H("message" -> "pong")` gin's
  `gin.H{"message": "pong"}`.
- **Generic Go functions** are called through monomorphic facades (`def Max(xs: GoSlice[GoInt]):
  GoInt` for `slices.Max`). Go types mentioning type parameters are taken from the facade's
  Scala types; the call passes values of those types, so Go infers the type arguments. Each
  instantiation needs its own facade member (`@GoName` allows several).
- **Output ordering.** Scala's stdout is buffered, and flushed before each Go call and after each
  Go-to-Scala callback, so it stays ordered with what Go code writes.
- **Imports** use aliases (`gp_url "net/url"`), so they cannot clash with generated names or
  each other.

### Generated facades

`FacadeGen` (`scalago.linker.FacadeGen`; Mill: `goFacadePackages`) writes facades from Go
packages' APIs, as ScalablyTyped does from `.d.ts` files. Each package becomes one `@GoImport`
object; its types become classes and traits.

| Go                                    | Generated Scala                                         |
|---------------------------------------|---------------------------------------------------------|
| numbers, `string`, `error`            | Scala numbers (`int` is `Long`), `String`, `GoError`    |
| Go types, several results             | their generated facades, tuples                         |
| trailing `error`                      | thrown, except where the error is the value (`errors.New`, `Errorf`, `ctx.Err()`) |
| func type without methods             | alias of a Scala function type (`type Cmd = () => Any`) |
| interface without methods             | alias of `Any` (`type Msg = Any`)                       |
| other named non-struct type           | a class, plus its conversion (`def Color(value: String): Color`) |
| named slice or map type               | also its composite literal (`def H(entries: (String, Any)*): H`, `def HandlersChain(elems: HandlerFunc*)`) |
| generic function with an interface constraint (`Matches[K fmt.Stringer](k K)`) | the constraint as the type (`k: fmt.Stringer`); Go infers it as the type argument |
| slices and maps inside Go data        | canonical views (`GoSlice[GoString]`, `GoMap[GoString, GoInt]`), so slices are shared |

- **Aliases** let Scala lambdas and values pass as they are. Aliases of function types are
  erased in the IR, so the plugin records facade types before erasure, aliases followed.
  Composite literals also cover element types that cannot be inside Go data, such as funcs.
- **Func types' results** are as functions': a trailing `error` is thrown (by the Scala
  function, or by a Go func returned to Scala), several results are a tuple. `errgroup`'s
  `func() error` is `() => Unit`.
- **Structural to nominal.** A generated type extends every generated interface whose methods
  it has (`class Buffer extends io.Writer, io.Reader, fmt.Stringer, ...`).
- **Transitive types.** Types of other packages that generated members use are generated too
  (`time.Duration` for `tea.Tick`, `io.Reader` for `tea.WithInput`); their functions, variables
  and constants need the package in the list. `goapi` reruns until no package is missing.
- **Left out**, with a comment saying why: generics constrained by type sets (`~int | string`,
  `comparable`), unnamed structs, arrays, funcs inside Go data, complex numbers, constants beyond
  `Long`, types of `internal` packages (which the output module cannot import).

**Dependencies' facades are reused**, as ScalablyTyped reuses converted libraries: a Go type has
one Scala type, not one per generated set, so values flow between libraries.

- FacadeGen reads the facades of its classpath (`--classpath`; Mill: the compile classpath) from
  the IR (`FacadeIndex`): classes and traits by their `GoFacade`, type aliases, which have no
  class, by the package facade that records them (`GoFacade.typeAliases`). Reading IR rather
  than a manifest covers handwritten facades and needs nothing beyond what jars contain.
- Go types a dependency provides are not generated, nor types only they use: generated members
  use the dependency's types (`scalago.gostd.io.Reader` in bubbletea's facades). Their packages
  are still loaded, for their declarations.
- Generated types extend a dependency's interface facade only if it declares the methods with the
  types FacadeGen would generate, compared on the plugin's recorded `GoMember` types; a
  handwritten trait that differs would make the generated class fail to compile.
- A requested package that dependencies provide completely (all types and all generatable
  members) is not generated, and FacadeGen says so.
- A Go package can have facade objects in several modules (`textfacades.base` with `Named`,
  `gofacades.base` with `Unit`): the linker matches facades by Go type, not by class. An object
  name a dependency already uses is an error (the sets need different Scala packages).

Generated facades are checked at link time like handwritten ones, only where used, so unused
members cost nothing. `scalago-gostd` (`gostd/`) has facades of 21 standard library packages,
plus the types of `io/fs`, `reflect` and `syscall` they use: about 1,700 members.

| Test or example    | Covers                                                             |
|--------------------|--------------------------------------------------------------------|
| `go-std`           | `scalago-gostd`                                                    |
| `go-facadegen`     | a local module in bubbletea's style; the rules above               |
| `go-web`           | a module in gin's style: embedded structs, promoted methods, variadic named func types, composite literals, handlers on goroutines, JSON with `GoData`, callbacks with error results |
| `go-facade-reuse`  | two generated sets (`facades-<package>.txt` compiled as dependencies of `facades.txt`) passing values to each other and to `scalago-gostd` |
| `examples/gotui/`, `gin/`, `argus/` | bubbletea, bubbles and lipgloss; a JSON API on gin; configuration reloaded by argus (`mill examples.<name>.check`) |

### Go data in Scala

`GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]` and `GoPtr[T]` (`library/src/scalago/go/`) are opaque
types whose values are Go slices, maps, channels and pointers, shared with Go without copying
(as Scala.js's `js.Array`, `js.Dictionary`). Elements have **canonical** Scala views, decided by
the Go element type alone, so erasure loses nothing:

| Go element type                             | Scala view                                |
|---------------------------------------------|-------------------------------------------|
| `int`, `int64`, `uint`, `uint64`, `uintptr` | `Long` (or `GoInt`, `GoUint`, ...)        |
| `int32`, `uint32`                           | `Int` (or `GoUint32`)                     |
| `int16`, `uint16`, `int8`, `uint8`          | `Short`, `Char`, `Byte` (or `GoByte`)     |
| `float32`, `float64`, `bool`                | `Float`, `Double`, `Boolean`              |
| `string`                                    | `GoString`                                |
| struct values                               | the facade (a pointer to a copy)          |
| anything else                               | the Go value: facades, `GoSlice`, ..., `Any` |

- `rt/gotypes.go` converts by the actual Go type: fast paths for common slice types,
  reflection otherwise. Link-time checks name the right view: `GoSlice[String]` for a Go
  `[]string` is an error suggesting `GoSlice[GoString]`.
- Creating Go data needs the Go element type, which a `GoType[T]` given supplies:
  `GoSlice.make[GoInt](n)` makes an `[]int`, `GoSlice.make[Long](n)` an `[]int64`. `GoInt`,
  `GoByte` and the other Go numeric types are opaque over Scala numbers, so they only matter
  here and in facades.
- Every operation has a JVM implementation with Go's semantics (`GoNatives`), so code using Go
  data without facades runs on the JVM too. The `go-data` test checks both agree.

**Plain data.** Go code that inspects values by reflection (`encoding/json`, templates,
configuration libraries) cannot see inside Scala objects: a case class in an `any` is a pointer
to a generated struct. `GoData` (`library/src/scalago/go/GoData.scala`) copies between Scala
data and plain Go data:

- `GoData(x)`: strings become Go strings; integers Go `int`s (the type of Go's integer
  literals, which libraries' type switches expect); collections `[]any`; maps and case classes
  (by `productElementNames`) `map[string]any`; `Option` its value or `nil`; Go values stay.
  `GoData.map(k -> v, ...)` is a typed `GoMap[GoString, Any]`.
- `GoData.toScala` goes back: strings, `Long`, `Double`, `Boolean`, `Vector`, and maps as
  `Map[String, Any]` sorted by key (Go maps have no order). Natives `rt.GoDataKind` and
  `rt.GoBasicData` tell predeclared basic values, maps and slices from other values.
- It copies by design: converting Scala values passed to `any` automatically would break
  identity (a Scala message passed through bubbletea must come back as itself).
- Struct tags have no Scala counterpart, so JSON request bodies are bound into `*any`
  (`GoPtr.alloc[Any]`) and read with `GoData.toScala`, or parsed by a Scala JSON library.

Not supported: generic Go *types*, `GoType` instances for facade types, `GoSlice`/`GoMap` facades
for *named* slice and map types, `select` over channels, unnamed struct and interface types,
variadic callbacks, Go funcs with more than 3 parameters returned to Scala.

### Go strings

`String` stays `java.lang.String` (UTF-16), so programs behave as on the JVM, and converting it
to Go's `string` transcodes. `scalago.go.GoString` (`library/src/scalago/go/GoString.scala`) is
the opt-in Go `string`, passed to and from Go as is. On the JVM it is a `JvmGoString` holding
UTF-8 bytes, whose `==`, `hashCode`, `toString` and `Ordering` work by content, as on Go.
Converting a `String` turns unpaired surrogates into U+FFFD on both (Go's rule; the JDK writes
`?`).

- **`go"..."`** (`GoInterpolator.scala`) writes `GoString`s like `s"..."`. The macro processes
  escapes as `s` does, at compile time (bad escapes are compile errors), and expands to
  `GoString(part0).concat(arg0).concat(GoString(part1))...`. `GoString` arguments are inserted
  as they are; others become `GoString.valueOf(arg)` (`String.valueOf`).
- **No `f`-style formatting.** `java.util.Formatter` semantics would need a second
  implementation over Go strings; formatting into a `String` and interpolating that is as short.
- **The linker** (`FunctionEmitter.genGoString`) emits `GoString(s)`, `GoString.valueOf(x)` and
  `concat` chains as one Go `string` expression. Literals are Go constants (a literal-only
  `go"..."` is `any("...")`, free at run time); `valueOf` is `rt.ToGoString`, which formats
  primitives straight into a Go string; operands are joined by one `+`, a single allocation.
  Operands are spilled left to right like call arguments, so values render in JVM order.
- **Why a macro.** It leaves the plugin and the IR alone, reuses the compiler's escape
  processing, and is the JVM implementation too. The linker recognizes ordinary `GoString`
  calls, so handwritten ones benefit as well.

## I/O

`java.io` and `java.nio` are Scala in javalib over a small byte-oriented native layer on Go's
`os` package (`scalago.runtime.OS`, `rt/files.go`). Java semantics (which exception, which
message) are in Scala; the layer knows nothing of Java's classes.

| Natives | Operations                                                                   |
|---------|------------------------------------------------------------------------------|
| Handles | `open(path, mode)` (read, write, append, create, create-new, truncate) returns an `*os.File` held as an `Object`; `read`, `write`, `readAt`, `writeAt` (positional, for file channels), `seek`, `size`, `available`, `truncate`, `sync`, `lock`/`unlock`, `close` |
| Paths   | `stat`, `access`, `mkdir`, `remove`, `rename`, `readDir`, `createTemp`, `chmod`, `setModified`, `symlink`, `link`, `readLink`, `realPath`: the result or a Go error |
| Errors  | `errorKind` (no such file, exists, not empty, access denied, not a directory, is a directory, loop); `errorReason`, the C library's text ("No such file or directory") |

- Read and write errors throw `IOException` with the C library's message. From path errors,
  javalib builds the JDK's exceptions: `FileNotFoundException("<path> (<reason>)")` in
  `java.io`; `NoSuchFileException`, `FileAlreadyExistsException`, `DirectoryNotEmptyException`,
  `AccessDeniedException` or `FileSystemException` in `java.nio.file`.
- Locks are POSIX record locks with `fcntl`, as the JDK's; Windows has none.
- Streams, readers and writers are adapted from Scala Native. Paths are Unix paths (`/`),
  normalized as by the JDK.

**Buffers** (`java.nio`) are heap buffers. `ByteBuffer` wraps a byte array, with multi-byte
values in either byte order and views (`asIntBuffer`, ...). Other element types wrap arrays,
view byte arrays, or (chars) wrap read-only text (`CharBuffer.wrap(CharSequence)`).

- States, exceptions, `equals`, `compareTo`, `mismatch` and `hashCode` are the JDK's, and so are
  the concrete class names `toString` shows (`HeapByteBuffer`, `HeapIntBufferR`,
  `ByteBufferAsLongBufferL`, `StringCharBuffer`).
- Each element type's buffer has abstract element access (`load`, `store`) and the backing array
  when there is one, which bulk operations copy with `System.arraycopy`.
- All but the byte classes (per-type classes; heap, read-only and byte-array-view
  implementations in both orders) are generated from `javalib/templates/*.template` by javalib's
  `generatedSources`. `allocateDirect` makes a heap buffer (`isDirect` false, `hasArray` true).

**Charsets** are UTF-8 (the default), ISO-8859-1 and US-ASCII; programs cannot define others.
Coders work on arrays (`decodeArray`/`encodeArray`), which `InputStreamReader`,
`OutputStreamWriter`, `Files` and `new String(bytes, ...)` call directly; they return underflow,
overflow or an error at the offending input. Malformed UTF-8 is replaced or reported in the
JDK's units (each maximal prefix of a valid sequence). `CharsetDecoder`/`CharsetEncoder` add the
JDK's buffer API on top (`CoderResult`, the reset/coding/end/flushed state machine,
`Charset.encode`/`decode`), copying buffers without an accessible array.

**Channels.** `FileChannel` (from `FileChannel.open`, `Files.newByteChannel`, and `getChannel`
of file streams and `RandomAccessFile`, sharing the stream's file and position as on the JDK)
has scattering and gathering, positional I/O, `transferTo`/`transferFrom`, `truncate`, `force`
and locks (overlapping locks within the process are refused, as on the JDK). `map` reads the
region into a heap `MappedByteBuffer`: `READ_ONLY` and `PRIVATE` behave as the JDK's but do not
see later changes to the file; `READ_WRITE` is not supported. `Channels` adapts both ways.

**File systems.** `FileSystems.getDefault()` is the only one: `getPath`, `getSeparator`,
`getRootDirectories`, `getPathMatcher` with `regex:` and `glob:` (translated as the JDK's
`Globs`, into a regular expression for Go's engine).

### Standard streams

- `System.out` and `System.err` are `PrintStream`s whose print methods hand the value to the
  runtime (`Console.write`), which transcodes it straight into a 32 KB stdout buffer (stderr is
  unbuffered). One call and one lock per `print`/`println`: lines from different threads never
  interleave.
- Standard output is flushed at exit, before writing to stderr, before reading stdin, and around
  calls into Go code; not per line, unlike the JDK's autoflushing `System.out`. `System.in` is a
  `BufferedInputStream` over `os.Stdin`. `System.setOut/setErr/setIn` replace the streams.
- `Predef.println` goes through the Scala library's `Console`, so `Console.withOut` works, on
  `InheritableThreadLocal`. javalib's `scala.util.DynamicVariable` skips the thread-local lookup
  until a variable is first set, so `Console.out` costs one atomic load while nothing is
  redirected.

### Differences from the JDK

- The console streams' print methods do not take the stream's monitor: holding `System.out`'s
  lock does not keep other threads from printing.
- `FileTime`s have millisecond precision; file attributes given at creation are ignored.
- Directories list in name order (`Files.list`, `walk`, `newDirectoryStream`), not the file
  system's. `Files.walk` with `FOLLOW_LINKS` does not detect cycles.
- `Thread.interrupt` does not interrupt blocking channel operations (no
  `ClosedByInterruptException`). `FileLock`'s class is `java.nio.channels.FileLockImpl`.
- `ByteOrder.nativeOrder()` is little-endian; Go's big-endian platforms are not supported.
- Not implemented: direct and `READ_WRITE`-mapped buffers, socket channels, selectors,
  asynchronous channels, other file systems and providers (`FileSystemProvider`, zip file
  systems), `FileStore`, watch services, `Scanner`, other charsets.

## Subprocesses

`ProcessBuilder`, `Process` and `ProcessHandle` are Scala (javalib) over Go's `os/exec`
(`scalago.runtime.Subprocesses`, `rt/subprocess.go`).

- **Starting.** `start` passes the command, directory, the three redirects and the environment,
  the latter only if the program touched `environment()` (a copy of `System.getenv()` checked as
  the JDK checks it). The program is looked up on the parent's `PATH`; a relative program path
  is relative to the directory. Failures are `IOException`s with the JDK's messages (`Cannot run
  program "x" (in directory "d"): Exec failed, error: 2 (No such file or directory)`).
- **Pipes** are `os.Pipe`s. The child gets one end; the parent keeps the other as a file-layer
  handle, so a process's streams are `java.io` file streams, buffered as the JDK's
  (`getOutputStream` must be flushed or closed).
- **Redirects.** `INHERIT` gives the child the program's standard streams, after flushing
  stdout so the program's output comes first. Files (`from`, `to`, `appendTo`, `DISCARD`) are
  opened by the parent.
- **Waiting.** A goroutine per process waits and records the exit status (128 plus the signal
  number for a signal, as on the JDK). `waitFor`, with or without timeout, blocks on a channel
  and is interruptible. `destroy` sends `SIGTERM`, `destroyForcibly` `SIGKILL` (both kill on
  Windows); both close the pipes, as on the JDK.
- **Also:** `ProcessHandle.current()` (pid, parent), `Process.toHandle`, `pid`, `inputReader`,
  `outputWriter`, `Runtime.exec`. **Not implemented:** `onExit`, `ProcessHandle.of` for other
  processes, `allProcesses`, `children`, `descendants`, `info` (empty values), `startPipeline`.

`scala.sys.process` (`!`, `!!`, `#|`, `#>`, `#&&`, `lazyLines`, `ProcessLogger`) is the Scala
library's, unchanged: its pumping threads are goroutine-backed `Thread`s, its pipes javalib's
`PipedInputStream`s, and `System.getProperties` is a `Properties` object as on the JDK. os-lib's
`os.proc(...).call()`, `spawn` and file operations run unchanged too (`examples/ecosystem`).

## Networking

`java.net` and `java.net.http` are Scala (javalib) over a native layer on Go's `net` and
`net/http` (`scalago.runtime.Net`, `rt/net.go`, `rt/http.go`), built like the file layer.
Addresses are byte arrays; connections, listeners and HTTP exchanges are handles. Failures are
Go errors the layer classifies (refused, reset, timed out, closed, unknown host, address in use,
TLS, ...), which javalib turns into the JDK's exceptions and messages (`ConnectException:
Connection refused`, `SocketTimeoutException: Read timed out`).

- **`URI`** follows the JDK's parser: RFC 2396 with the JDK's deviations (IPv6 literals with
  scope ids, empty authorities and paths), registry- and server-based authorities, the JDK's
  messages and error indices, normalization, resolution, relativization, component-constructor
  quoting, `toASCIIString`, escape-case-insensitive comparison. Checked against JDK 21 and 25
  on 1,700 cases.
- **`URL`** parses as the JDK's stream handlers (context URLs, IPv6 hosts, ports, refs) for
  http, https, file, jar, ftp and mailto URLs, and opens the first three. `URLEncoder` and
  `URLDecoder` take charsets or their names.
- **`InetAddress`** parses IPv4 literals (with the C library's short forms: `127.1`) and IPv6
  literals (IPv4-mapped ones are `Inet4Address`es), and prints and hashes as the JDK. Names
  resolve with Go's resolver (`/etc/hosts`, DNS), IPv4 first as on the JDK.
  `InetSocketAddress` is the JDK's, unresolved addresses included.
- **Sockets.** `Socket` and `ServerSocket` are Go TCP connections and listeners. `setSoTimeout`
  sets a deadline before each blocking read or accept; the socket stays usable after a timeout.
  Closing a socket from another thread closes the Go connection, ending a blocked read or accept
  with `SocketException: Socket closed`, as on the JDK.
- **Socket options** (`TCP_NODELAY`, off by default as on the JDK; keep-alive, linger, buffer
  sizes) go to the connection. Listening on the wildcard address accepts IPv4 and IPv6.

**HTTP.** `HttpURLConnection` and `java.net.http.HttpClient` share one `net/http` transport:
connections are kept alive and reused, requests are HTTP/1.1, no proxy is used (the JDK ignores
`HTTP_PROXY` too), responses are not decompressed, and TLS is Go's `crypto/tls` with the
system's root certificates. A request is sent whole when its response is first needed; a
request body is buffered.

- **`HttpURLConnection`** adds the JDK's default headers (`User-Agent: Java/<version>`,
  `Accept: */*`, a POST's form content type), follows redirects within a protocol up to 20
  times, and fails as the JDK: `FileNotFoundException` for a 404, "Server returned HTTP
  response code: 500 for URL: ..." with the body in `getErrorStream`.
- **Timeouts** are timers that cancel the exchange: the connect timeout; the read timeout, for
  the response headers and each body read; `HttpRequest.timeout`, until the headers arrive
  (`HttpTimeoutException`).
- **Response bodies** are streams over the socket read native. `HttpClient` hands one on demand
  to the response's `BodySubscriber` (any `BodyHandlers`/`BodySubscribers` one, or the program's
  own), reading each chunk in the thread that requests it, so `send` needs no thread of its own:
  `ofString` reads the body while it subscribes, `ofInputStream` as the stream is read.
- **`sendAsync`** runs `send` on the client's executor, by default a cached pool of daemon
  threads, as on the JDK.

### Differences from the JDK

- Requests are HTTP/1.1 even when HTTP/2 is asked for (`HttpClient.Version.HTTP_2` is the
  default); responses say `HTTP_1_1`, as the JDK's do from servers without HTTP/2.
- Response header names are Go's canonical ones (`content-type` arrives as `Content-Type`, so
  `X-b` and `x-B` are one field) and come in name order.
- TLS trusts the system's certificates, not the JDK's `cacerts`. `HttpsURLConnection`,
  `SSLContext`, socket factories and hostname verifiers are not available.
- Streaming modes buffer the request body (`setChunkedStreamingMode` sends a `Content-Length`).
- `URL.equals`/`hashCode` compare host names without resolving them. `InetAddress` caches no
  lookups and resolves no interface names in scope ids (numeric ids work); `isReachable` only
  tries a TCP connection to port 7. A socket stream's `available()` is 0.
- `Thread.interrupt` does not end blocking socket operations (on the JDK it closes a virtual
  thread's socket); closing a socket does not interrupt its `connect`; `Socket.bind` takes
  effect at `connect`.
- Not implemented: proxies, `Authenticator`, `CookieHandler`, push promises, WebSockets, UDP
  (`DatagramSocket`), `NetworkInterface`, socket channels, selectors.

## Digests and compression

`MessageDigest` (MD5, SHA-1, SHA-224/256/384/512, SHA-512/224 and /256, with the JDK's names,
aliases and OIDs), `Mac` (their HMACs) and `SecureRandom` are Scala over Go's crypto packages
(`scalago.runtime.Crypto`, `rt/crypto.go`). A digest in progress is a Go hash, cloned through its
marshalled state; HMAC is written over those hashes so that `Mac.clone` works. `SecureRandom`
reads `crypto/rand` whatever its algorithm, so seeds add nothing: a seeded SHA1PRNG is not
reproducible, unlike the JDK's.

`java.util.zip` is Scala, except checksums (`hash/crc32`) and the deflate state machines on
`compress/flate` (`scalago.runtime.Zip`, `rt/zip.go`).

- **`Deflater`** writes through flate writers into a buffer of pending output, with the zlib
  header and trailer written by hand, so a level change mid-stream ends the current writer's
  blocks and continues the stream with a new writer.
- **`Inflater`** is fed piece by piece, but Go's flate reader pulls its input and cannot resume
  after running out. So the reader runs on a goroutine, as a coroutine of the `inflate` calls:
  control passes over channels, and the two never run at once. A finalizer stops the goroutine
  of an inflater dropped without `end()`.
- The reader reads one byte at a time, never beyond the compressed data, so `getRemaining()` is
  exact, which GZIP and zip trailers rely on.
- `GZIP*Stream`, `Zip*Stream`, `ZipEntry` and `ZipFile` follow the JDK: data descriptors, UTF-8
  names, DOS times in the local zone with extended and NTFS timestamps, concatenated GZIP
  members, ZIP64 when reading.

Differences from the JDK: compressed bytes differ from zlib's (valid, but not the same bytes or
sizes); corrupt data gives Go's messages ("flate: corrupt input before offset N"); an inflater
may deliver output at block ends rather than mid-block; `FULL_FLUSH` does not reset the
dictionary; `FILTERED` is the default strategy.

## java.time

`java.time` (`javalib/src/java/time`) is
[scala-java-time](https://github.com/cquiroz/scala-java-time) 2.9.0, the Scala port of the
ThreeTen backport that Scala.js and Scala Native use, vendored as source (BSD-3-Clause,
`javalib/LICENSE-scala-java-time.md`). scala-go's changes are marked `scala-go:`.

It is vendored because no artifact fits: the JVM one is empty (the JVM has `java.time`); the
Scala.js and Scala Native ones contain only compiled output (the build generates their sources),
depend on platform libraries and on scala-java-locales for `java.text`, and would make users add
a dependency for part of the JDK. As source it is part of javalib, like `java.util`.

- **Time zones are Go's**: the system's zoneinfo, or Go's embedded copy (`time/tzdata`, about
  450 KB). The linker imports it (`internal/scala/tzdata.go`) only when a program can reach a
  named zone (`scalago.runtime.Time.zone`), so zone users run without a system zoneinfo
  (Windows, minimal containers) and other programs do not carry it. `DateTimeFormatter`
  patterns can parse zone IDs, so using patterns reaches it.
- **Zone rules.** A region's `ZoneRules` (`zone/GoZoneRules.scala`) wrap its `*time.Location`,
  which Go does not let programs walk. Go gives the offset at an instant and the period during
  which it holds (`Time.ZoneBounds`); transitions are ends of periods with different offsets.
  `getStandardOffset` is the offset of the latest period that is not daylight saving time.
- **Local date-times** resolve by examining the periods within 18 hours (the largest offset).
  Each period whose offset maps the local date-time back into itself gives a valid offset: one,
  none (a gap) or two (an overlap), as with the JDK's rules, from the same data, with no copy of
  the database in Scala. Without a system zoneinfo, zone IDs parse as the longest name Go loads.
- **The system zone** (`ZoneId.systemDefault`, `TimeZone.getDefault`) is found as the JDK does
  on Linux: `TZ`, `/etc/timezone`, then the target of the `/etc/localtime` link or the zoneinfo
  file matching its contents; `GMT` if none names a known zone.
- **The clock** has nanosecond precision (`time.Now`), as the JDK's on Linux.
- **No locale data.** Month and day names, AM/PM, eras and `ofLocalized*` patterns are the JDK's
  English ones (`en_US`, with CLDR's narrow no-break space before AM/PM) whatever the locale;
  numbers use ASCII digits. Zone names (`z`, `zzzz`, `TimeZone.getDisplayName`, `Date.toString`)
  are the tz database's abbreviations ("CEST"), or `GMT+03:00` where it has none ("+03").
- **Other changes:** caches threads would race on are synchronized or built once; JDK 9–18
  methods the backport lacks are added (`LocalDate.ofInstant`, `Duration.truncatedTo`,
  `toXxxPart`, `dividedBy(Duration)`, `Instant.until(Instant)`, `LocalDate.datesUntil`, ...);
  exception messages are the JDK's; `LocalDate.plusDays` stays within the month when it can.
- **Also included:** `java.util.Date` (milliseconds; `toString` in the default zone), `TimeZone`
  on java.time's zones, and the `java.text` base classes the API mentions (`Format`,
  `ParsePosition`, `FieldPosition`, `ParseException`).

Differences from the JDK:

- No long zone names ("Eastern Standard Time"), locale-specific texts or patterns; no Japanese
  calendar (the other non-ISO calendars are there).
- `getTransitions` stops at 2037; `getTransitionRules` is empty.
- Daylight saving time is what the zoneinfo files say: in zones with negative DST
  (Europe/Dublin's winter time) the standard offset can differ from the JDK's.
- `ZoneId.getAvailableZoneIds` lists the system's zoneinfo, empty where there is none (every
  zone still loads).
- Parsing zone names (`z`) chooses arbitrarily among zones sharing an abbreviation.
- The deprecated calendar methods of `Date`, `Calendar` and `SimpleDateFormat` are missing.

## Exports and library builds

Go code calls Scala code through **exports** (`Exports.scala`), as Scala.js's
`@JSExportTopLevel`:

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

- **Library links** (`Linker.Config.library`, `--library true`, Mill's `ScalaGoLibraryModule`)
  have no entry point; the roots are the exported definitions. The root package (named after the
  module path's last element, or `--package`) is the library's Go API, in `exports.go`. Other Go
  modules require and import it. Modules and static initializers run on first use.
- **Exported functions** are methods of top-level objects. **Exported classes** become Go
  interface types (`type Counter = scala.X_Counter`) with the class's `@GoExport` methods and
  those of exported superclasses; the primary constructor becomes `New<Name>`. Go names are the
  Scala names capitalized, unless given.
- **Identity.** The interface value holds the generated struct pointer, so a Scala object is the
  same Go value every time it crosses (`==` works in Go), with no wrappers. Each instantiated
  subclass's struct gets its own Go methods, calling the Scala methods virtually; methods
  promoted from an embedded struct would lose the outer receiver.
- **Conversions** follow the facade rules, with Go types chosen from Scala types
  (`GoInterop.goTypeOf`): `Int` is `int32`, `GoInt` `int`, `String`/`GoString` `string`,
  `Array[T]`/`GoSlice[T]` `[]T`; `GoMap`, `GoChan`, `GoPtr` are Go's; exported classes are their
  interfaces, facades their Go types, functions Go funcs (a Go func passed in is a `GoFunctionN`).
- **Errors.** A `@throws` method gets a trailing `error` result, which a Scala exception becomes
  (`rt.RecoverAsError`; a `GoException` gives back its Go error). Otherwise the exception is a
  Go panic whose value is an `error` describing it.
- **Goroutines.** Exported code may be called from any goroutine
  ([Concurrency](#concurrency-and-the-memory-model)). Buffered output is flushed when each call
  returns.
- **No import cycles.** Go forbids them, so a Go package the library imports through a facade
  cannot import the library; it receives Scala code as values instead (Go interfaces implemented
  in Scala, or Go funcs).
- **One Scala library per Go binary.** Linking is whole-program, so two separately linked Scala
  libraries in one binary would each carry their own runtime and standard library, as with
  separately linked Scala.js modules.

Not supported: exported traits (Go code implementing Scala traits), exported vals and fields,
secondary constructors, tuple results, overloads with the same Go name, generic methods.

## Concurrency and the memory model

Go code calls Scala code from any goroutine, so the runtime and the generated code are
goroutine-safe, with the JVM's guarantees where Scala code relies on them.

- **Initialization.** Module construction and static initializers run once under an
  `rt.InitLock`; the fast path is one atomic load. Other goroutines wait for an initialization
  in progress. The initializing goroutine re-entering (self-reference, initialization cycles)
  sees the partial state, as on the JVM. If an initializer throws, the exception propagates and
  later uses throw `NoClassDefFoundError`.
- **Monitors.** `synchronized` is a reentrant per-object monitor; `wait`/`notify`/`notifyAll`
  work as on the JVM, with timeouts and interrupts. The plugin lowers `synchronized` to
  enter/exit around `try`/`finally`. Scala objects keep the monitor in `rt.ObjectHeader`,
  created on first use; strings, boxes and Go values use a global table whose entries are never
  removed. An uncontended enter/exit costs about 55 ns.
- **Goroutine ids** are needed by monitors, `Thread.currentThread` and interruptible channel
  operations; Go does not expose them. On amd64 and arm64, `rt/goid.go` reads the id from the
  goroutine's runtime structure: a two-instruction assembly `getg`, plus the field offset found
  at startup by matching the ids `runtime.Stack` reports for several goroutines (about 4 ns).
- **Fallback.** Without the offset (a future Go layout, another architecture), the runtime
  parses `runtime.Stack` (about 3.5 µs). `goid_test.go` fails in that case on amd64 and arm64.
- **Volatile fields** (`@volatile`, and each `lazy val`'s state field) are sequentially
  consistent, as the JMM requires. Primitive fields are `sync/atomic` values (`rt.VolatileInt32`,
  `rt.VolatileInt64`, `rt.VolatileBool`, ...), which never allocate.
- **Volatile references.** Go cannot access an interface atomically, so a reference field is an
  `rt.Volatile[T]`: an atomic pointer to an immutable box. A store allocates a box, except for
  null and for re-storing the last stored object (a lock owner alternating between a thread and
  null), whose box it reuses.
- **Atomic intrinsics.** javalib's atomics do read-modify-write through
  `scalago.runtime.Intrinsics`: `compareAndSet`, `getAndSet`, `getAndAdd`, whose first argument
  is the field or its getter. The plugin turns them into IR `CompareAndSet`, `GetAndSet`,
  `GetAndAdd`; the linker into `sync/atomic`'s `CompareAndSwap`, `Swap`, `Add`. Rarer operations
  (`getAndBitwiseOr`, ...) are compare-and-set loops.
- **Atomic arrays** (`scalago.runtime.AtomicArrays`): `Int` and `Long` arrays in place,
  reference arrays as Go slices of atomic pointers (`scalago.runtime.VolatileArray`).
- **Benign races.** The JDK's concurrent classes read some plain reference fields racily, which
  the JMM makes safe and Go does not (torn reads). In the ported classes they are volatile:
  `ConcurrentHashMap`'s tables and `LongAdder`'s cells are `VolatileArray`s; a lock's owner,
  `AbstractQueuedSynchronizer`'s waiters and `CompletableFuture`'s stage actions are `@volatile`.
  All of `java.util.concurrent` runs under the race detector in the tests.
- **Lazy vals** follow Scala 3's scheme: compare-and-set on the volatile field; goroutines that
  find the value being computed wait on a `Waiting` marker (a monitor).

### Threads

- **Goroutine-backed.** `java.lang.Thread` runs on a goroutine. `start`, `join` (with timeouts),
  `sleep`, `interrupt`, `currentThread`, names, daemon status, priorities, thread groups (names
  and handlers), `getState`, `holdsLock`, builders and uncaught-exception handlers work. Virtual
  threads are threads like the others (daemons, unnamed by default, `isVirtual`); Go's scheduler
  multiplexes all of them.
- **Foreign goroutines** (the main goroutine, or one started by Go code) get a `Thread` the first
  time they ask, kept while they run. Go does not report goroutine exit, so when the number of
  such `Thread`s doubles, the runtime drops those of ended goroutines, found from a full stack
  dump's goroutine ids (never reused). A Go server calling Scala per request does not leak them.
- **`Thread.currentThread`** is a lookup by goroutine id through a direct-mapped cache: an
  atomic load and a comparison.
- **Program end.** As on the JVM, `Main()` waits for non-daemon threads, and an exception in a
  thread is reported (`Exception in thread "name" ...`) without ending the program.
- **Thread locals.** Each `Thread` holds a table of its `ThreadLocal` values; a new thread copies
  its creator's `InheritableThreadLocal`s (through `childValue`). Tables hold keys strongly, so a
  value lives until `remove` or the end of its thread.
- **Parking** (`LockSupport`), the basis of the synchronizers: each thread has a permit, a
  one-slot Go channel. `park` takes it, or blocks until `unpark`, an interrupt (the status stays
  set, as on the JVM) or the timeout. Parked threads report `WAITING`/`TIMED_WAITING`.
- **`Runtime.availableProcessors`** is `GOMAXPROCS`.

### java.util.concurrent

Scala Native's port of JSR-166 (Doug Lea's public-domain code), adapted to the intrinsics:
`AbstractQueuedSynchronizer` and what builds on it (`ReentrantLock` and its conditions,
`ReentrantReadWriteLock`, `Semaphore`, `CountDownLatch`, `CyclicBarrier`, `Phaser`,
`Exchanger`), `StampedLock`, atomics and adders, executors, `FutureTask`, `CompletableFuture`,
`ConcurrentHashMap` (the JDK's table layout and iteration order), concurrent queues and deques.
Written for scala-go instead:

- `CopyOnWriteArrayList`/`CopyOnWriteArraySet`: Scala Native's are single-threaded.
- `ConcurrentSkipListMap`/`Set`: a `TreeMap` under one lock shared by all its views, with
  snapshot iterators that never throw `ConcurrentModificationException`; not lock-free.
- `ForkJoinPool`: the JDK's is a large lock-free design for platform threads, and Go's scheduler
  already does much of its work. scala-go's keeps the API and behavior on a simpler structure.

**`ForkJoinPool`:**

- Workers are goroutine-backed `ForkJoinWorkerThread`s from the pool's factory, so Scala's global
  `ExecutionContext` installs its `BlockContext` as on the JVM.
- Each worker has a deque under a spin lock: `fork()` pushes there, the owner takes LIFO (FIFO in
  async mode), idle workers steal FIFO. Submissions from other threads go to a lock-free queue.
- Workers start on demand up to the parallelism, park when idle (registering first, then
  scanning once more, so no wake-up is lost) and exit after the keep-alive time.
- Blocking is compensated: a worker in `managedBlock` (Scala's `blocking {}`) or in `join` does
  not count against the parallelism. `join` helps first: it runs the joined task if still queued,
  then other local or stolen tasks.
- `ForkJoinTask`, `RecursiveTask`, `RecursiveAction`, `CountedCompleter` are Scala Native's.
  `commonPool()` has `availableProcessors() - 1` workers, as on the JVM.

**`scala.concurrent`** runs unchanged: `ExecutionContext.global` is a `ForkJoinPool`, `Await`
uses `AbstractQueuedSynchronizer`, `blocking {}` is a managed block. Other runtime state is
goroutine-safe too: the stdout buffer, string interning, the `Class` table, `String.hashCode`'s
cache.

### Performance

24 cores; warm JVM 25 for comparison; ranges because the machine was loaded.

| Operation                                        | scala-go   | JVM        |
|--------------------------------------------------|------------|------------|
| uncontended `ReentrantLock` lock/unlock          | ~95 ns     | 20–30 ns   |
| uncontended `synchronized`                       | ~50 ns     |            |
| `AtomicInteger.incrementAndGet`                  | ~10 ns     | ~10 ns     |
| chain of 10⁵ `Future.map`s, per step             | 0.7–1.1 µs | 0.4–0.7 µs |
| `Future(x + 1)` chained by `flatMap`, per step   | 2.3–3.9 µs | 0.6–2.1 µs |
| `Future.sequence` of 10⁵ futures, per future     | 3–4.7 µs   | 2–3.8 µs   |
| `ConcurrentHashMap.merge`, 8 threads, 1,000 keys | ~120 ns    | 55–75 ns   |
| `ConcurrentHashMap.get`                          | 13–16 ns   | 5–25 ns    |
| `LongAdder`, 8 threads (the weak spot)           | 45–120 ns  | ~10 ns     |

### What is not guaranteed

Ordinary fields are plain Go memory, so a data race on them is a bug, as on the JVM. But the
JMM gives racy programs some guarantees and Go does not: a racy read of a reference (an
interface or slice header in Go) can see a torn value and crash. `final` fields get no
publication guarantee either. Share objects through `synchronized`, volatiles, atomics,
`Thread.start`/`join` or Go channels, all happens-before edges, and check with Go's race
detector (`go build -race`), under which the `concurrency` test runs.

## gears

The concurrency API is [gears](https://github.com/lampepfl/gears), used as published. Its JVM
implementation runs each `Future` on a virtual thread and suspends by blocking it, with
`ReentrantLock`/`Condition`. Under scala-go a virtual thread is a goroutine-backed `Thread`, so
the same code runs on goroutines.

- **Compilation.** gears is compiled from its TASTy like any dependency. `scalago-gears`
  overrides `gears.async.VThreadScheduler`, the one class needing `Thread.ofVirtual` and a
  `VarHandle` (`gears/src/scalago/gearsrt/`, renamed to `gears.async`).
  `import gears.async.default.given` works unchanged.
- **From javalib** gears uses `ReentrantLock` and `Condition` (on `AbstractQueuedSynchronizer`
  and parking), atomics, `ConcurrentLinkedQueue` and `TimeUnit`. Cancellation interrupts the
  thread, which wakes sleeps, waits, conditions and blocked Go channel operations.
- **Go integration** (`scalago.gears`, `gears/src/scalago/gears/GoGears.scala`):
  `GoChan.awaitReceive`/`awaitSend` are cancellable channel operations in gears code;
  `withGoContext(ctx)(body)` cancels `body` when a Go `context.Context` is done, which exported
  code uses to honour its Go callers' contexts.

The `gears` test runs the same program on JVM virtual threads and on goroutines (also under the
race detector); `gears-go-context` cancels gears code with a Go deadline.

## Semantics handled explicitly

- **JVM numerics:** wrapping arithmetic, masked shift counts, `>>>`, saturating float-to-int
  conversions, float `%`. Literals are folded where Go would reject constant overflow.
- **Equality and strings.** `==` is null-safe and numerically cooperative (`rt.Equals`), and
  identity on Java enums (whose `equals` is final), Scala 3 enums extending `java.lang.Enum`
  included. `##` matches `Statics.anyHash`. `toString` matches the JDK, `Double.toString`
  included. String `hashCode` and `compareTo` work on UTF-16.
- **Scala 3 enums.** The cases of an enum extending `java.lang.Enum` are static fields of the
  companion object's class, set by its constructor (the JVM's static initializer); reading one
  constructs the object first.
- **Specialized tuples.** Scala 3 compiles tuples of primitives to the JVM library's Scala
  2-specialized classes (`Tuple2$mcJD$sp`, `_1$mcJ$sp`), which the library's TASTy lacks. The
  plugin (`SpecializedTuples`) uses the generic classes, with boxing.
- **`Enumeration` names.** On the JVM, a value created without a name (`val Mon, Tue = Value`)
  is named after the public `val` holding it, found by reflection. Scala.js's compiler rewrites
  such calls into `Value(name)`. scala-go's plugin (`EnumerationNames`) instead follows each such
  field assignment in an `Enumeration` subclass with a call `scalago$registerName`, which
  javalib's reflection-free `Enumeration` records.
  - As on the JVM, every value held by a public `val` is named, also `new Val` and its
    subclasses (`case class PlanetVal(...) extends Val`); an explicit name or `nextName` wins;
    other values print `<Invalid enum: no field for #i>`. Values held by trait `val`s are not
    named.
  - It runs in `genGoIR`, after constructors assign the fields, so JVM bytecode is unchanged; it
    also applies to libraries compiled from TASTy (`BigDecimal.RoundingMode`).
- **`BigDecimal`** (`rt/bigdecimal.go`) is written in Go to the JDK's specification, with a
  `long` fast path like the JDK's: money-like values need no `math/big` and one allocation per
  operation.
  - Results, scales, string forms, `hashCode` and exception messages match the JDK, down to
    `"/ by zero"` versus `"BigInteger divide by zero"` (which depends on the JDK's internal
    representation). Checked against JDK 25 on 700,000 random operations (`MathContext`
    arithmetic in every rounding mode, `sqrt`, `pow`, conversions, parsing, extreme scales).
  - Where JDK versions differ it follows the newest: `sqrt` is correctly rounded in every
    rounding mode (JDK 21's is not for the directed ones); `valueOf(NaN)` says `Infinite or NaN`.
  - `doubleValue`/`floatValue` round correctly, through `strconv.ParseFloat` beyond the fast
    paths. `MathContext`s reach the natives packed into one `int64` (`MathContext.goBits`).
  - `scala.math.BigDecimal` is the Scala library's, unchanged.

## Status

What works is documented for users in [The JDK](_docs/libraries/jdk.md),
[Scala libraries](_docs/libraries/scala.md), [Differences from the JVM](_docs/user/differences.md) and
[Limitations](_docs/user/limitations.md); the sections above list the smaller differences per area.
Features are covered by end-to-end tests (`tests/cases`: each program runs as Go, and on the JVM
unless it calls Go, against the same expected output) and by `examples/`. What does not work is
pinned by tests too: the `limits-*` cases expect each limitation below and in
[Limitations](_docs/user/limitations.md) to fail (a compile or link error, or today's output),
and the `known-bugs*` cases pin each [known bug](roadmap.md#known-bugs). Lifting a limitation
or fixing a bug makes its case fail, so the documentation is updated with it.

These limitations follow from the design and are not planned to change:

- **Reflection.** `getClass`, `Class` names, `isAssignableFrom` (the linker passes the class
  hierarchy to the runtime when the program uses it) and `java.lang.reflect.Array` work.
  Members (`getMethods`, `getDeclaredFields`, `Method.invoke`), run-time annotations, dynamic
  proxies, method handles, class loading and bytecode generation do not exist in a Go binary.
  The [roadmap](roadmap.md) adds only what the linker can decide (type tests by `Class`,
  reflective instantiation, structural calls).
- **No Go counterpart:** Java serialization (`ObjectOutputStream`, `ObjectInputStream`), JMX
  (`java.lang.management`, `javax.management`), Java agents, `SecurityManager`, JNI
  (`System.loadLibrary`). Go code, cgo included, is reached through facades instead.
- **Libraries without TASTy** (Java libraries such as Netty, Jackson, SLF4J and JDBC drivers;
  Scala 2 libraries) are missing at link time. Natives cover the few Java classes of popular
  Scala libraries that reachable code uses (ujson's float formatting); a library can be patched
  by overriding classes, as `scalago-gears` does. Go libraries take the place of Java ones.
- **Stack overflow and out of memory end the program.** Go grows a goroutine's stack to 1 GB (a
  recursion 100,000 calls deep works), then aborts, as when memory runs out:
  `StackOverflowError` and `OutOfMemoryError` are never thrown.
- **Finalizers** (`Object.finalize`) never run.
- **Data races** can crash a program; `final` fields have no publication guarantee
  ([Concurrency](#concurrency-and-the-memory-model)).
- **Regular expressions** are RE2's (Go's `regexp`): no lookaround, backreferences or possessive
  quantifiers.
- **Strings are UTF-16**, as on the JVM, so passing them to Go transcodes (`GoString` avoids it).
- **One Scala library per Go binary** ([Exports](#exports-and-library-builds)).
- **Arrays of references do not know their element type.** `toArray(new Array[String](0))`,
  `Arrays.copyOf` and `ClassTag[String].newArray` return arrays whose `getClass` is `Object[]`;
  a non-empty reference array holding only `()` is an `Array[Unit]` to type tests (which the
  Scala library makes before `Array[AnyRef]`). An element class per array would cost a word per
  array and a check per store.
- **`BigInteger` and `BigDecimal` cannot be subclassed**: they are Go values.
- **Little-endian only.** `ByteOrder.nativeOrder()` is little-endian, so Go's big-endian targets
  (s390x, ppc64, mips) are unsupported. 32-bit targets are untested.

**Missing APIs.** When reachable code uses something missing, linking fails with a "called
from" chain naming the API. Reachability over-approximates (virtual calls resolve by name), so a
program can fail to link because of code it never runs. With `--missing-apis throw` (Mill:
`goMissingApis`), such uses are warnings, and each throws the JVM's linkage error where used, if
it runs: `NoClassDefFoundError`, `NoSuchMethodError`, `NoSuchFieldError`, or
`UnsupportedOperationException` for code the plugin could not translate. These are
`LinkageError`s, so `NonFatal` does not catch them, as on the JVM.

Status of work and plans: [Roadmap](roadmap.md).
