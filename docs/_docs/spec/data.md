---
title: Data representation
---

# Data representation

This page specifies the Go representation of Scala values. Programs observe it only through
semantics (equality, identity, type tests, `getClass`), which follow the JVM except where
marked, and through Go interop, where the representation is the contract.

Notation: `rt.X` is a runtime type; `S_C` is the struct generated for class `C`, `C_C` the Go
interface of references to `C` (names are mangled, see `linker/Mangling.scala`).

## Primitive types

| Scala | Go | Boxed (in `any`) | `getClass.getName` of the box | Array |
|-------|----|------------------|-------------------------------|-------|
| `Boolean` | `bool` | `bool` | `java.lang.Boolean` | `*rt.Array[bool]` (`[Z`) |
| `Char` | `uint16` (a UTF-16 code unit) | `uint16` | `java.lang.Character` | `*rt.Array[uint16]` (`[C`) |
| `Byte` | `int8` | `int8` | `java.lang.Byte` | `*rt.Array[int8]` (`[B`) |
| `Short` | `int16` | `int16` | `java.lang.Short` | `*rt.Array[int16]` (`[S`) |
| `Int` | `int32` | `int32` | `java.lang.Integer` | `*rt.Array[int32]` (`[I`) |
| `Long` | `int64` | `int64` | `java.lang.Long` | `*rt.Array[int64]` (`[J`) |
| `Float` | `float32` | `float32` | `java.lang.Float` | `*rt.Array[float32]` (`[F`) |
| `Double` | `float64` | `float64` | `java.lang.Double` | `*rt.Array[float64]` (`[D`) |
| `Unit` | no value (methods have no result) | `*rt.BoxedUnit`, the single `rt.UnitValue` | `scala.runtime.BoxedUnit` | an array of references |
| `Nothing` | no value | — | — | — |

- The Go types have the JVM types' sizes and representations: Go guarantees the sizes of its
  sized numeric types, two's-complement integers and IEEE 754 floats (Go:
  [Numeric types][go-numeric], [Size and alignment guarantees][go-size]). Go's `int`, `uint` and
  `uintptr`, whose size is implementation-specific, are not used for Scala values.
- Primitive values are unboxed in fields, locals, parameters, results and primitive arrays.
- **Boxing** stores the Go value in an `any`: a conversion to an interface type (Go:
  [Assignability][go-assign]). The dynamic Go type determines the box class (`int32` is an
  `Integer`, `uint16` a `Character`). It allocates unless the value is small (gc keeps 0–255 in
  a static table). `Integer.valueOf`, `BoxesRunTime.boxToInteger` and implicit boxing are the
  same conversion.
- **Unboxing** is a Go type assertion (Go: [Type assertions][go-assert]). Unboxing `null` gives
  the zero value, as on the JVM.
