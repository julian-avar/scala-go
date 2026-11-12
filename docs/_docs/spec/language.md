---
title: Language
---

# Language

This page specifies the run-time semantics of Scala programs, following the chapters of the
[Scala 3.4 specification](https://scala-lang.org/files/archive/spec/3.4/) and then the Scala 3
reference features. Compile-time rules (syntax, typing, implicit search, overloading, erasure)
are the Scala compiler's: the page says "as in Scala" and moves on. Go constructs cite the
[Go specification][go-spec] rule they rely on ("Go: [Conversions][go-conversions]").

## 1. Lexical syntax

[Spec chapter 1](https://scala-lang.org/files/archive/spec/3.4/01-lexical-syntax.html). As in
Scala. At run time:

- String literals are sequences of UTF-16 code units, including unpaired surrogates
  (`"\uD800"`). Each distinct literal is one `String` object per program: equal literals are `eq`.
- Character literals are UTF-16 code units (`Char` is an unsigned 16-bit integer).
- Numeric literals have the values the compiler computes. Constant conversions fold with the
  JVM's semantics: `300.0.toByte` is `44`.

In Go:

- The compiler folds constant expressions, also through type ascriptions and inline parameters:
  `(Int.MaxValue: Int) + 1` reaches the back end as `-2147483648`. Go's exact constant arithmetic
  (Go: [Constant expressions][go-constexpr]) never applies to Scala arithmetic.
- A literal is a typed Go constant (`int32(5)`, `float64(0.1)`). `NaN`, the infinities and
  `-0.0`, which Go constants cannot denote (Go: [Constants][go-constants]), are `rt.NaN()`,
  `rt.Inf(±1)` and `rt.NegZero()`. The linker folds conversions of literals, because Go rejects
  constant conversions that do not fit (Go: [Representability][go-repr]).
- String literals are package variables initialized at program start (see
  [Runtime](runtime.md#program-start-and-exit)). Their Go source is the UTF-8 text, or the
  UTF-16 code units when the literal has an unpaired surrogate, which a Go string literal cannot
  express (Go: [String literals][go-string-lits]).

## 2. Identifiers, names and scopes

[Spec chapter 2](https://scala-lang.org/files/archive/spec/3.4/02-identifiers-names-and-scopes.html).
As in Scala. Classes keep their JVM binary names at run time: `getClass.getName` is `Main$` for
an object `Main`, `Main$LocalC$1` for a local class, `Main$$anon$3` for an anonymous class.
Generated Go identifiers are mangled from these names and are invisible to the program.

## 3. Types

[Spec chapter 3](https://scala-lang.org/files/archive/spec/3.4/03-types.html). The Scala
compiler checks and erases types exactly as for the JVM; the back end sees erased types only.

- Value types map to fixed Go types (see [Data representation](data.md)).
- Type parameters, union and intersection types, match types, type lambdas, opaque types and
  refinements are erased as on the JVM. Run-time type tests see only the erased class (see
  [Pattern matching](#8-pattern-matching)).
- Structural types over a user-defined `Selectable` (`selectDynamic`/`applyDynamic`) work.
  **Deviation:** structural types through `scala.reflect.Selectable.reflectiveSelectable` need
  `Method.invoke` and fail to link ("method java.lang.reflect.Method.invoke ... is not
  available").

## 4. Basic definitions

[Spec chapter 4](https://scala-lang.org/files/archive/spec/3.4/04-basic-definitions.html).

- `val`, `var` and `def` are the fields, getters, setters and methods the compiler generates.
- `lazy val` is initialized at most once, thread-safely, with Scala 3's scheme: a volatile
  state field updated by compare-and-set; threads finding the value under computation wait. If
  the initializer throws, the exception propagates and the next access runs it again.
- `@volatile var` fields are sequentially consistent (see
  [Runtime: memory model](runtime.md#memory-model)).
- Type definitions have no run-time existence.

## 5. Classes and objects

[Spec chapter 5](https://scala-lang.org/files/archive/spec/3.4/05-classes-and-objects.html).

**Construction.** Constructors run as Scala compiles them: superclass constructor, then trait
initializers in linearization order, then the class body statement by statement. A field read
before its initializer runs has its default value (`0`, `false`, `null`). A trait reading an
abstract `val` that a subclass defines sees `0`; a superclass calling an overridden `def` that
returns a subclass `val` sees `null`. Verified by `tests/cases/initialization-order`.

**Objects (modules).** An `object` is constructed on first access, not at program start.

- Construction runs once, under a per-object initialization lock. A thread accessing the object
  while another thread constructs it waits.
- A thread accessing an object during its own construction of it (directly or through a cycle)
  gets the partially constructed object, as on the JVM. With `object A { val x = B.y + 1 }` and
  `object B { val y = A.x + 1 }`, accessing `A.x` first gives `A.x == 2` and `B.y == 1`.
- If the constructor throws, the exception propagates to the triggering access. Every later
  access throws `NoClassDefFoundError("Could not initialize class A$")`. **Deviation:** the JVM
  wraps the first failure in `ExceptionInInitializerError`, which becomes the cause of the later
  errors; scala-go throws the exception itself, with no cause on `NoClassDefFoundError` (javalib
  has no `ExceptionInInitializerError`).
- Static members (`@static`, Java statics in javalib, cases of Java-style Scala 3 enums) are set
  by the class's static initializer. It runs once, under its own lock, before the first
  instantiation, static field access or static method call, as on the JVM. Mechanism:
  [Runtime](runtime.md#class-and-object-initialization).

**Dispatch.** Virtual calls resolve as on the JVM: the class's own or inherited method, then the
most specific trait default method. Calls through `this` in inherited code stay virtual.
`super[T].m` and private methods are called statically.

**Case classes.** The compiler generates `equals`, `hashCode`, `toString`, `copy`,
`productElement`, `unapply` and the companion's `apply`, so they behave as on the JVM, including
`hashCode` values (`Point(1, 2).hashCode` is the same number).

**Class names.** `getClass.getName` returns JVM binary names. `getSimpleName` follows the JVM
for top-level, nested and anonymous (`""`) classes and arrays. **Deviation (bug):**
`getSimpleName` of a local class (`Main$LocalC$1`) is `""` instead of `LocalC`.

## 6. Expressions

[Spec chapter 6](https://scala-lang.org/files/archive/spec/3.4/06-expressions.html).

### Evaluation order

Operands, arguments and receivers are evaluated left to right, each once, as on the JVM:
`f(a(), b(), c())` evaluates `a`, `b`, `c`, then calls `f`; `a() + b() * c()` evaluates `a`
first. A string concatenation chain evaluates all operands before converting any to a string
(as `StringConcatFactory`). `&&` and `||` short-circuit; `&` and `|` on `Boolean` do not.

Go orders the calls, receive operations and logical operations of an expression left to right,
but leaves unspecified when variables, fields and index expressions are read relative to them
(Go: [Order of evaluation][go-order]). The generated code relies on:

- Calls (getters such as `c.n`, natives, `rt` helpers) are Go calls, so they keep their order.
- When an operand needs Go statements of its own (a conditional, a block, a `try`), the operands
  before it are first stored in Go variables, except literals, `this` and never-reassigned
  locals (`FunctionEmitter.genInOrder`).
- `&&` and `||` are Go's; `&` and `|` on `Boolean` are calls (`rt.And`, `rt.Or`).

**Deviation (bug):** an operand that reads a field directly, followed by a call that changes the
field, can see the new value. Below, the generated `(i.elem + inc(i))` reads `elem` after the
call (gc's order; Go allows both). Fields read directly are those of the `*Ref` cells of
captured `var`s, and private fields; fields read through a getter are not affected.

```scala sc:nocompile
var i = 0                    // local, captured: a scala.runtime.IntRef
def inc() = { i += 1; i }
i + inc()                    // JVM: 1; scala-go: 2
```

### Numeric operations

| Operation | Semantics | In Go |
|-----------|-----------|-------|
| `Int`, `Long` `+ - *`, unary `-` | two's complement, wrapping: `Int.MaxValue + 1 == Int.MinValue` | Go's operators on `int32`/`int64`, which wrap (Go: [Integer overflow][go-overflow]) |
| integer `/`, `%` | truncating; `Int.MinValue / -1 == Int.MinValue`, `Int.MinValue % -1 == 0` | Go's operators: same truncation, and `x / -1 == x` for the most negative `x` (Go: [Integer operators][go-intops]) |
| integer `/`, `%` by zero | `ArithmeticException("/ by zero")`, after evaluating the dividend | a Go run-time panic (Go: [Integer operators][go-intops]), translated (see [Exceptions](#exceptions)). A literal `0` divisor, which Go rejects, is `rt.DivByZero(dividend)` |
| `<<`, `>>`, `>>>` | shift count masked to 5 bits (`Int`) or 6 bits (`Long`): `1 << 33 == 2` | Go does not mask (`x << 33` is `0` for `int32`) and panics on a negative count (Go: [Arithmetic operators][go-arith]), so the count is masked: `x << (uint64(n) & 31)` |
| `>>>` | logical shift | `int32(uint32(x) >> n)` |
| `Byte`, `Short`, `Char` arithmetic | operands promoted to `Int` (`'a' + 1 == 98`); narrowing conversions wrap | Go integer conversions sign- or zero-extend, then truncate (Go: [Conversions][go-conversions]), as the JVM's `i2b`, `i2s`, `i2c` |
| `Float`, `Double` arithmetic | IEEE 754 binary32/binary64, round to nearest even | Go's `float32`/`float64` operators; see the fused multiply-add deviation below |
| floating `/` by zero | `Infinity`, `-Infinity` or `NaN` | Go requires only IEEE 754 results and lets an implementation panic (Go: [Floating-point operators][go-floatops]); gc does not panic |
| floating `%` | C `fmod`: sign of the dividend (`-5.5 % 2 == -1.5`); `x % 0.0` is `NaN` | Go has no `%` on floats: `math.Mod`, which is `fmod` |
| floating → `Int`/`Long` | truncation; `NaN` → `0`; out of range saturates (`1e20.toInt == Int.MaxValue`) | Go truncates, but out-of-range results are implementation-dependent (Go: [Conversions][go-conversions]): `rt.D2I`/`rt.D2L` test `NaN` and the range first |
| floating → `Byte`/`Short`/`Char` | via `Int`, then narrowing: `300.0.toByte == 44`, `1e20.toChar == '￿'` | `int8(rt.D2I(x))`, ... |
| integer → floating, `Double` → `Float` | rounded to nearest | Go's conversions, which round to the target precision |
| `==`, `<`, ... on floating | IEEE: `NaN != NaN`, `-0.0 == 0.0` | Go's comparison operators, which are IEEE 754's (Go: [Comparison operators][go-compare]) |
| `Double.equals`, `compare` | by bits: `NaN` equals `NaN`, `-0.0` does not equal `0.0`; `-0.0 < 0.0 < NaN` | natives on the bits (`math.Float64bits`) |

The `numerics-runtime` test case checks the run-time cases (operands the compiler cannot fold).

**Deviation (bug):** `a * b + c` on `Float`/`Double` may use a fused multiply-add (one rounding
instead of two) on arm64, and on amd64 with `GOAMD64=v3` or higher; results can differ from the
JVM's in the last bit. Go permits fusion "possibly across statements" unless an explicit
floating-point conversion rounds the intermediate (Go: [Floating-point operators][go-floatops]).
The generated code has no such conversion, and a variable for the product would not prevent
fusion. Verified: gc emits `FMADDD` on arm64 and `VFMADD231SD` on amd64 v3; default amd64 builds
do not fuse.

`java.lang.Math` and `StrictMath`:

- `abs`, `min`, `max`, `floorDiv`, `floorMod`, `signum`, `rint`, `sqrt`, `clamp` and `*Exact`
  (throwing `ArithmeticException("integer overflow")`/`("long overflow")`) have the JDK's results.
- The transcendental functions (`sin`, `exp`, `log`, `pow`, ...) are Go's `math` package.
  **Deviation:** results may differ from the JDK's in the last bit. `StrictMath` is `Math`, not
  bit-exact fdlibm.
- **Deviation (bug):** `Math.round(Double)` computes `floor(x + 0.5)` in floating point, which
  rounds twice.

  ```scala sc:nocompile
  Math.round(0.49999999999999994)   // JDK: 0; scala-go: 1
  Math.round(4503599627370497.0)    // JDK: 4503599627370497; scala-go: 4503599627370498
  ```

### String conversion

Concatenation, interpolation, `String.valueOf` and `println` convert values as the JVM does:

| Value | String |
|-------|--------|
| `null` | `null` |
| `()` | `()` |
| `Boolean`, integers | decimal (`-128`, `true`) |
| `Char` | the character itself |
| `Double`, `Float` | `Double.toString`/`Float.toString`: the shortest decimal that round-trips (JDK 19+ algorithm): `1.0E7`, `0.001`, `1.0E-4`, `4.9E-324`, `-0.0`, `NaN`, `Infinity` |
| `String` | itself |
| case class, tuple, `Option`, collections | their `toString` (Scala library, unchanged): `Point(1,2)`, `(1,a)`, `List(1, 2)` |
| Scala 3 `enum` case | its name |
| object without `toString` | `ClassName@` + `hashCode` in hexadecimal |
| primitive array | `[I@` + identity hash |
| array of references | **Deviation:** `[Ljava.lang.Object;@...` for every element type (JVM: `[Ljava.lang.String;@...`); see [Arrays](data.md#arrays) |
| lambda | **Deviation:** the function trait's `toString`, `<function1>` (JVM: `Main$$$Lambda/0x...@hash`) |

### Equality and hashing

| Operation | Semantics |
|-----------|-----------|
| `x == y` (references) | `null`-safe. Boxed numbers and characters compare by numeric value (`1 == 1L`, `'a' == 97`, `1 == 1.0`); otherwise `x.equals(y)` |
| `x equals y` | the class's `equals`; boxes compare type and value (`1 equals 1L` is `false`) |
| `x eq y` | reference identity, for objects, strings and arrays |
| `x.##` | `hashCode`, except that numbers hash consistently with `==` (`1.## == 1L.## == 1.0.##`), as `Statics.anyHash`; `null.##` is `0` |
| `hashCode` | Java's for strings, boxes, `BigInteger`, `BigDecimal`; compiler-generated for case classes |
| arrays | `==` is identity; compare contents with `sameElements` or `java.util.Arrays.equals` |
| Java enums (incl. Scala 3 enums extending `java.lang.Enum`) | `==` is identity |

In Go:

- `eq` and `ne` compare interface values, `any(a) == any(b)`: equal if both are nil, or if their
  dynamic types are identical and their dynamic values equal (Go:
  [Comparison operators][go-compare]). Objects, strings and arrays are pointers, so this is
  identity. Boxes are the primitive values themselves, so equal boxes are `eq` (see below).
- `==` on references is `rt.Equals`; on primitives, Go's `==` after numeric promotion.
- Null tests (`x == null`, `x eq null`) compare with `nil`, through `rt.IsNull` when the value is
  an `any`, which may hold a nil pointer (see [Data representation](data.md#classes-and-objects)).

Deviations:

- **Deviation (bug):** `==` and `!=` throw `NullPointerException` when one operand of static
  type `String` is null and the other is not. With `s: String = null`, `"a" == s`, `t == s`,
  `s == t` and `(t: Any) == s` throw (JVM: `false`), and so does
  `s match { case "a" => ...; case _ => ... }`. `s == null`, `s eq null` and `t.equals(s)` work.
  The operand reaches `rt.Equals` as an `any` holding a nil `*rt.String`, which is not a nil
  interface (Go: [Comparison operators][go-compare]), and is dereferenced. Verified on `String`;
  operands typed `BigInteger` or `BigDecimal` take the same path.
- **Deviation (bug):** `eq` on two references to the same Go value of a type Go cannot compare
  (a `GoSlice`, `GoMap`, Go func, or a Go struct value containing one) throws
  `RuntimeException("runtime error: comparing uncomparable type []int")` (JVM: `true`).
  Comparing interface values with identical, non-comparable dynamic types is a Go run-time
  panic (Go: [Comparison operators][go-compare]). `==` works: identity for slices, maps and
  funcs.
- **Deviation (bug):** a boxed `Int` or `Long` compared with a boxed `Float` is compared as
  `Double`; the JVM compares as `Float`. `(16777217L: Any) == (16777216f: Any)` is `false`
  (JVM: `true`). Unboxed comparisons are not affected.
- **Deviation (bug):** a boxed primitive on the left of `==` with a `BigInt` or `BigDecimal` on
  the right is `false`: `(1: Any) == BigInt(1)` (JVM: `true`, through `ScalaNumber`).
  `BigInt(1) == 1` is `true` on both.
- **Deviation:** `==` on two references to the *same* boxed `NaN` is `false`. The JVM's
  `BoxesRunTime.equals` tests identity first and gives `true`.
- **Deviation:** boxed primitives have no identity. `eq` compares them by type and value:
  `Integer.valueOf(1000) eq Integer.valueOf(1000)` is `true` (JVM: `false` outside the `Integer`
  cache). `System.identityHashCode` of a box is `0`, and equal boxes share one monitor.

### Type tests and casts

`isInstanceOf`, `asInstanceOf`, type patterns and `catch` clauses test the erased class. In Go
they are type assertions (Go: [Type assertions][go-assert]). A test is the comma-ok form
`_, ok := x.(T)`, which never panics. A cast is `x.(T)` after a nil test (`rt.Cast`, so `null`
passes); a failed assertion's panic (`*runtime.TypeAssertionError`) becomes `ClassCastException`.
`T` is the class's Go interface, which only its subclasses implement (see
[Data representation](data.md#classes-and-objects)), or a concrete Go type for strings, boxes
and arrays.

- A boxed value is an instance of its own box class only (`(1L: Any).isInstanceOf[Int]` is
  `false`), and of `Number`, `Comparable`, `Serializable` as on the JVM.
- Type arguments are not checked: `List(1).isInstanceOf[List[String]]` is `true`, with the
  compiler's unchecked warning.
- `null` is an instance of nothing. `null.asInstanceOf[T]` is `null`, or the zero value for a
  value type (`null.asInstanceOf[Int] == 0`).
- A failed cast throws `ClassCastException`. **Deviation:** the message is Go's
  (`interface conversion: *scala.S_Sq is not scala.C_Point: missing method A_Point`), not the
  JVM's (`class Sq cannot be cast to class Point (...)`). Casts from `Any` to `Comparable`,
  `CharSequence`, `Number` and the like have a JVM-style message (`class java.lang.Integer
  cannot be cast to class java.lang.CharSequence`) without the JVM's module details.
- **Deviation:** arrays of references do not know their element type: every `Array[R]` is an
  instance of every other `Array[S]`, casts between them succeed, and no `ArrayStoreException`
  is thrown. Primitive arrays are exact. Full rule: [Arrays](data.md#arrays).
- **Deviation (bug):** a cast to a boxed primitive class is not checked when the operand's
  static type is `String` or another class held as a Go pointer (`Class`, `BigInteger`,
  `BigDecimal`, arrays). `("a": Any).asInstanceOf[Integer]` returns the string when the compiler
  keeps the operand typed `String`. Operands typed `Any` are checked.

### Exceptions

- `throw e` throws `e`; `throw null` throws `NullPointerException`.
- `try`/`catch`/`finally` behave as on the JVM. `catch` cases are tested in order. `finally`
  runs after normal completion, after an exception, and after `return`, `break` or `continue`
  out of the `try`. An exception or `return` in `finally` replaces the outcome of the `try`.

In Go, `throw e` is `panic(rt.Exc(e))`; the panic value (`*rt.Exception`) wraps the `Throwable`.
A `try` block is a Go closure run by `rt.Try`, whose deferred function calls `recover` directly,
as Go requires for `recover` to stop the panic (Go: [Handling panics][go-panics]), and returns
the `Throwable` or `nil`. `catch` and `finally` then run on the method's own stack; an uncaught
exception is re-thrown with `panic`. `return`, `break` and `continue` out of the closure set a
jump code that the enclosing code performs.

Errors the Go runtime detects are run-time panics with `runtime.Error` values, whose exact
values Go leaves unspecified (Go: [Run-time panics][go-rtpanics]). `rt.Caught` recognizes them
by type and by gc's messages and makes the JVM's exceptions:

| Go runtime error | Exception |
|------------------|-----------|
| `nil` dereference (method call, field access on `null`) | `NullPointerException` |
| integer division by zero | `ArithmeticException("/ by zero")` |
| index out of range | `ArrayIndexOutOfBoundsException("Index 5 out of bounds for length 3")` |
| failed type assertion | `ClassCastException` |
| negative slice length | `NegativeArraySizeException` |
| any other run-time panic, or a panic in Go code with a non-exception value | `RuntimeException` with Go's message |

- **Deviation (bug):** a negative array index throws `RuntimeException("runtime error: index
  out of range [-1]")` (JVM: `ArrayIndexOutOfBoundsException("Index -1 out of bounds for length
  3")`). gc's message for a negative index has no `with length` part, which the translation
  expects. Indices past the end are translated.
- **Deviation:** `NullPointerException`s have no message (the JVM's name the `null` expression).
- **Deviation:** `String` index errors have older JDKs' messages: `"abc".charAt(5)` gives
  `index 5, length 3` (JDK 25: `Index 5 out of bounds for length 3`), `substring(2, 1)` gives
  `begin 2, end 1, length 3` (JDK 25: `Range [2, 1) out of bounds for length 3`).
  `Integer.parseInt(s, radix)` omits the JDK's `under radix 16` suffix.
- Go fatal errors are not exceptions. Stack exhaustion, heap exhaustion and Go's own fatal
  conditions end the process with exit status 2 and a Go report, without running `catch` or
  `finally` (see [Runtime: stack](runtime.md#stack)).
- `scala.util.control.NonFatal` is the library's. **Deviation (bug):** javalib's
  `StackOverflowError` and `OutOfMemoryError` extend `Error` directly (there is no
  `VirtualMachineError`), so `NonFatal` matches them. The runtime itself never throws them.
- A `Throwable` captures its stack trace when constructed (see
  [Runtime: stack traces](runtime.md#stack-traces)).

### Functions and closures

- A lambda is an instance of a class the linker synthesizes (`Owner$$Lambda$n`), like the JVM's
  `LambdaMetafactory` classes. Captured `var`s are shared through `scala.runtime.*Ref` objects,
  as Scala compiles them.
- SAM conversion works for every single-abstract-method type, Scala or Java (`Runnable`,
  `Comparator`, `java.util.function.*`), with the bridges `LambdaMetafactory` would add.
- `return` inside a lambda (non-local return) throws and catches
  `scala.runtime.NonLocalReturnControl`, as on the JVM. `scala.util.boundary`/`break` throw a
  `Break` without a stack trace.

### Other expressions

- `synchronized` takes the receiver's monitor: reentrant, released however the block
  completes. Any value can be locked. Objects carry their monitor; strings and other values use
  a global table, keyed by reference for strings and by value for boxes.
- `while` and `if` are Go loops and conditionals. Self-recursive tail calls are loops (the
  compiler rewrites them, with or without `@tailrec`).
- Matches the Scala compiler turns into switches (integer and character literal cases) are Go
  expression `switch` statements, whose cases compare with `==` and do not fall through (Go:
  [Expression switches][go-switch]). Other matches, including most on string literals, are the
  compiler's `==` tests and jumps (Go `for` loops with labeled `break`s).

## 7. Implicits

[Spec chapter 7](https://scala-lang.org/files/archive/spec/3.4/07-implicits.html). As in Scala:
implicit search, `given`/`using`, conversions and extension methods are resolved at compile
time. At run time, givens and implicit values are ordinary `val`s, `lazy val`s, `def`s and
objects. `ClassTag`s work (`runtimeClass`, `newArray`; see [Arrays](data.md#arrays)).

## 8. Pattern matching

[Spec chapter 8](https://scala-lang.org/files/archive/spec/3.4/08-pattern-matching.html).
Scala compiles patterns into type tests, `unapply` calls, equality tests and jumps; scala-go
runs that code. The evaluation order of extractors and guards, and `MatchError`, are the JVM's.
Type patterns are type tests (see [Type tests and casts](#type-tests-and-casts)), including the
array deviation. Literal patterns compare with `==`. `@switch` matches are Go `switch`es.

## 9. Top-level definitions

[Spec chapter 9](https://scala-lang.org/files/archive/spec/3.4/09-top-level-definitions.html).

- Packages exist at run time only in class names. All generated code is in one Go package, so
  cyclic dependencies between Scala packages are allowed.
- Top-level definitions belong to synthetic `$package` objects, as on the JVM.
- The entry point is the `main(Array[String])` method of the object named at link time (`Main`
  by default), or a class generated for a `@main` method. See
  [Runtime: program start and exit](runtime.md#program-start-and-exit).

## 10. XML

[Spec chapter 10](https://scala-lang.org/files/archive/spec/3.4/10-xml-expressions-and-patterns.html).
Scala 3 has no XML literals; neither does scala-go.

## 11. Annotations

[Spec chapter 11](https://scala-lang.org/files/archive/spec/3.4/11-annotations.html).
Annotations with a run-time effect:

| Annotation | Effect |
|------------|--------|
| `@volatile` | the field is a `sync/atomic` value: sequentially consistent loads, stores and compare-and-set |
| `@transient` | none (there is no Java serialization) |
| `@tailrec` | compile-time check; the compiler turns the recursion into a loop |
| `@switch` | compile-time check; the match is a Go `switch` |
| `@static` | a Go package variable or function, initialized by the static initializer |
| `@throws` | none, except on `@GoExport` methods: the Go function gets a trailing `error` result |
| `@native` | the method has no body. **Deviation:** calling it throws `RuntimeException("AbstractMethodError: ...")` (JVM: `UnsatisfiedLinkError`) |
| `@inline`, `@noinline` | none in the back end; Go inlines by its own heuristics |
| `@SerialVersionUID`, `@BeanProperty`, `@varargs` | as Scala compiles them; serialization has no effect |
| `@GoImport(path, name)` | the object is a Go package, or the class or trait a Go type (see [Data representation](data.md#go-interop-types)) |
| `@GoName(name)` | the Go name of a facade member |
| `@GoExport(name)` | exports a method or class to Go code (library builds) |

All other annotations (`@deprecated`, `@unchecked`, `@nowarn`, `@implicitNotFound`, ...) only
affect compilation.

## 12. The standard library

[Spec chapter 12](https://scala-lang.org/files/archive/spec/3.4/12-the-scala-standard-library.html).
The Scala library is the published scala-library 3.9.0, recompiled from its TASTy by the plugin.
`Option`, collections, `Predef`, string interpolators, `scala.math`, `scala.util`,
`scala.concurrent` and `scala.sys.process` are the same code as on the JVM. Exceptions:

- javalib replaces `scala.runtime.*Ref`, `LazyVals`, `JProcedureN`, `ScalaNumber`,
  `Enumeration` (names values without reflection, as on the JVM) and `DynamicVariable` (a faster
  `Console.withOut` when nothing is redirected).
- `BoxesRunTime` and `Statics` (equality and hashing of boxes) are native.
- Specialized tuple classes are replaced by the generic ones (see [Data representation](data.md#case-classes-tuples-options-and-collections)).
- `scala.quoted` is not available at run time; macros run in the compiler.
- **Deviation:** `scala.util.Properties` values read from `library.properties` (such as
  `versionNumberString`) are empty: a Go binary has no class-path resources.

Library code that needs a JDK API javalib lacks fails to link if reached.

## Scala 3 reference features

| Feature | Run-time behavior |
|---------|-------------------|
| Enums | As Scala compiles them: `values`, `valueOf`, `ordinal`, `toString` (the case name); singleton cases are instances of anonymous classes (`Color$$anon$1`). Enums extending `java.lang.Enum` compare by identity. `EnumSet`/`EnumMap` are not implemented |
| Opaque types | Erased to the underlying type: no wrapper, no cost |
| Extension methods | Ordinary methods |
| `given`/`using`, context functions | Ordinary values, methods and lambdas |
| `inline`, macros | Expanded by the compiler. Stack trace lines of inlined code are those of the inlined source |
| Union and intersection types | Erased; type tests test the erased classes |
| Match types, type lambdas, kind polymorphism | Compile-time only |
| `Selectable` | User-defined `Selectable`s work; `reflectiveSelectable` does not link (see [Types](#3-types)) |
| `Dynamic` | Works: calls are rewritten to `selectDynamic`/`applyDynamic` at compile time |
| `@main` | Works; the generated class parses the arguments (`scala.util.CommandLineParser`) |
| Export clauses | Forwarder methods generated by the compiler |
| Tuples (`*:`, `TupleXXL`) | Library classes; elements of `Tuple1`–`Tuple22` are boxed (no specialization) |
| `boundary`/`break` | Exceptions without stack traces, as on the JVM |
| Explicit nulls, capture checking | Compile-time only |

[go-spec]: https://go.dev/ref/spec
[go-arith]: https://go.dev/ref/spec#Arithmetic_operators
[go-assert]: https://go.dev/ref/spec#Type_assertions
[go-compare]: https://go.dev/ref/spec#Comparison_operators
[go-constants]: https://go.dev/ref/spec#Constants
[go-constexpr]: https://go.dev/ref/spec#Constant_expressions
[go-conversions]: https://go.dev/ref/spec#Conversions
[go-floatops]: https://go.dev/ref/spec#Floating_point_operators
[go-intops]: https://go.dev/ref/spec#Integer_operators
[go-order]: https://go.dev/ref/spec#Order_of_evaluation
[go-overflow]: https://go.dev/ref/spec#Integer_overflow
[go-panics]: https://go.dev/ref/spec#Handling_panics
[go-repr]: https://go.dev/ref/spec#Representability
[go-rtpanics]: https://go.dev/ref/spec#Run_time_panics
[go-string-lits]: https://go.dev/ref/spec#String_literals
[go-switch]: https://go.dev/ref/spec#Expression_switches
