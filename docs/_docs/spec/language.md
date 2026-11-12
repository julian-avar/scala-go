---
title: Language
---

# Language

Run-time semantics of Scala programs, by chapter of the
[Scala 3.4 specification](https://scala-lang.org/files/archive/spec/3.4/), then the Scala 3
reference features. Compile-time rules (syntax, typing, implicit search, overloading, erasure)
are the Scala compiler's ("as in Scala"). Conventions: [Specification](index.md#conventions).

## 1. Lexical syntax

[Spec chapter 1](https://scala-lang.org/files/archive/spec/3.4/01-lexical-syntax.html). As in
Scala. At run time:

- String literals are sequences of UTF-16 code units, including unpaired surrogates
  (`"\uD800"`). Each distinct literal is one `String` object per program, created at program
  start ([Runtime](runtime.md#program-start-and-exit)): equal literals are `eq`.
- Character literals are UTF-16 code units.
- The compiler folds constant expressions with the JVM's semantics, also through type
  ascriptions and inline parameters: `300.0.toByte` is `44`, `(Int.MaxValue: Int) + 1` is
  `-2147483648`. Go's exact constant arithmetic (Go: [Constant expressions][go-constexpr]) never
  applies to Scala arithmetic.
- In Go, a literal is a typed constant (`int32(5)`), except values Go constants cannot denote
  (`NaN`, the infinities, `-0.0`; Go: [Constants][go-constants]), which are computed, and
  conversions that do not fit (Go: [Representability][go-repr]), which the linker folds. A
  string literal with an unpaired surrogate (Go: [String literals][go-string-lits]) is emitted as
  UTF-16 code units.

## 2. Identifiers, names and scopes

[Spec chapter 2](https://scala-lang.org/files/archive/spec/3.4/02-identifiers-names-and-scopes.html).
As in Scala. Classes keep their JVM binary names at run time: `getClass.getName` is `Main$` for
an object `Main`, `Main$LocalC$1` for a local class, `Main$$anon$3` for an anonymous class.
Generated Go identifiers are mangled from these names and invisible to the program.

## 3. Types

[Spec chapter 3](https://scala-lang.org/files/archive/spec/3.4/03-types.html). The Scala
compiler checks and erases types as for the JVM; the back end sees erased types only. Value
types map to fixed Go types ([Data representation](data.md)). Type parameters, union and
intersection types, match types, type lambdas, opaque types and refinements are erased; run-time
type tests see only the erased class ([Type tests and casts](#type-tests-and-casts)).

Structural types over a user-defined `Selectable` (`selectDynamic`/`applyDynamic`) work.
**Deviation:** structural types through `scala.reflect.Selectable.reflectiveSelectable` need
`Method.invoke` and fail to link.

## 4. Basic definitions

[Spec chapter 4](https://scala-lang.org/files/archive/spec/3.4/04-basic-definitions.html).
`val`, `var` and `def` are the fields, getters, setters and methods the compiler generates; type
definitions have no run-time existence.

- `lazy val` is initialized at most once, thread-safely, with Scala 3's scheme; threads finding
  the value under computation wait. If the initializer throws, the exception propagates and the
  next access runs it again.
- `@volatile var` fields are sequentially consistent ([Runtime: memory
  model](runtime.md#memory-model)).

## 5. Classes and objects

[Spec chapter 5](https://scala-lang.org/files/archive/spec/3.4/05-classes-and-objects.html).

**Construction.** Superclass constructor, then trait initializers in linearization order, then
the class body statement by statement. A field read before its initializer runs has its default
value (`0`, `false`, `null`): a trait reading an abstract `val` that a subclass defines sees `0`;
a superclass calling an overridden `def` that returns a subclass `val` sees `null`
(`tests/cases/lang-initialization-order`).

**Objects (modules).** An `object` is constructed on first access, not at program start.

- Construction runs once, under a per-object lock; other threads accessing the object meanwhile
  wait.
- A thread accessing an object during its own construction of it (directly or through a cycle)
  gets the partially constructed object. With `object A { val x = B.y + 1 }` and
  `object B { val y = A.x + 1 }`, accessing `A.x` first gives `A.x == 2` and `B.y == 1`.
- If the constructor throws, the triggering access throws `ExceptionInInitializerError` caused by
  the exception (an `Error` propagates as it is), and every later access throws
  `NoClassDefFoundError("Could not initialize class A$")`, caused by an
  `ExceptionInInitializerError` that names the first exception.
- Static members (`@static`, Java statics in javalib, cases of Java-style Scala 3 enums) are set
  by the class's static initializer, run once, under its own lock, before the first
  instantiation, static field access or static method call
  ([Runtime](runtime.md#class-and-object-initialization)).

**Dispatch.** A virtual call resolves to the class's own or inherited method, then to the most
specific trait default method. Calls through `this` in inherited code stay virtual. `super[T].m`
and private methods are called statically.

**Case classes.** Their members are compiler-generated; even `hashCode` values equal the JVM's.

**Class names.** `getSimpleName` and `getCanonicalName` follow the JVM for top-level, nested,
local and anonymous (`""`, canonical name `null`) classes and arrays; the IR records the JVM's
`InnerClasses` entry of each nested class.

## 6. Expressions

[Spec chapter 6](https://scala-lang.org/files/archive/spec/3.4/06-expressions.html).

### Evaluation order

Operands, arguments and receivers are evaluated left to right, each once: `f(a(), b(), c())`
evaluates `a`, `b`, `c`, then calls `f`; `a() + b() * c()` evaluates `a` first. A string
concatenation chain evaluates all operands before converting any to a string (as
`StringConcatFactory`). `&&` and `||` short-circuit; `&` and `|` on `Boolean` do not.

Go orders calls left to right but leaves unspecified when variables and fields are read
relative to them (Go: [Order of evaluation][go-order]); operands before one that needs Go
statements (a conditional, a block, a `try`) are first stored in variables.
**Deviation (bug):** an operand that reads a field directly (a captured `var`'s `*Ref` cell, a
private field), followed by a call that changes it, can see the new value: with a captured
`var i` and `def inc() = { i += 1; i }`, `i + inc()` is `2` (JVM: `1`).

### Numeric operations

| Operation | Semantics |
|-----------|-----------|
| `Int`, `Long` `+ - *`, unary `-` | two's complement, wrapping: `Int.MaxValue + 1 == Int.MinValue` |
| integer `/`, `%` | truncating; `Int.MinValue / -1 == Int.MinValue`, `Int.MinValue % -1 == 0` |
| integer `/`, `%` by zero | `ArithmeticException("/ by zero")`, after evaluating the dividend |
| `<<`, `>>`, `>>>` | count masked to 5 bits (`Int`) or 6 bits (`Long`): `1 << 33 == 2`; `>>>` is a logical shift |
| `Byte`, `Short`, `Char` arithmetic | promoted to `Int` (`'a' + 1 == 98`); narrowing conversions wrap |
| `Float`, `Double` arithmetic | IEEE 754 binary32/binary64, round to nearest even (but see fused multiply-add below) |
| floating `/` by zero | `Infinity`, `-Infinity` or `NaN` |
| floating `%` | C `fmod`: sign of the dividend (`-5.5 % 2 == -1.5`); `x % 0.0` is `NaN` |
| floating → `Int`/`Long` | truncation; `NaN` → `0`; out of range saturates (`1e20.toInt == Int.MaxValue`) |
| floating → `Byte`/`Short`/`Char` | via `Int`, then narrowing: `300.0.toByte == 44`, `1e20.toChar == '￿'` |
| integer → floating, `Double` → `Float` | rounded to nearest |
| `==`, `<`, ... on floating | IEEE: `NaN != NaN`, `-0.0 == 0.0` |
| `Double.equals`, `compare` | by bits: `NaN` equals `NaN`, `-0.0` does not equal `0.0`; `-0.0 < 0.0 < NaN` |

In Go, integer operators wrap and truncate this way (Go: [Integer overflow][go-overflow],
[Integer operators][go-intops]); shift counts are masked explicitly, as Go does not mask and
panics on a negative count (Go: [Arithmetic operators][go-arith]); floating-to-integer
conversions are range-checked first (Go: [Conversions][go-conversions]). What scala-go relies on
where Go leaves results to the implementation: [Runtime](runtime.md#go-implementation-dependence).
`tests/cases/expr-arithmetic-runtime` checks operands the compiler cannot fold.

**Deviation (bug):** `a * b + c` on `Float`/`Double` may be fused into one multiply-add (one
rounding) on arm64 and on amd64 with `GOAMD64=v3` or higher, as Go permits without an explicit
conversion of the product (Go: [Floating-point operators][go-floatops]); results can differ from
the JVM's in the last bit.

`java.lang.Math` and `StrictMath`:

- `abs`, `min`, `max`, `floorDiv`, `floorMod`, `signum`, `rint`, `sqrt`, `clamp` (`int` and
  `long` overloads only; the floating-point ones are missing) and `*Exact` (throwing
  `ArithmeticException("integer overflow")`/`("long overflow")`) have the JDK's results.
- **Deviation:** the transcendental functions (`sin`, `exp`, `log`, `pow`, ...) are Go's `math`
  and may differ from the JDK's in the last bit; `StrictMath` is `Math`, not fdlibm.
- **Deviation (bug):** `pow` misses Java's special cases (`pow(1, NaN)` is `1.0`, not `NaN`) and
  can be off by more than one bit (`pow(10, 308)`).
- **Deviation (bug):** `Math.round(Double)` rounds twice (`floor(x + 0.5)`):
  `Math.round(0.49999999999999994)` is `1` (JDK: `0`); large odd values are off by one.

### String conversion

Concatenation, interpolation, `String.valueOf` and `println` convert values as the JVM does:
`null` and `()` as `null` and `()`, a `Char` as the character, a `Double` or `Float` as the
shortest decimal that round-trips (JDK 19+: `1.0E7`, `0.001`, `1.0E-4`, `4.9E-324`, `-0.0`,
`NaN`), library types by their `toString` (`Point(1,2)`, `(1,a)`, `List(1, 2)`), a Scala 3 `enum`
case by its name, an object without `toString` as `ClassName@` + `hashCode` in hexadecimal, a
primitive array as `[I@` + identity hash. **Deviation:** an array of references is
`[Ljava.lang.Object;@...` for every element type (JVM: `[Ljava.lang.String;@...`;
[Arrays](data.md#arrays)); a lambda is the function trait's `<function1>` (JVM:
`Main$$$Lambda/0x...@hash`).

### Equality and hashing

| Operation | Semantics |
|-----------|-----------|
| `x == y` (references) | `null`-safe. Boxed numbers and characters compare by numeric value (`1 == 1L`, `'a' == 97`, `1 == 1.0`); otherwise `x.equals(y)` |
| `x equals y` | the class's `equals`; boxes compare type and value (`1 equals 1L` is `false`) |
| `x eq y` | reference identity, for objects, strings and arrays |
| `x.##` | `hashCode`, except that numbers hash consistently with `==` (`1.## == 1L.## == 1.0.##`); `null.##` is `0` |
| `hashCode` | Java's for strings, boxes, `BigInteger`, `BigDecimal`; compiler-generated for case classes |
| arrays | `==` is identity; compare contents with `sameElements` or `java.util.Arrays.equals` |
| Java enums (incl. Scala 3 enums extending `java.lang.Enum`) | `==` is identity |

In Go, `eq` compares interface values, `any(a) == any(b)` (Go: [Comparison
operators][go-compare]); objects, strings and arrays are pointers, so this is identity.

- **Deviation:** boxed primitives have no identity: `eq` compares them by type and value
  (`Integer.valueOf(1000) eq Integer.valueOf(1000)` is `true`), `System.identityHashCode` of a
  box is `0`, and equal boxes share one monitor.
- **Deviation:** `==` on two references to the *same* boxed `NaN` is `false` (JVM: `true`).
- **Deviation (bug):** `==` and `!=` throw `NullPointerException` when one operand is a null of
  static type `String` (also `BigInteger`, `BigDecimal`) and the other is not null, including
  string-literal matches on a null selector (JVM: `false`); `s == null` and `t.equals(s)` work.
- **Deviation (bug):** `eq` on two references to the same Go value of a type Go cannot compare
  (`GoSlice`, `GoMap`, a Go func, a Go struct containing one) throws `RuntimeException` (JVM:
  `true`); `==` works.
- **Deviation (bug):** a boxed `Int` or `Long` `==` a boxed `Float` compares as `Double`, not
  `Float`: `(16777217L: Any) == (16777216f: Any)` is `false`.
- **Deviation (bug):** `(1: Any) == BigInt(1)` (a box on the left, `BigInt` or `BigDecimal` on
  the right) is `false`.
- **Deviation (bug):** `().##` and `().hashCode` are identity hashes (JVM: `0`).

### Type tests and casts

`isInstanceOf`, `asInstanceOf`, type patterns and `catch` clauses test the erased class. In Go
they are type assertions (Go: [Type assertions][go-assert]) to the class's Go interface, which
only its subclasses implement ([Classes and objects](data.md#classes-and-objects)), or to the Go
type of strings, boxes and arrays. Tests use the comma-ok form, which never panics.

- A boxed value is an instance of its own box class only (`(1L: Any).isInstanceOf[Int]` is
  `false`), and of `Number`, `Comparable`, `Serializable`.
- Type arguments are not checked: `List(1).isInstanceOf[List[String]]` is `true`, with the
  compiler's unchecked warning.
- `null` is an instance of nothing. `null.asInstanceOf[T]` is `null`, or the zero value for a
  value type (`null.asInstanceOf[Int] == 0`).
- A failed cast throws `ClassCastException`. **Deviation:** the message is Go's
  (`interface conversion: *scala.S_Sq is not scala.C_Point: missing method A_Point`; JVM:
  `class Sq cannot be cast to class Point (...)`). Casts from `Any` to `Comparable`,
  `CharSequence`, `Number` and the like have the JVM's message without its module details.
- **Deviation:** arrays of references do not know their element type: every `Array[R]` is an
  instance of every `Array[S]`, casts between them succeed, and no `ArrayStoreException` is
  thrown. Primitive arrays are exact ([Arrays](data.md#arrays)).
- **Deviation (bug):** a cast to a box class is not checked when the operand is typed `String`,
  `Class`, `BigInteger`, `BigDecimal` or an array: `"a".asInstanceOf[Integer]` returns the string.
- **Deviation (bug):** a cast from `Any` to a class never instantiated, or to a trait no
  instantiated class implements, lets every value through.

### Exceptions

- `throw e` throws `e`; `throw null` throws `NullPointerException`.
- `catch` cases are tested in order. `finally` runs after normal completion, after an exception,
  and after `return`, `break` or `continue` out of the `try`. An exception or `return` in
  `finally` replaces the outcome of the `try`.

In Go, `throw` is a panic and `try` a closure that `recover`s it (Go: [Handling
panics][go-panics]; [Design](../contributing/design.md#control-flow)). Go run-time panics, whose
exact values Go leaves unspecified (Go: [Run-time panics][go-rtpanics]), are recognized by type
and by gc's messages and become Java exceptions:

| Go runtime error | Exception |
|------------------|-----------|
| `nil` dereference (method call, field access on `null`) | `NullPointerException` |
| integer division by zero | `ArithmeticException("/ by zero")` |
| index out of range | `ArrayIndexOutOfBoundsException("Index 5 out of bounds for length 3")` |
| failed type assertion | `ClassCastException` |
| negative slice length | `NegativeArraySizeException` |
| any other run-time panic, or a panic in Go code with a non-exception value | `RuntimeException` with Go's message |

- **Deviation (bug):** a negative array index throws `RuntimeException("runtime error: index out
  of range [-1]")`, not `ArrayIndexOutOfBoundsException`.
- **Deviation:** `NullPointerException`s have no message (the JVM's name the `null` expression).
- **Deviation (bug):** `String` index errors have JDK 8's messages (`"abc".charAt(5)`:
  `index 5, length 3`; JDK 25: `Index 5 out of bounds for length 3`), and `Integer.parseInt(s,
  radix)` omits the JDK's `under radix 16` suffix.
- Go fatal errors (stack or heap exhaustion, Go's own fatal conditions) are not exceptions: the
  process ends with exit status 2 without running `catch` or `finally`
  ([Runtime: stack](runtime.md#stack)).
- `StackOverflowError` and `OutOfMemoryError` are `VirtualMachineError`s, so `NonFatal` rejects
  them; the runtime never throws them.
- A `Throwable` captures its stack trace when constructed
  ([Runtime: stack traces](runtime.md#stack-traces)).

### Functions and closures

- A lambda is an instance of a class the linker synthesizes (`Owner$$Lambda$n`), like the JVM's
  `LambdaMetafactory` classes. Captured `var`s are shared through `scala.runtime.*Ref` objects.
- SAM conversion works for every single-abstract-method type, Scala or Java (`Runnable`,
  `Comparator`, `java.util.function.*`), with the bridges `LambdaMetafactory` would add.
- A non-local `return` inside a lambda throws and catches `scala.runtime.NonLocalReturnControl`.
  `scala.util.boundary`/`break` throw a `Break` without a stack trace.

### Other expressions

- `synchronized` takes the receiver's monitor: reentrant, released however the block
  completes. Any value can be locked ([Monitors](data.md#classes-and-objects)).
- Self-recursive tail calls are loops (the compiler rewrites them, with or without `@tailrec`).
- Matches the compiler turns into switches (integer and character literal cases) are Go
  expression `switch`es, whose cases do not fall through (Go: [Expression
  switches][go-switch]). Other matches, including most on string literals, are `==` tests and
  jumps.

## 7. Implicits

[Spec chapter 7](https://scala-lang.org/files/archive/spec/3.4/07-implicits.html). As in Scala.
At run time, givens and implicit values are ordinary `val`s, `lazy val`s, `def`s and objects.
`ClassTag`s work (`runtimeClass`, `newArray`; see [Arrays](data.md#arrays)).

## 8. Pattern matching

[Spec chapter 8](https://scala-lang.org/files/archive/spec/3.4/08-pattern-matching.html).
Scala compiles patterns into type tests, `unapply` calls, equality tests and jumps; the
evaluation order of extractors and guards, and `MatchError`, are therefore the JVM's. Type
patterns are type tests ([Type tests and casts](#type-tests-and-casts)), including the array
deviation. Literal patterns compare with `==`.

## 9. Top-level definitions

[Spec chapter 9](https://scala-lang.org/files/archive/spec/3.4/09-top-level-definitions.html).

- Packages exist at run time only in class names. All generated code is in one Go package, so
  cyclic dependencies between Scala packages are allowed.
- Top-level definitions belong to synthetic `$package` objects.
- The entry point is the `main(Array[String])` method of the object named at link time (`Main`
  by default), or a class generated for a `@main` method
  ([Runtime](runtime.md#program-start-and-exit)).

## 10. XML

[Spec chapter 10](https://scala-lang.org/files/archive/spec/3.4/10-xml-expressions-and-patterns.html).
Scala 3 has no XML literals; neither does scala-go.

## 11. Annotations

[Spec chapter 11](https://scala-lang.org/files/archive/spec/3.4/11-annotations.html).
Annotations with a run-time effect:

| Annotation | Effect |
|------------|--------|
| `@volatile` | sequentially consistent loads, stores and compare-and-set |
| `@static` | a Go package variable or function, set by the static initializer |
| `@throws` | none, except on `@GoExport` methods: the Go function gets a trailing `error` result |
| `@native` | no body. **Deviation:** calling it throws `RuntimeException("native method stub")` (JVM: `UnsatisfiedLinkError`) |
| `@GoImport`, `@GoName`, `@GoExport` | Go facades and exports ([Go interoperability](interop.md), [Go interop types](data.md#go-interop-types)) |
| `@transient`, `@SerialVersionUID` | none (there is no Java serialization) |
| `@inline`, `@noinline` | none in the back end; Go inlines by its own heuristics |

Others (`@tailrec`, `@switch`, `@BeanProperty`, `@varargs`, `@deprecated`, ...) act at compile
time only.

## 12. The standard library

[Spec chapter 12](https://scala-lang.org/files/archive/spec/3.4/12-the-scala-standard-library.html).
The Scala library is scala-library 3.9.0, recompiled from its TASTy: `Option`, collections,
`Predef`, string interpolators, `scala.math`, `scala.util`, `scala.concurrent` and
`scala.sys.process` are the JVM's code. Exceptions:

- javalib replaces `scala.runtime.*Ref`, `LazyVals`, `JProcedureN`, `ScalaNumber`,
  `Enumeration` (names values without reflection) and `DynamicVariable` (a faster
  `Console.withOut` when nothing is redirected). `BoxesRunTime` and `Statics` are native.
- Specialized tuple classes are replaced by the generic ones
  ([Data representation](data.md#case-classes-tuples-options-and-collections)).
- `scala.quoted` is not available at run time; macros run in the compiler.
- **Deviation:** `scala.util.Properties` values read from `library.properties` (such as
  `versionNumberString`) are empty: a Go binary has no class-path resources.

Library code that needs a JDK API javalib lacks fails to link if reached.

## Scala 3 reference features

| Feature | Run-time behavior |
|---------|-------------------|
| Enums, opaque types | As Scala compiles them ([Data](data.md#enums-and-opaque-types)). `EnumSet`/`EnumMap` are not implemented |
| Extension methods, export clauses, `given`/`using`, context functions | Ordinary methods, forwarders, values and lambdas |
| `inline`, macros | Expanded by the compiler. **Deviation (bug):** stack trace lines of inlined code are the inlined source's (JVM: the call site's) |
| Union and intersection types, match types, type lambdas, kind polymorphism, explicit nulls, capture checking | Erased or compile-time only |
| `Selectable`, `Dynamic` | See [Types](#3-types); `Dynamic` calls are rewritten at compile time |
| `@main` | The generated class parses the arguments (`scala.util.CommandLineParser`) |
| Tuples (`*:`, `TupleXXL`) | Library classes; elements of `Tuple1`–`Tuple22` are boxed |
| `boundary`/`break` | Exceptions without stack traces |

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
