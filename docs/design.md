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
6. **Runtime** (`runtime/rt`). Go support code, bundled into the linker jar and copied into
   every output module.
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
| `Array[prim]` / `Array[AnyRef]`  | `*rt.Array[prim]` / `*rt.Array[any]` (covariance works)         |
| class `C`                        | struct `S_C` embedding the superclass's struct                  |
| reference of static type `C`     | interface `C_C`: embeds parents' interfaces, lists called methods |
| trait `T`                        | interface `C_T`, with a marker method `I_T()`                   |
| method body                      | free function `Impl_C_m(this C_C, ...)`                         |
| virtual method                   | forwarding method on each instantiated struct                   |
| `object O`                       | lazily initialized singleton `Mod_O_S()`                        |
| `@static` fields, `<clinit>`     | package variables, plus a guarded `Clinit_C()` run on first use |

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
- **One Go package.** Everything goes in one Go package, because Scala packages may be cyclic.
  Names are mangled injectively (`Mangling.scala`).

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
- **`lazy val`** relies on JVM `VarHandle`s. The plugin rewrites that into plain field
  compare-and-set.

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

Known gaps:
- `BigDecimal` (`java.math.BigDecimal` is not implemented), regular expressions,
  `String.format` beyond the common conversions, `java.util` collections, `java.time`, and I/O
  beyond stdout/stderr.
- Reflection: `getClass` and `Class` names work; nothing else.
- Concurrency: everything assumes a single goroutine (module initialization, lazy vals,
  `synchronized`).
- `Enumeration` (it uses reflection).
- Stack traces: exceptions carry none.

When reachable code uses something missing, linking fails with a "called from" chain naming
the API.

## Roadmap

1. **Concurrency.** Use [gears](https://github.com/lampepfl/gears) as the user-facing API, backed
   by goroutines. This needs thread-safe module and static initialization and lazy vals, and
   `synchronized` built on `sync.Mutex`.
2. **Go interop.** Call Go packages from Scala, with facades similar to Scala.js's JS facades and
   `GoString` at the boundary.
3. **Library coverage.** `BigDecimal`, `java.util` collections, regex (on Go's `regexp`, with
   Java syntax differences), more of `java.lang`.
4. **Publishing.** All artifacts are wired for `PublishModule` (organization
   `io.github.scalago` is a placeholder).

## Open questions

- **Missing members.** Should unresolvable but statically reachable members become link errors
  (current behavior) or runtime errors, as on the JVM, with a warning? The JVM behavior is more
  permissive, but it hides gaps.
- **Exceptions and Go errors** at the interop boundary.
