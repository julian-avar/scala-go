# scala-go design

scala-go compiles Scala 3 to Go with the architecture of Scala.js and Scala Native: a compiler
plugin emits IR per class, and a whole-program linker turns all IR into one Go module. This
document says how. What programs observe is in the [Specification](_docs/spec/index.md); plans
and known bugs are in the [Roadmap](roadmap.md).

## Pipeline

```
 .scala ──scalac + plugin──▶ .class + .tasty + .goir        (per module, incremental)

 scala-library.jar (TASTy) ──scalac -from-tasty + plugin──▶ stdlib .goir     (built once)
 javalib/ (Scala sources)  ──scalac + plugin─────────────▶ java.* .goir      (built once)

            all .goir on the classpath (program + javalib + stdlib)
                                     ▼
          linker: lambdas → classes, reachability, Go codegen ──▶ Go module ──go build──▶ binary
```

| Component | Code | Role |
|-----------|------|------|
| Compiler plugin | `plugin/` | phase `genGoIR`: one `.goir` per class, next to its `.class` |
| IR | `ir/` | typed, erased trees; methods identified by name and erased signature, as on the JVM; binary format with a string table and a format version |
| Library IR | `mill-build/.../TastyIR.scala` | IR of the Scala library and Scala dependencies, recompiled from TASTy |
| javalib | `javalib/` | the JDK, written in Scala |
| Linker | `linker/` | links all IR into a Go module (`Linker.link`; command line `scalago.linker.Main`) |
| Runtime | `runtime/rt` | Go support code, bundled in the linker jar and copied into every module as `internal/rt` |
| goapi | `runtime/goapi` | helper the linker `go run`s to read Go package APIs |
| Mill plugin | `mill-build/src/scalago/mill/ScalaGoModule.scala` | adds the compiler plugin; `goLink`, `goBuild`, `goRun`. Links in its own JVM: Mill runs on Scala 3.8, the linker on 3.9 |

### Compiler plugin