- **Deviation:** boxes have no identity. Interface values with equal dynamic types and values are
  equal (Go: [Comparison operators][go-compare]), so equal boxes are `eq`, have
  `System.identityHashCode` `0` and share one monitor (see
  [Equality and hashing](language.md#equality-and-hashing)).

## Strings

| Scala | Go | Notes |
|-------|----|-------|
| `String` (`java.lang.String`) | `*rt.String`: UTF-16 code units (`[]uint16`) and a cached hash | `null` is a nil pointer. Immutable. Literals are package variables, one per distinct literal |
| `scalago.go.GoString` | Go `string` (UTF-8 bytes) | opt-in; a byte array on the JVM |

- `length`, `charAt`, indices, `substring`, `compareTo` and `hashCode` work on UTF-16 code
  units. They agree with the JVM for all text, including surrogate pairs and unpaired surrogates.
- Most `String` methods are Go natives; regex-based ones run on Go's `regexp` (RE2).
- Converting to Go text (I/O, interop, `GoString`) transcodes UTF-16 to UTF-8; unpaired
  surrogates become U+FFFD (on the console, `?`, as on the JDK). Converting from Go text
  replaces invalid UTF-8 with U+FFFD.
- `String` and `GoString` are distinct types; `s.toGoString` and `g.toJavaString` copy and
  transcode.
- A Go `string` is a byte sequence, conventionally UTF-8: `len` counts bytes, indexing gives
  bytes, `range` decodes UTF-8 (Go: [String types][go-strings]). `GoString` exposes these
  (`byteLength`, `byteAt`); `String` never uses them.
- **Deviation (bug):** `s.indexOf(cp)` with an integer that is not a valid code point
  (`0x110000`) searches for U+FFFD: `"a�b".indexOf(0x110000)` is `1` (JVM: `-1`). Go converts
  such an integer to `"�"` (Go: [Conversions to and from a string type][go-string-conv]).
- **Deviation (bug):** `s.lastIndexOf(cp)` and `lastIndexOf(cp, from)` with a supplementary code
  point compare it with single UTF-16 code units, so they never find it:
  `"a😀b".lastIndexOf(0x1F600)` is `-1` (JVM: `1`). `indexOf` finds it.

## Arrays

| Scala | Go |
|-------|----|
| `Array[P]` for a primitive `P` | `*rt.Array[p]`, `p` the Go primitive type |
| `Array[R]` for any reference type `R` (`String`, `Any`, a class, an array, `Unit`) | `*rt.Array[any]` |

- `rt.Array[T]` is a struct holding a Go slice (`Data []T`). An array reference is a pointer to
  it; `null` is nil. Lengths are `Int`s. Indexing is bounds-checked
  (`ArrayIndexOutOfBoundsException`).
- Multi-dimensional arrays are arrays of array references.
- Arrays are covariant as on the JVM (an `Array[String]` is an `Array[AnyRef]`): all arrays of
  references share one Go type.
- `clone` copies. `System.arraycopy` throws `ArrayStoreException` between arrays of different Go
  element types.
- **Deviation:** an array of references does not record its element type.
  - `getClass` is `[Ljava.lang.Object;` (JVM: `[Ljava.lang.String;`, `[[I`, `[LPoint;`), so
    `Array("a").getClass != classOf[Array[String]]`, and `toString` is `[Ljava.lang.Object;@...`.
  - Every array of references is an instance of every `Array[R]` (`Array("a")` is an
    `Array[Point]`), and casts between them never fail. A non-empty one holding only `()` is an
    `Array[Unit]`.
  - Storing an element of the wrong class does not throw `ArrayStoreException`.
  - `ClassTag.newArray`, `java.lang.reflect.Array.newInstance`, `Arrays.copyOf` and
    `Collection.toArray(T[])` make `Object[]`s.

## Classes and objects

| Scala | Go |
|-------|----|
| class `C` (instance) | struct `S_C` on the Go heap; a reference is `*S_C` inside an interface |
| superclass | `S_C` embeds the superclass's struct; root classes embed `rt.ObjectHeader` |
| fields | struct fields of the field type's Go type, in declaration order |
| `@volatile` field | `rt.VolatileInt32`, `rt.VolatileInt64`, `rt.VolatileBool`, ... (`sync/atomic`), or for reference types `rt.Volatile[T]` (an atomic pointer to an immutable box) |
| reference of static type `C` | interface `C_C`: the methods called on receivers of type `C`, embedding the parents' interfaces |
| reference of type `Any`, `AnyRef`, `Object`, or a type that strings, boxes or arrays implement (`Comparable`, `CharSequence`, `Number`, `Serializable`, `Cloneable`, ...) | `any` |
| `this` in a class with a single instantiated subclass (leaf classes, objects, lambdas) | `*S_D` directly, so calls and field accesses are static |
| method | Go function `Impl_C__m(this, args...)`; virtual methods also get forwarding Go methods on each instantiated struct |
| `object O` | one `*S_O_S` in a package variable, created on first use by `Mod_O_S()` under an `rt.InitLock` |
| `@static` field, Java static | package variable, initialized by `Clinit_C()` |
| `new Object()` | `*rt.Object` |
| `null` | a nil interface, or a nil pointer for `String`, arrays, `Class`, `BigInteger`, `BigDecimal`. Converted to `any`, a nil pointer becomes a nil `any` |

**Structs and interfaces.** A subclass's struct embeds its superclass's struct, so the
superclass's fields and Go methods are promoted (Go: [Struct types][go-struct]).

- `*S_C` implements `C_C` because its method set, including methods promoted from embedded
  structs, contains every method of `C_C` (Go: [Method sets][go-methodsets],
  [Implementing an interface][go-implements]).
- `C_C` has an accessor `A_C() *S_C`, declared on `*S_C` and promoted to subclasses. It returns
  the embedded `S_C`, through which fields are accessed. A trait's interface has a marker method
  `I_T()` instead.
- Virtual methods are declared on each instantiated struct, as forwarders that pass the outer
  pointer to the implementation. A promoted method would receive the embedded struct and lose
  the object's identity.
- Type tests against classes are Go type assertions. Go interfaces are satisfied structurally;
  the per-class marker methods make the structure name the class. So tests are exact:
  subclasses pass, unrelated classes with the same methods do not.
- Classes that are never instantiated have no struct. References to them are `any`, and only
  `null` should pass a cast to them. **Deviation (bug):** from an operand typed `Any`, every value
  passes, also for a trait no instantiated class implements.

**Null.** An interface value is nil only if it has neither dynamic type nor value; an interface
holding a nil pointer is not nil (Go: [Comparison operators][go-compare]). Null references of
pointer types (`String`, arrays, ...) are therefore converted to `any` through `rt.AnyRef`, which
gives a nil `any`, and null tests on `any` values use `rt.IsNull`. Where an operand misses this
conversion, `==` throws (see [Equality and hashing](language.md#equality-and-hashing)).

**Object header.** `rt.ObjectHeader` is one atomic pointer (8 bytes) to the object's monitor,
allocated on first `synchronized`, `wait` or `notify`. It also keeps objects from being
zero-sized, so distinct objects have distinct addresses. A JVM object header is 12–16 bytes.

**Monitors.** A monitor is a Go mutex, a condition, an owner goroutine id, a recursion count and
a wait set. Values without a header (strings, boxes, Go values) get monitors from a global table
whose entries are never removed. An uncontended `synchronized` costs about 55 ns.

**Identity hash codes** (`System.identityHashCode`, the default `hashCode` and `toString`) derive
from the object's address, which never changes: Go's collector does not move objects. They are
`0` for boxes and non-pointer Go values.

## Traits

| Scala | Go |
|-------|----|
| trait `T` | interface `C_T`: a marker method `I_T()` and the methods called on `T` receivers |
| trait method with a body | a Go function taking `this` as an interface; each class mixing in `T` gets a forwarding Go method to it unless it overrides it |
| trait fields | fields of the classes that mix the trait in (as Scala compiles them) |

Method resolution follows the JVM: class methods win over trait default methods, then the most
specific default method wins.

## Functions and lambdas

- A lambda is an instance of a synthetic class `Owner$$Lambda$n`: a struct whose fields are the
  captured values. It implements the functional interface's abstract method (and its bridges)
  by calling the method the compiler lifted the lambda body into.
- `FunctionN` values are ordinary objects. Arguments and results of generic function types are
  boxed, as on the JVM without specialization.
- **Deviation:** a lambda's `toString` is the function trait's, `<function1>` (see
  [String conversion](language.md#string-conversion)).

## Case classes, tuples, options and collections

- Case classes are classes with compiler-generated members; nothing in the back end is specific
  to them.
- Tuples are `scala.Tuple1`–`Tuple22` (and `TupleXXL`) with boxed `Object` elements. The
  compiler's specialized tuple classes (`Tuple2$mcII$sp`) are replaced by the generic ones.
- `Option`, `Either`, `Try`, `List`, `Vector`, `Map`, `Set`, ranges, `StringBuilder` and all
  other Scala library types are the library's classes, compiled unchanged from its TASTy. Their
  elements are boxed.
- `java.util` collections are javalib's Scala implementations, with the JDK's iteration orders.

## Classes implemented natively

Instances of these classes are Go values rather than generated structs ("hijacked", as in
Scala.js); their methods are Go functions (`linker/Hijacked.scala`, `linker/Natives.scala`):

| Class | Go value |
|-------|----------|
| `java.lang.String` | `*rt.String` |
| `java.lang.Boolean`, `Character`, `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double` | the Go primitive |
| `scala.runtime.BoxedUnit` | `*rt.BoxedUnit` |
| `java.lang.Class` | `*rt.Class` (a JVM class name, interned) |
| `java.math.BigInteger` | `*rt.BigInteger` (a `math/big` integer) |
| `java.math.BigDecimal` | `*rt.BigDecimal`: an `int64` unscaled value, or a `big.Int` when it does not fit, and an `int32` scale |

- Calls on references whose static type is a supertype of these (`Any`, `Comparable`,
  `CharSequence`, `Number`) go through Go type switches.
- **Deviation:** `BigInteger` and `BigDecimal` cannot be subclassed. **Deviation (bug):** a
  subclass is not a link error; the generated code does not compile.
- `scala.math.BigInt` and `scala.math.BigDecimal` are the Scala library's wrappers around them.
- Static members of `Math`, `StrictMath`, `System`, `Character`, `Integer` and the other boxes,
  `java.util.Arrays` (primitive arrays), `Objects`, `java.lang.reflect.Array`,
  `scala.runtime.BoxesRunTime` and `scala.runtime.Statics` are natives too.

## Enums and opaque types

- A Scala 3 `enum` is a sealed abstract class, as Scala compiles it: singleton cases are
  instances of anonymous subclasses held by the companion object; parameterized cases are case
  classes.
- An `enum` extending `java.lang.Enum` has javalib's `Enum` as superclass (`name`, `ordinal`,
  `compareTo`, identity `equals`). Its cases are static fields, set when the companion object is
  constructed.
- `scala.Enumeration` values are the library's `Val` objects; javalib's `Enumeration` registers
  their names without reflection.
- An opaque type is its underlying type, with no wrapper.

## Go interop types

These `scalago.go` types represent Go values directly. Their semantics, and the conversions at
facade calls and exports, are specified in [Go interoperability](interop.md).

| Scala | Go value | On the JVM |
|-------|----------|------------|
| `GoString` | `string` | `Array[Byte]` (UTF-8) |
| `GoSlice[T]` | `[]T`, shared with Go (no copy) | an array segment with Go's semantics |
| `GoMap[K, V]` | `map[K]V`, shared | a hash map with Go's semantics |
| `GoChan[T]` | `chan T` | a channel with Go's semantics |
| `GoPtr[T]` | `*T` | a mutable cell |
| `GoInt`, `GoUint`, `GoUint64`, `GoUintptr` | `int`, `uint`, `uint64`, `uintptr` (as `Long` in Scala, bit for bit) | `Long` |
| `GoUint32`, `GoByte` | `uint32`, `uint8` (as `Int`, `Byte`) | `Int`, `Byte` |
| facade class of a Go struct type `T` | `*T` inside an `any` | — (facade calls throw) |
| facade class of any other Go type | the Go value inside an `any` | — |
| facade trait (a Go interface) | the Go interface value; Scala classes extending it get Go methods | — |
| `GoError` | `error` | — |
| `GoException` | a Scala exception holding a Go `error` | — |
| Go func returned to Scala | a `GoFunction0`–`GoFunction3` wrapping it | — |

Element views, struct passing and `==` on Go values: [Go interoperability](interop.md#go-data).

[go-assert]: https://go.dev/ref/spec#Type_assertions
[go-assign]: https://go.dev/ref/spec#Assignability
[go-compare]: https://go.dev/ref/spec#Comparison_operators
[go-implements]: https://go.dev/ref/spec#Implementing_an_interface
[go-methodsets]: https://go.dev/ref/spec#Method_sets
[go-numeric]: https://go.dev/ref/spec#Numeric_types
[go-size]: https://go.dev/ref/spec#Size_and_alignment_guarantees
[go-string-conv]: https://go.dev/ref/spec#Conversions_to_and_from_a_string_type
[go-strings]: https://go.dev/ref/spec#String_types
[go-struct]: https://go.dev/ref/spec#Struct_types