Phase `genGoIR` (`ScalaGoPlugin`, `GoIRCodeGen`) runs after `repeatableAnnotations`, the last
tree transform, and before `genBCode`, which still runs. Trees are erased, pattern matching is
lowered to labeled blocks, lambdas are lifted, classes flattened and mixins expanded: the plugin
translates a small Java-like core. Phase `goTypeArgs` keeps the type arguments of generic facade
calls ([Generics](#go-interop)). The plugin jar includes the IR sources, so `-Xplugin:<jar>` is
self-contained.

| Option | Effect |
|--------|--------|
| `-P:scala-go:lenient` | untranslatable code becomes `Unsupported` IR instead of a compile error; linking fails only if it is reachable |
| `-P:scala-go:rename:a.b:c.d` | emits classes of package `a.b` (and subpackages) as `c.d` |
| `-P:scala-go:static-forwarders` | adds static forwarders for objects' methods, as the JVM backend does (javalib) |

The plugin uses compiler internals, so it targets exactly one Scala version (3.9.0,
`ScalaGoModule.ScalaVersion`) and is published with the full version as suffix.

### Libraries from TASTy

The Scala library ships TASTy (since 3.8). The build recompiles it with `-from-tasty` and the
plugin in lenient mode, stopping after `genGoIR`: about 20 s, about 2,800 classes.
`scala.quoted` is skipped; the compiler cannot recompile it from TASTy. Scala 3 dependencies go
the same way (`TastyIR`, `ScalaGoModule.scalaGoDependencyIR`): every jar with TASTy and no IR.
Published libraries such as gears therefore need no scala-go artifact (Scala.js and Scala Native
need one per library).

A library that needs JVM-only APIs is patched by overriding single classes: a module compiled
with `-P:scala-go:rename` emits replacements under the library's names, and its IR comes first
on the link classpath ([gears](#gears)). That order, where the first definition of a class wins
(`ScalaGoModule.goLink`): the module's own IR and plugin-compiled dependencies, dependencies' IR
from TASTy, javalib, the Scala library's IR.

### javalib

The JDK in Scala ([coverage](_docs/libraries/jdk.md)). It also replaces the Java-written classes
of the Scala library (`*Ref`, `LazyVals`, `JProcedureN`, `ScalaNumber`) and `Enumeration`, which
uses reflection ([Semantics](#semantics-handled-explicitly)).

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
- **java.util.stream** keeps Scala Native's interfaces but has the JDK's push design (Scala
  Native pulls each element through a chain of spliterators, a closure per element and stage). A
  stage (`AbstractPipeline`, four element types) wraps the next stage's sink in its own; a
  terminal operation pushes the source's elements through with one `forEachRemaining`, or
  `tryAdvance` until cancellation when it short-circuits. A traversal allocates one sink per
  stage and nothing per element, and primitive streams stay unboxed. Pipelines track the JDK's
  flags (sized, sorted, distinct, short-circuiting, size-adjusting), so `peek` order, `sorted`
  as a barrier and `count()` without traversal for known sizes match the JDK.
  `Collectors.groupingBy` uses `computeIfAbsent` on a `HashMap`, so groups iterate in the JDK's
  order.

### Linker

In `linker/src/scalago/linker/`:

| Step | Code | What it does |
|------|------|--------------|
| Loading | `IRLoader`, `Program` | reads all `.goir` from the classpath; the first definition of a class wins. Where an entry has class files, IR without its class file is stale (incremental compilation deletes class files, not IR) and ignored |
| Lambdas | `Lambdas` | `Closure` nodes become synthetic classes whose single abstract method calls the lifted target, with boxing and cast adaptation; bridges for other abstract methods of the same name, as `LambdaMetafactory` adds |
| Reachability | `Analyzer` | worklist from the entry point; virtual calls resolve by name over instantiated classes (over-approximating, as Scala.js); only reachable classes and methods are emitted; errors carry a "called from" chain |
| Natives | `Natives` | Go implementations of JDK methods (`String`, boxes, `Math`, `System`, `Character`, `BigInteger`, `BigDecimal`, `Arrays`, ...); they take precedence over IR |
| Emission | `ClassEmitter`, `FunctionEmitter`, `LinkContext`, `Mangling`, `LineDirectives` | [Object model](#object-model), [Control flow](#control-flow) |
| Go interop | `GoInterop`, `GoApi` | [Go interop](#go-interop) |
| Exports | `Exports` | [Exports](#exports-and-library-builds) |
| Facades | `FacadeGen`, `FacadeIndex`, `FacadeUsage` | [Generated facades](#generated-facades) |

- **Runtime objects.** javalib reaches the Go runtime through `scalago.runtime` objects whose
  methods throw on the JVM and are natives when linked: `Threads`, `AtomicArrays`, `Regexes`,
  `Console`, `OS`, `Process`, `Subprocesses`, `Net`, `Crypto`, `Zip`, `Time`, `StackTraces`.
- **Hijacked-class members in Scala.** `String` is a Go value with native methods. Members
  easier in Scala (`lines()`, `CASE_INSENSITIVE_ORDER`) are in javalib's `java.lang.String$`
  (`scalago.javalib.hijacked.lang.String`). For any hijacked class, a static member without a
  native is the object's, and an instance method without a native is the object's method taking
  the receiver first (`Program.staticMethodViaCompanion`, `hijackedCompanionMethod`), or else an
  interface's default method (`Program.hijackedDefault`: `String.chars()` is `CharSequence`'s).
- **Static fields.** Java static fields (`Collections.EMPTY_LIST`) are usually companion-object
  members in javalib. Reading a static field a class lacks calls the companion's getter of the
  same name and type (`Program.staticFieldViaCompanion`).

## Object model

| Scala | Go |
|-------|----|
| `Int`, `Long`, `Double`, ... | `int32`, `int64`, `float64`, ... (`Char` = `uint16`) |
| boxed primitive | the Go primitive inside an `any` |
| `String` | `*rt.String`: UTF-16 code units, like the JVM (`nil` = null) |
| `scalago.go.GoString` (opt-in) | Go `string` (UTF-8); UTF-8 bytes on the JVM ([Go strings](#go-strings)) |
| `BigInteger` | `*rt.BigInteger` (`math/big`) |
| `BigDecimal` | `*rt.BigDecimal`: a scale and an `int64` unscaled value, or a `big.Int` when it does not fit |
| Go facade of a struct type `T` | `*T` inside an `any` ([Go interop](#go-interop)) |
| Go facade of any other Go type | the Go value inside an `any` |
| `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go slice, map, channel or pointer inside an `any` |
| `Array[prim]` / `Array[AnyRef]` | `*rt.Array[prim]` / `*rt.Array[any]` (covariance works) |
| class `C` | struct `S_C` embedding the superclass's struct; root classes embed `rt.ObjectHeader` (the monitor) |
| reference of static type `C` | interface `C_C`: embeds the parents' interfaces, lists the methods called on `C` receivers |
| trait `T` | interface `C_T`, with a marker method `I_T()` |
| method body | free function `Impl_C__m(this C_C, ...)` (`this *S_D` if only class `D` can be `this`) |
| virtual method | forwarding method on each instantiated struct |
| `object O` | accessor `Mod_O_S()` returning a `*S_O_S`, constructed on first use under an `rt.InitLock` |
| `@static` fields, `<clinit>` | package variables, plus `Clinit_C()`, run once under an `rt.InitLock` |
| `@volatile` field | `sync/atomic` value (`rt.VolatileInt32`, ...), or `rt.Volatile[T]` for references |

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
  `excN := rt.Try(func() { ... })`, which `recover`s and translates Go runtime errors into Java
  exceptions ([Exceptions](_docs/spec/language.md#exceptions)). A `return` or `break` inside the
  closure sets `ctl` to a jump code, replayed after the closure returns, after the `finally`
  block if there is one.
- **`lazy val`** uses JVM `VarHandle`s. The plugin rewrites the handle's compare-and-set into IR
  `CompareAndSet` on the volatile lazy val field ([Concurrency](#concurrency-and-the-memory-model)).

### Stack traces

What traces show: [Runtime: stack traces](_docs/spec/runtime.md#stack-traces).

- **Positions.** The linker writes Go line directives into method bodies
  (`/*line Main.scala:5:3*/`, `LineDirectives.scala`), so Go reports Scala files and lines in
  stack traces, panics, and compiler and `go vet` messages. A directive is written only where the
  Scala line differs from the one Go would count on to; lines that cannot fail (braces, jumps,
  declarations) need none: about 5% more code, no measurable effect on `go build` time.
  `--line-directives false` (Mill: `goLineDirectives`; tests: `SCALAGO_LINE_DIRECTIVES=false`)
  shows Go positions, for debugging generated code.
- **Capture.** `Throwable`'s constructor calls `fillInStackTrace` unless `writableStackTrace` is
  false (`ControlThrowable`, `NoStackTrace`, `boundary`'s `Break`). It records Go program
  counters by following frame pointers on amd64 and arm64 (about 1 ns per frame), elsewhere
  with `runtime.Callers`. Runtime errors become Java exceptions while their panic is recovered,
  before the stack unwinds, so the trace starts at the fault; they use `runtime.Callers`, because
  a faulting leaf function has no frame record of its own, and so do programs that have called C
  code.
- **Symbolization** happens on the first `getStackTrace` or `printStackTrace`: method
  implementations (`Impl_<class>__<method>...`) demangle to Scala names, so no table is needed.
- **Dropped frames**, which the JVM would not show: Go runtime and `rt` frames, generated helpers
  (forwarders, dispatchers, allocators, module accessors), lambda classes, the Throwable's own
  `fillInStackTrace` and constructors, and the function enclosing a `try` closure (the
  closure's frame stands for it, as the JVM has one frame per invocation). `SCALAGO_TRACE=1`
  keeps every frame, with Go names and positions. An object's `main` runs through the static
  forwarder `Main.main`, shown without a line number (`at Main.main(Main.scala)`), as on the
  JVM.

## Go interop

Go code is called through **facades**, Scala declarations of Go APIs, as Scala.js's JS facades
([guide](_docs/interop/facades.md), [rules](_docs/spec/interop.md)):

```scala
import scalago.go.*

@GoImport("net/url")
object url:
  def Parse(rawURL: String): URL = native   // func Parse(string) (*URL, error)
  class URL:                                // type URL struct { ... }
    def Host: String = native               // a field
    def Query(): Values = native            // a method
  class Values:                             // type Values map[string][]string
    def Get(key: String): String = native
```

- **Declarations** are in `library/src/scalago/go/Interop.scala`. Members are `= native`, which
  throws on the JVM, so programs that use Go code are Go-only.
- **The plugin** checks declarations (`def` only, `= native`, no constructor or type
  parameters) and records the Go name and the Scala signature *before erasure* in the IR
  (`GoMember`, `ScalaType`): conversions need type arguments (`Int => Boolean`,
  `(Int, GoError)`, `Seq[String]`) and opaque types (`GoString`) that erasure loses.
- **Go signatures come from the Go toolchain**, not from facades. The linker runs `goapi`, which
  reads the used packages' export data (`go list -export`, so the Go build cache) and prints
  their API, plus the declarations of other packages' exported types they use, transitively (so
  `time.Duration` parameters convert without a facade of `time`). Each facade use is checked
  against it, and conversions are driven by both types. So facades use natural Scala types
  (`Int` for `int`), and a mismatch is a link error at the Scala call site, not a Go compile
  error in generated code.
- **Modules.** Packages resolve in the output module, whose `go.mod` lists the configured
  requirements (`--go-require`, `--go-replace`; Mill: `goRequires`, `goLocalModules`).
  `go list -mod=mod` completes `go.mod`/`go.sum`, which needs the network for third-party
  modules: for the facades' packages, then for the modules they import, which `go build` wants
  listed as indirect requirements (`GoApi.completeGoMod`). Imports use aliases
  (`gp_url "net/url"`), so they cannot clash with generated names or each other.
- **Conversions** (`GoInterop.toGo`/`fromGo`) follow
  [Values across the boundary](_docs/spec/interop.md#values-across-the-boundary), which names
  the runtime helpers. A Scala function for a Go func type becomes a Go closure calling `apply`
  through a dispatcher; a Go func returned to Scala is a `GoFunctionN` wrapping a Go closure that
  converts with the facade's types. Struct facades hold pointers: a type test against one
  accepts `T` and `*T`, and the cast copies a value to a new pointer (`rt.IsStruct`,
  `rt.CastStruct`).
- **Go interfaces implemented in Scala.** A Scala class extending a facade trait gets Go methods
  with the Go names on its struct; they convert arguments, call the Scala method, and convert the
  result.
- **Unnamable types** (unexported, `internal`): `goapi` reports them as opaque, with the
  interfaces they implement (of their package and its imports); calls involving them go through
  `rt.CallReflect`.

**Generics.** Facade members and classes may be generic (`def Max[E](x: GoSlice[E]): E`); their
signatures record type parameters as `ScalaType.Param`. Erasure drops call sites' type
arguments, so the plugin's `goTypeArgs` phase (before `firstTransform`: placed among the later
transforms it changed their grouping, and erasure left some calls unapplied) attaches them to the
`Apply` trees as a sticky attachment, and `genGoIR` writes them into the IR (`Apply.typeArgs`,
`New.typeArgs`, by type parameter name; `Traversal.transform` keeps them). The linker
substitutes them, unboxes and boxes values of type-parameter types, binds Go type parameters as
in [Generic functions](_docs/spec/interop.md#generic-functions), and instantiates the Go
function explicitly (`gp_slices.Max[[]int, int](x)`). Go checks the constraints.

### Generated facades

`FacadeGen` (`scalago.linker.FacadeGen`; Mill: `goFacadePackages`) writes facades from `goapi`'s
output, as ScalablyTyped does from `.d.ts` files: each package becomes one `@GoImport` object,
its types classes and traits ([mapping](_docs/interop/generated-facades.md#how-go-types-map)).

- **Aliases.** Func types and interfaces without methods become aliases of Scala function types
  and `Any`, so Scala lambdas and values pass as they are. Aliases are erased in the IR, so the
  plugin records facade types before erasure, aliases followed.
- **Structural to nominal.** A generated type extends every generated interface whose methods
  it has (`class Buffer extends io.Writer, io.Reader, ...`).
- **Transitive types.** Types of other packages that generated members use are generated too;
  FacadeGen reruns `goapi` until no package is missing. Members it cannot express get a
  `// not generated:` comment.
- **Reuse.** FacadeGen reads the facades of its classpath from the IR (`FacadeIndex`): classes
  and traits by their `GoFacade`, type aliases, which have no class, by the package facade that
  records them (`GoFacade.typeAliases`). Reading IR rather than a manifest covers handwritten
  facades and needs nothing beyond what jars contain. Go types a dependency provides are not
  generated, so a Go type has one Scala type and values flow between libraries. A generated type
  extends a dependency's interface facade only if its recorded `GoMember` types are those
  FacadeGen would generate. The linker matches facades by Go type, not by class, so a Go package
  can have facade objects in several modules.

Generated facades are checked at link time like handwritten ones, only where used, so unused
members cost nothing. `scalago-gostd` (`gostd/`) has facades of the whole standard library
(`go list std` but `internal`, `vendor`, `unsafe`; about 180 packages, 16,700 members).
`gostd.usage` checks all of them: `FacadeUsage` writes a program using every facade member of a
package (read from the IR, with `null` arguments, in a branch that never runs), which is linked
and built (`mill gostd.usage.goBuild`).

### Go data in Scala

`GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]` and `GoPtr[T]` (`library/src/scalago/go/`) are opaque
types whose values are Go slices, maps, channels and pointers, shared with Go without copying
(as Scala.js's `js.Array`). Elements have **canonical** Scala views decided by the Go element
type alone, so erasure loses nothing ([Go data](_docs/spec/interop.md#go-data)).

- `rt/gotypes.go` converts by the actual Go type: fast paths for common slice types,
  reflection otherwise. Link-time checks name the right view (`GoSlice[GoString]` for a Go
  `[]string`).
- Creating Go data needs the Go element type, which a `GoType[T]` given supplies. `GoInt`,
  `GoByte` and the other Go numeric types are opaque over Scala numbers, so they only matter
  here and in facades.
- Every operation has a JVM implementation with Go's semantics (`GoNatives`), so code using Go
  data without facades runs on the JVM too.
- **`GoData`** (`GoData.scala`) copies between Scala data and the plain Go data that Go code
  inspecting values by reflection (`encoding/json`) expects; natives `rt.GoDataKind` and
  `rt.GoBasicData` classify Go values. It copies explicitly because converting Scala values
  passed to `any` automatically would break identity (a Scala message passed through bubbletea
  must come back as itself).

### Go strings

`String` stays `java.lang.String` (UTF-16), so programs behave as on the JVM, and converting it
to Go's `string` transcodes. `scalago.go.GoString` (`library/src/scalago/go/GoString.scala`) is
the opt-in Go `string`, passed to and from Go as is. On the JVM it is a `JvmGoString` holding
UTF-8 bytes, whose `==`, `hashCode`, `toString` and `Ordering` work by content. Converting a
`String` turns unpaired surrogates into U+FFFD on both (Go's rule; the JDK writes `?`).

- **`go"..."`** (`GoInterpolator.scala`) is a macro: it processes escapes as `s` does, at compile
  time (bad escapes are compile errors), and expands to `GoString(part0).concat(arg0).concat(GoString(part1))...`; non-`GoString`
  arguments become `GoString.valueOf(arg)`. A macro leaves the plugin and the IR alone, reuses
  the compiler's escape processing, and is the JVM implementation too. There is no `f`-style
  variant: `java.util.Formatter` would need a second implementation over Go strings.
- **The linker** (`FunctionEmitter.genGoString`) emits `GoString(s)`, `GoString.valueOf(x)` and
  `concat` chains, handwritten or not, as one Go `string` expression. Literals are Go constants
  (a literal-only `go"..."` is `any("...")`, free at run time); `valueOf` is `rt.ToGoString`,
  which formats primitives straight into a Go string; operands are joined by one `+`, a single
  allocation, after being spilled left to right like call arguments.

## The JDK over Go

Where the JDK talks to the operating system, javalib is Scala over a small native layer on Go's
standard library, reached through a `scalago.runtime` object and implemented in `rt/`. Java
semantics (which exception, which message) are in Scala; the layer knows nothing of Java's
classes. Differences from the JDK: [The JDK](_docs/libraries/jdk.md).

### I/O

`java.io` and `java.nio` over Go's `os` (`scalago.runtime.OS`, `rt/files.go`). Natives: handles
(`open(path, mode)` returns an `*os.File` held as an `Object`; `read`, `write`, positional
`readAt`/`writeAt` for file channels, `seek`, `size`, `available`, `truncate`, `sync`,
`lock`/`unlock`, `close`); path operations (`stat`, `mkdir`, `readDir`, `rename`, `symlink`,
`realPath`, ...) returning the result or a Go error; `errorKind` (no such file, exists, not
empty, access denied, not a directory, is a directory, loop) and `errorReason`, the C library's
text.

- From these javalib builds the JDK's exceptions: `FileNotFoundException("<path> (<reason>)")`
  in `java.io`; `NoSuchFileException`, `FileAlreadyExistsException`,
  `DirectoryNotEmptyException`, `AccessDeniedException` or `FileSystemException` in
  `java.nio.file`. Locks are POSIX record locks with `fcntl`, as the JDK's. Streams, readers and
  writers are adapted from Scala Native.
- **Buffers** are heap buffers. Each element type's buffer has abstract element access (`load`,
  `store`) and the backing array when there is one, which bulk operations copy with
  `System.arraycopy`. All but the byte classes are generated from `javalib/templates/*.template`
  by javalib's `generatedSources`, with the JDK's class names (`HeapIntBufferR`), which
  `toString` shows.
- **Charsets.** Coders work on arrays (`decodeArray`/`encodeArray`), which `InputStreamReader`,
  `OutputStreamWriter`, `Files` and `new String(bytes, ...)` call directly. `CharsetDecoder`/
  `CharsetEncoder` add the JDK's buffer API on top (`CoderResult`, the coding state machine),
  copying buffers without an accessible array.
- **Channels and file systems.** A `FileChannel` from `getChannel` shares the stream's file and
  position, as on the JDK; `map` reads the region into a heap `MappedByteBuffer`. Globs are
  translated as by the JDK's `Globs`, into a regular expression for Go's engine.
- **Standard streams.** `System.out`'s and `System.err`'s print methods hand the value to
  `Console.write`, which transcodes it straight into a 32 KB stdout buffer (stderr is
  unbuffered): one call and one lock per `print`/`println`. `Predef.println` goes through the
  Scala library's `Console`; javalib's `scala.util.DynamicVariable` skips the thread-local lookup
  until a variable is first set, so `Console.out` costs one atomic load while nothing is
  redirected. Flush points: [Standard I/O](_docs/spec/runtime.md#standard-io).

### Subprocesses

`ProcessBuilder`, `Process` and `ProcessHandle` over Go's `os/exec`
(`scalago.runtime.Subprocesses`, `rt/subprocess.go`).

- `start` passes the environment only if the program touched `environment()` (a copy of
  `System.getenv()` checked as the JDK checks it). The program is looked up on the parent's
  `PATH`; a relative program path is relative to the directory.
- **Pipes** are `os.Pipe`s; the parent keeps its end as a file-layer handle, so a process's
  streams are `java.io` file streams, buffered as the JDK's. `INHERIT` flushes stdout first;
  redirect files are opened by the parent.
- A goroutine per process waits and records the exit status (128 plus the signal number for a
  signal); `waitFor` blocks on a channel, interruptibly. `destroy` sends `SIGTERM`,
  `destroyForcibly` `SIGKILL`.

`scala.sys.process` is the Scala library's, unchanged: its pumping threads are goroutine-backed
`Thread`s and its pipes javalib's `PipedInputStream`s.

### Networking

`java.net` and `java.net.http` over Go's `net` and `net/http` (`scalago.runtime.Net`,
`rt/net.go`, `rt/http.go`). Addresses are byte arrays; connections, listeners and HTTP exchanges
are handles. The layer classifies Go errors (refused, reset, timed out, closed, unknown host,
address in use, TLS, ...), which javalib turns into the JDK's exceptions and messages.

- **`URI`** follows the JDK's parser (RFC 2396 with the JDK's deviations, messages and error
  indices), checked against JDK 21 and 25 on 1,700 cases. Names resolve with Go's resolver,
  IPv4 first as on the JDK.
- **Sockets** are Go TCP connections and listeners. `setSoTimeout` sets a deadline before each
  blocking read or accept, so the socket stays usable after a timeout. Closing a socket from
  another thread closes the Go connection, which ends a blocked read or accept.
- **HTTP.** `HttpURLConnection` and `HttpClient` share one `net/http` transport (keep-alive,
  HTTP/1.1, no proxy, no decompression, `crypto/tls` with the system's root certificates). A
  request, its body buffered, is sent when its response is first needed. Timeouts are timers
  that cancel the exchange.
- **Response bodies** are streams over the socket read native. `HttpClient` hands one on demand
  to the response's `BodySubscriber`, reading each chunk in the thread that requests it, so
  `send` needs no thread of its own. `sendAsync` runs `send` on the client's executor.

### Digests and compression

`MessageDigest`, `Mac` and `SecureRandom` are over Go's crypto packages
(`scalago.runtime.Crypto`, `rt/crypto.go`). A digest in progress is a Go hash, cloned through its
marshalled state; HMAC is written over those hashes so that `Mac.clone` works. `SecureRandom`
reads `crypto/rand`.

`java.util.zip` is Scala, except checksums (`hash/crc32`)
and the deflate state machines on `compress/flate` (`scalago.runtime.Zip`, `rt/zip.go`).

- **`Deflater`** writes through flate writers into a buffer of pending output, with the zlib
  header and trailer written by hand, so a level change mid-stream ends the current writer's
  blocks and continues the stream with a new writer.
- **`Inflater`** is fed piece by piece, but Go's flate reader pulls its input and cannot resume
  after running out. So the reader runs on a goroutine, as a coroutine of the `inflate` calls:
  control passes over channels, and the two never run at once. A finalizer stops the goroutine
  of an inflater dropped without `end()`. The reader reads one byte at a time, never beyond the
  compressed data, so `getRemaining()` is exact, which GZIP and zip trailers rely on.
- The formats follow the JDK: data descriptors, UTF-8 names, DOS times in the local zone with
  extended and NTFS timestamps, concatenated GZIP members, ZIP64 when reading.

### java.time

`java.time` (`javalib/src/java/time`) is
[scala-java-time](https://github.com/cquiroz/scala-java-time) 2.9.0, the ThreeTen backport port
that Scala.js and Scala Native use, vendored as source (BSD-3-Clause,
`javalib/LICENSE-scala-java-time.md`); changes are marked `scala-go:`. No artifact fits: the JVM
one is empty, the Scala.js and Scala Native ones contain only compiled output and depend on
platform libraries.

- **Time zones are Go's**: the system's zoneinfo, or Go's embedded copy (`time/tzdata`, about
  450 KB), which the linker imports (`internal/scala/tzdata.go`) only when a program can reach a
  named zone (`scalago.runtime.Time.zone`). `DateTimeFormatter` patterns can parse zone IDs, so
  using patterns reaches it.
- **Zone rules.** A region's `ZoneRules` (`zone/GoZoneRules.scala`) wrap its `*time.Location`,
  which Go does not let programs walk. Go gives the offset at an instant and the period during
  which it holds (`Time.ZoneBounds`); transitions are ends of periods with different offsets.
  `getStandardOffset` is the offset of the latest period that is not daylight saving time.
- **Local date-times** resolve by examining the periods within 18 hours (the largest offset).
  Each period whose offset maps the local date-time back into itself gives a valid offset: one,
  none (a gap) or two (an overlap), with no copy of the database in Scala. Without a system
  zoneinfo, zone IDs parse as the longest name Go loads.
- **The system zone** is found as the JDK does on Linux; for `/etc/localtime`, from the link's
  target or the zoneinfo file matching its contents.
- **Other changes:** caches threads would race on are synchronized or built once; JDK 9–18
  methods the backport lacks are added; exception messages are the JDK's; `LocalDate.plusDays`
  stays within the month when it can. javalib adds `java.util.Date`, `TimeZone` on java.time's
  zones, and the `java.text` base classes the API mentions.

## Exports and library builds

Go code calls Scala code through **exports** (`Exports.scala`), as Scala.js's
`@JSExportTopLevel` ([guide](_docs/interop/exports.md), [rules](_docs/spec/interop.md#exports)).

- **Library links** (`--library true`, Mill's `ScalaGoLibraryModule`) have no entry point; the
  roots are the `@GoExport` definitions. The root package (named after the module path's last
  element, or `--package`) is the library's Go API, in `exports.go`.
- **Exported classes** become Go interface types aliased from the generated package
  (`type Counter = scala.X_Counter`), with `New<Name>` for the primary constructor.
- **Identity.** The interface value holds the generated struct pointer, so a Scala object is the
  same Go value every time it crosses, with no wrappers. Each instantiated subclass's struct gets
  its own Go methods, calling the Scala methods virtually: methods promoted from an embedded
  struct would lose the outer receiver.
- **Conversions** follow the facade rules, with Go types chosen from Scala types
  (`GoInterop.goTypeOf`).

## Concurrency and the memory model

Go code calls Scala code from any goroutine, so the runtime and the generated code are
goroutine-safe, with the JVM's guarantees where Scala code relies on them
([Memory model](_docs/spec/runtime.md#memory-model)).

- **Initialization.** Module construction and static initializers run once under an
  `rt.InitLock`; the fast path is one atomic load. Other goroutines wait for an initialization
  in progress; the initializing goroutine re-entering sees the partial state, as on the JVM.
- **Monitors.** The plugin lowers `synchronized` to enter/exit around `try`/`finally`. Scala
  objects keep the monitor in `rt.ObjectHeader`, created on first use; strings, boxes and Go
  values use a global table whose entries are never removed. An uncontended enter/exit costs
  about 55 ns.
- **Goroutine ids** are needed by monitors, `Thread.currentThread` and interruptible channel
  operations; Go does not expose them. On amd64 and arm64, `rt/goid.go` reads the id from the
  goroutine's runtime structure: a two-instruction assembly `getg`, plus the field offset found
  at startup by matching the ids `runtime.Stack` reports for several goroutines (about 4 ns).
  Without the offset (a future Go layout, another architecture), the runtime parses
  `runtime.Stack` (about 3.5 µs); `goid_test.go` fails in that case on amd64 and arm64.
- **Volatile fields** (`@volatile`, and each `lazy val`'s state field) are sequentially
  consistent. Primitive fields are `sync/atomic` values (`rt.VolatileInt32`, ...), which never
  allocate. Go cannot access an interface atomically, so a reference field is an
  `rt.Volatile[T]`: an atomic pointer to an immutable box. A store allocates a box, except for
  null and for re-storing the last stored object (a lock owner alternating between a thread and
  null), whose box it reuses.
- **Atomic intrinsics.** javalib's atomics do read-modify-write through
  `scalago.runtime.Intrinsics`: `compareAndSet`, `getAndSet`, `getAndAdd`, whose first argument
  is the field or its getter. The plugin turns them into IR `CompareAndSet`, `GetAndSet`,
  `GetAndAdd`; the linker into `sync/atomic`'s `CompareAndSwap`, `Swap`, `Add`. Rarer operations
  (`getAndBitwiseOr`, ...) are compare-and-set loops. Atomic arrays
  (`scalago.runtime.AtomicArrays`) are `Int` and `Long` arrays in place, reference arrays Go
  slices of atomic pointers (`scalago.runtime.VolatileArray`).
- **Benign races.** The JDK's concurrent classes read some plain reference fields racily, which
  the JMM makes safe and Go does not (a racy interface read can be torn). In the ported classes
  they are volatile: `ConcurrentHashMap`'s tables and `LongAdder`'s cells are `VolatileArray`s; a
  lock's owner, `AbstractQueuedSynchronizer`'s waiters and `CompletableFuture`'s stage actions
  are `@volatile`. All of `java.util.concurrent` runs under the race detector in the tests.
- **Lazy vals** follow Scala 3's scheme: compare-and-set on the volatile field; goroutines that
  find the value being computed wait on a `Waiting` marker (a monitor).

### Threads

- **Goroutine-backed.** `java.lang.Thread` runs on a goroutine; virtual threads are threads like
  the others.
- **Foreign goroutines** (the main goroutine, or one started by Go code) get a `Thread` the first
  time they ask, kept while they run. Go does not report goroutine exit, so when the number of
  such `Thread`s doubles, the runtime drops those of ended goroutines, found from a full stack
  dump's goroutine ids (never reused). A Go server calling Scala per request does not leak them.
- **`Thread.currentThread`** is a lookup by goroutine id through a direct-mapped cache: an
  atomic load and a comparison.
- **Parking** (`LockSupport`), the basis of the synchronizers: each thread has a permit, a
  one-slot Go channel. `park` takes it, or blocks until `unpark`, an interrupt or the timeout.

### java.util.concurrent

Scala Native's port of JSR-166 (Doug Lea's public-domain code), adapted to the intrinsics:
`AbstractQueuedSynchronizer` and what builds on it (locks, `Semaphore`, latches, barriers,
`Phaser`, `Exchanger`), `StampedLock`, atomics and adders, executors, `CompletableFuture`,
`ConcurrentHashMap` (the JDK's table layout and iteration order), concurrent queues and deques.
Written for scala-go instead:

- `CopyOnWriteArrayList`/`CopyOnWriteArraySet`: Scala Native's are single-threaded.
- `ConcurrentSkipListMap`/`Set`: a `TreeMap` under one lock shared by all its views, with
  snapshot iterators that never throw `ConcurrentModificationException`; not lock-free.
- `ForkJoinPool`: the JDK's is a large lock-free design for platform threads, and Go's scheduler
  already does much of its work. scala-go's keeps the API and behavior on a simpler structure.
  Workers are goroutine-backed `ForkJoinWorkerThread`s from the pool's factory (so Scala's global
  `ExecutionContext` installs its `BlockContext`). Each has a deque under a spin lock: `fork()`
  pushes there, the owner takes LIFO (FIFO in async mode), idle workers steal FIFO; submissions
  from other threads go to a lock-free queue. Workers start on demand up to the parallelism,
  park when idle (registering first, then scanning once more, so no wake-up is lost) and exit
  after the keep-alive time. A worker in `managedBlock` (Scala's `blocking {}`) or `join` does
  not count against the parallelism; `join` first runs the joined task if still queued, then
  other local or stolen tasks. `ForkJoinTask` and its subclasses are Scala Native's.

`scala.concurrent` runs unchanged on these. Against a warm JVM 25: an uncontended `ReentrantLock`
lock/unlock is about 95 ns (JVM 20–30 ns), `AtomicInteger.incrementAndGet` about 10 ns on both,
and `LongAdder` under 8 threads, the weak spot, 45–120 ns (JVM about 10 ns).

## gears

The concurrency API is [gears](https://github.com/lampepfl/gears), used as published. Its JVM
implementation runs each `Future` on a virtual thread and suspends by blocking it, with
`ReentrantLock`/`Condition`. Under scala-go a virtual thread is a goroutine-backed `Thread`, so
the same code runs on goroutines.

- gears is compiled from its TASTy like any dependency. `scalago-gears` overrides
  `gears.async.VThreadScheduler`, the one class needing `Thread.ofVirtual` and a `VarHandle`
  (`gears/src/scalago/gearsrt/`, renamed to `gears.async`).
- Cancellation interrupts the thread, which wakes sleeps, waits, conditions and blocked Go
  channel operations.
- `scalago.gears` (`gears/src/scalago/gears/GoGears.scala`) adds cancellable
  `GoChan.awaitReceive`/`awaitSend` and `withGoContext(ctx)(body)`, which cancels `body` when a
  Go `context.Context` is done.

## Semantics handled explicitly

- **JVM numerics:** wrapping arithmetic, masked shift counts, `>>>`, saturating float-to-int
  conversions, float `%`. Literals are folded where Go would reject constant overflow.
- **Equality.** `==` is null-safe and numerically cooperative (`rt.Equals`), and identity on
  Java enums, Scala 3 enums extending `java.lang.Enum` included. `##` matches `Statics.anyHash`.
- **Scala 3 enums.** The cases of an enum extending `java.lang.Enum` are static fields of the
  companion object's class, set by its constructor (the JVM's static initializer); reading one
  constructs the object first.
- **Specialized tuples.** Scala 3 compiles tuples of primitives to the JVM library's Scala
  2-specialized classes (`Tuple2$mcJD$sp`, `_1$mcJ$sp`), which the library's TASTy lacks. The
  plugin (`SpecializedTuples`) uses the generic classes, with boxing.
- **`Enumeration` names.** On the JVM, a value created without a name (`val Mon, Tue = Value`)
  is named after the public `val` holding it, found by reflection. The plugin
  (`EnumerationNames`) follows each such field assignment in an `Enumeration` subclass with a
  call `scalago$registerName`, which javalib's reflection-free `Enumeration` records (Scala.js
  instead rewrites the calls into `Value(name)`). It runs in `genGoIR`, so JVM bytecode is
  unchanged, and applies to libraries compiled from TASTy (`BigDecimal.RoundingMode`). As on the
  JVM, every value held by a public `val` is named, `new Val` and its subclasses included; an
  explicit name or `nextName` wins; other values print `<Invalid enum: no field for #i>`. Values
  held by trait `val`s are not named.
- **`BigDecimal`** (`rt/bigdecimal.go`) is written in Go to the JDK's specification, with a
  `long` fast path like the JDK's: money-like values need no `math/big` and one allocation per
  operation. Results, scales, string forms, `hashCode` and exception messages match the JDK,
  down to `"/ by zero"` versus `"BigInteger divide by zero"` (which depends on the JDK's internal
  representation), checked against JDK 25 on 700,000 random operations (`MathContext`
  arithmetic in every rounding mode, `sqrt`, `pow`, conversions, parsing, extreme scales).
  Where JDK versions differ it follows the newest: `sqrt` is correctly rounded in every rounding
  mode (JDK 21's is not for the directed ones); `valueOf(NaN)` says `Infinite or NaN`.
  `doubleValue`/`floatValue` round correctly, through `strconv.ParseFloat` beyond the fast
  paths. `MathContext`s reach the natives packed into one `int64` (`MathContext.goBits`).

## Status

What works is documented for users in [The JDK](_docs/libraries/jdk.md),
[Scala libraries](_docs/libraries/scala.md), [Differences from the JVM](_docs/user/differences.md)
and [Limitations](_docs/user/limitations.md). End-to-end tests in `tests/cases`, named
`<area>-<topic>`, run each program as Go, and on the JVM unless it calls Go, against the same
expected output; `limits-*` cases pin each limitation (here and in Limitations) and
`known-bugs*` cases each [known bug](roadmap.md#known-bugs), so lifting one makes its case fail.

These limitations follow from the design and are not planned to change:

- **Run-time reflection** (`getMethods`, `Method.invoke`, run-time annotations, dynamic proxies,
  method handles, class loading, bytecode generation): a linked Go binary has no class metadata
  or loader. `getClass`, class names, `isAssignableFrom` and `java.lang.reflect.Array` (but its typed
  accessors) work; the
  [roadmap](roadmap.md) adds only what the linker can decide (type tests by `Class`, reflective
  instantiation, structural calls).
- **Java serialization, JMX, Java agents, `SecurityManager`, JNI:** no Go counterpart. Native
  code is reached through facades and cgo.
- **Libraries without TASTy** (Java libraries, Scala 2 libraries): nothing to compile. Natives
  cover the few Java classes popular Scala libraries reach (ujson's float formatting); a library
  can be patched by overriding classes, as `scalago-gears` does.
- **Stack overflow and out of memory end the program:** Go aborts when a goroutine's stack
  passes 1 GB or memory runs out, so `StackOverflowError` and `OutOfMemoryError` are never
  thrown.
- **Finalizers** (`Object.finalize`) never run.
- **Data races can crash:** a reference typed as a class or trait is a two-word Go interface,
  which a racy read can tear; `final` fields have no publication guarantee
  ([Concurrency](#concurrency-and-the-memory-model)).
- **Regular expressions** are Go's RE2: no lookaround, backreferences or possessive quantifiers.
- **Strings are UTF-16**, as on the JVM, so passing them to Go transcodes (`GoString` avoids it).
- **One Scala library per Go binary:** linking is whole-program, so two separately linked Scala
  libraries would each carry their own runtime and standard library.
- **`unsafe.Pointer`** has no Scala counterpart: facades leave out Go APIs that take or return it.
- **Arrays of references do not know their element type** (`getClass` is `Object[]` for
  `toArray(new Array[String](0))`, `Arrays.copyOf`, `ClassTag.newArray`; a non-empty reference
  array holding only `()` is an `Array[Unit]` to type tests): an element class would cost a word
  per array and a check per store.
- **`BigInteger` and `BigDecimal` cannot be subclassed:** they are Go values.
- **Little-endian 64-bit targets only:** `ByteOrder.nativeOrder()` is little-endian, so Go's
  big-endian targets (s390x, ppc64, mips) are unsupported; 32-bit targets are untested.

**Missing APIs.** Reachable code that uses something missing fails to link, with a "called from"
chain naming the API. Reachability over-approximates (virtual calls resolve by name), so code a
program never runs can cause it. With `--missing-apis throw` (Mill: `goMissingApis`), such uses
are warnings, and each throws where used, if it runs: `NoClassDefFoundError`,
`NoSuchMethodError` or `NoSuchFieldError`, `LinkageError`s that `NonFatal` does not catch, as on
the JVM; code the plugin could not translate throws `UnsupportedOperationException`.
