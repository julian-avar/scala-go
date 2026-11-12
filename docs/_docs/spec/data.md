---
title: Data representation
---

# Data representation

The Go representation of Scala values. Programs observe it only through semantics (equality,
identity, type tests, `getClass`; see [Language](language.md)) and through Go interop, where the
representation is the contract.

Notation: `rt.X` is a runtime type; `S_C` is the struct generated for class `C`, `C_C` the Go
interface of references to `C` (names are mangled).

## Primitive types

| Scala | Go | Array class |
|-------|----|-------------|
| `Boolean` | `bool` | `[Z` |
| `Char` | `uint16` (a UTF-16 code unit) | `[C` |
| `Byte`, `Short`, `Int`, `Long` | `int8`, `int16`, `int32`, `int64` | `[B`, `[S`, `[I`, `[J` |
| `Float`, `Double` | `float32`, `float64` | `[F`, `[D` |
| `Unit` | no value (methods have no result); boxed, the single `rt.UnitValue` (`*rt.BoxedUnit`) | an array of references |
| `Nothing` | no value | — |

- The Go types have the JVM types' sizes and representations (Go: [Numeric types][go-numeric],
  [Size and alignment guarantees][go-size]). Go's `int`, `uint` and `uintptr` are not used for
  Scala values.
- Primitive values are unboxed in fields, locals, parameters, results and primitive arrays.
- **Boxing** converts the value to `any` (Go: [Assignability][go-assign]); its dynamic Go type
  determines the box class (`int32` is an `Integer`, `uint16` a `Character`). It allocates,
  except, in gc, for integer values 0–255 and for `0.0`. `Integer.valueOf`, `BoxesRunTime.boxToInteger` and implicit
  boxing are the same conversion.
- **Unboxing** is a type assertion (Go: [Type assertions][go-assert]); unboxing `null` gives the
  zero value.
- **Deviation:** boxes have no identity: interface values with equal dynamic types and values are
  equal (Go: [Comparison operators][go-compare]), so equal boxes are `eq`
  ([Equality and hashing](language.md#equality-and-hashing)).

## Strings

| Scala | Go | Notes |
|-------|----|-------|
| `String` (`java.lang.String`) | `*rt.String`: UTF-16 code units (`[]uint16`) and a cached hash | `null` is a nil pointer. Immutable |
| `scalago.go.GoString` | Go `string` (UTF-8 bytes; Go: [String types][go-strings]) | opt-in; a byte array on the JVM. `byteLength`, `byteAt` expose bytes, which `String` never uses |

- `length`, `charAt`, indices, `substring`, `compareTo` and `hashCode` work on UTF-16 code
  units and agree with the JVM for all text, including surrogate pairs and unpaired surrogates.
- Most `String` methods are Go natives; regex-based ones run on Go's `regexp` (RE2).
- Converting to Go text (I/O, interop, `s.toGoString`) copies and transcodes UTF-16 to UTF-8;
  unpaired surrogates become U+FFFD (on the console, `?`, as on the JDK). Converting from Go
  text (`g.toJavaString`) replaces invalid UTF-8 with U+FFFD.

## Arrays

| Scala | Go |
|-------|----|
| `Array[P]` for a primitive `P` | `*rt.Array[p]`, `p` the Go primitive type |
| `Array[R]` for any reference type `R` (`String`, `Any`, a class, an array, `Unit`) | `*rt.Array[any]` |

- `rt.Array[T]` holds a Go slice (`Data []T`); `null` is a nil pointer. Indexing is
  bounds-checked (`ArrayIndexOutOfBoundsException`). Multi-dimensional arrays are arrays of array
  references.
- Arrays are covariant (an `Array[String]` is an `Array[AnyRef]`): all arrays of references share
  one Go type.
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
| superclass | `S_C` embeds the superclass's struct (Go: [Struct types][go-struct]); root classes embed `rt.ObjectHeader` |
| fields | struct fields of the field type's Go type, in declaration order |
| `@volatile` field | `rt.VolatileInt32`, `rt.VolatileInt64`, `rt.VolatileBool`, ... (`sync/atomic`), or `rt.Volatile[T]` for references |
| reference of static type `C` | interface `C_C`: the methods called on receivers of type `C`, embedding the parents' interfaces |
| reference of type `Any`, `AnyRef`, `Object`, or a type that strings, boxes or arrays implement (`Comparable`, `CharSequence`, `Number`, `Serializable`, `Cloneable`, ...) | `any` |
| method | Go function `Impl_C__m(this, args...)`, plus a forwarding Go method on each instantiated struct for virtual methods |
| `object O` | one `*S_O_S` in a package variable, created on first use by `Mod_O_S()` |
| `@static` field, Java static | package variable, initialized by `Clinit_C()` |
| `new Object()` | `*rt.Object` |
| class never instantiated | no struct; references are `any` |
| `null` | a nil interface, or a nil pointer for `String`, arrays, `Class`, `BigInteger`, `BigDecimal` |

**Type tests.** `*S_C` implements `C_C` through its method set, including promoted methods (Go:
[Method sets][go-methodsets], [Implementing an interface][go-implements]). `C_C` has an accessor
`A_C() *S_C`, through which fields are accessed; a trait's interface has a marker `I_T()`. These
per-class methods make a Go type assertion an exact type test: unrelated classes with the same
methods do not pass ([Design: object model](../contributing/design.md#object-model)).

**Null.** An interface holding a nil pointer is not a nil interface (Go: [Comparison
operators][go-compare]), so null references of pointer types are converted to `any` through
`rt.AnyRef`, and null tests on `any` values use `rt.IsNull`. Where an operand misses this
conversion, `==` throws ([Equality and hashing](language.md#equality-and-hashing)).

**Monitors.** `rt.ObjectHeader` is one atomic pointer (8 bytes; a JVM header is 12–16) to the
object's monitor (a mutex, a condition, an owner goroutine id, a recursion count, a wait set),
allocated on first `synchronized`, `wait` or `notify`. Values without a header get monitors from
a global table, keyed by reference for strings, by value for boxes and comparable Go values, by
address for other Go values; its entries are never removed.

**Identity hash codes** (`System.identityHashCode`, the default `hashCode` and `toString`) are
the object's address, which never changes; `0` for boxes and non-pointer Go values.

## Traits

A trait `T` is an interface `C_T`: a marker method `I_T()` and the methods called on `T`
receivers. A method with a body is a Go function taking `this` as an interface, to which each
class mixing in `T` gets a forwarding Go method unless it overrides it. Trait fields are fields
of the classes that mix the trait in.

## Functions and lambdas

A lambda is a struct whose fields are the captured values, implementing the functional
interface's abstract method (and its bridges) by calling the method the compiler lifted the body
into. Arguments and results of generic function types are boxed (no specialization).

## Case classes, tuples, options and collections

- Tuples are `scala.Tuple1`–`Tuple22` (and `TupleXXL`) with boxed elements. The compiler's
  specialized tuple classes (`Tuple2$mcII$sp`) are replaced by the generic ones.
- Case classes, `Option`, `Either`, `Try`, `List`, `Vector`, `Map`, `Set`, ranges,
  `StringBuilder` and all other Scala library types are ordinary classes, the library's compiled
  unchanged from its TASTy. Their elements are boxed.
- `java.util` collections are javalib's Scala implementations, with the JDK's iteration orders.

## Classes implemented natively

Instances of these classes are Go values rather than generated structs ("hijacked", as in
Scala.js); their methods are Go functions:

| Class | Go value |
|-------|----------|
| `java.lang.String` | `*rt.String` |
| `java.lang.Boolean`, `Character`, `Byte`, `Short`, `Integer`, `Long`, `Float`, `Double` | the Go primitive |
| `scala.runtime.BoxedUnit` | `*rt.BoxedUnit` |
| `java.lang.Class` | `*rt.Class` (a JVM class name, interned) |
| `java.math.BigInteger` | `*rt.BigInteger` (a `math/big` integer) |
| `java.math.BigDecimal` | `*rt.BigDecimal`: an `int64` unscaled value, or a `big.Int` when it does not fit, and an `int32` scale |

- Calls on references typed by a supertype of these (`Any`, `Comparable`, `CharSequence`,
  `Number`) go through Go type switches.
- **Deviation:** `BigInteger` and `BigDecimal` cannot be subclassed. **Deviation (bug):** a
  subclass is not a link error; the generated code does not compile.
- `scala.math.BigInt` and `scala.math.BigDecimal` are the Scala library's wrappers around them.
- Static members of `Math`, `StrictMath`, `System`, `Character`, `Integer` and the other boxes,
  `java.util.Arrays` (primitive arrays), `Objects`, `java.lang.reflect.Array`,
  `scala.runtime.BoxesRunTime` and `scala.runtime.Statics` are natives too.

## Enums and opaque types

- A Scala 3 `enum` is a sealed abstract class; `toString` is the case name. Singleton cases are
  instances of anonymous subclasses (`Color$$anon$1`) held by the companion object;
  parameterized cases are case classes.
- An `enum` extending `java.lang.Enum` has javalib's `Enum` as superclass (`name`, `ordinal`,
  `compareTo`, identity `equals`). Its cases are static fields, set when the companion object is
  constructed.
- `scala.Enumeration` values are the library's `Val` objects; javalib's `Enumeration` registers
  their names without reflection.
- An opaque type is its underlying type, with no wrapper.

## Go interop types

These `scalago.go` types represent Go values directly. Their semantics, and the conversions at
facade calls and exports, are in [Go interoperability](interop.md); element views, struct
passing and `==` on Go values in [Go data](interop.md#go-data).

| Scala | Go value | On the JVM |
|-------|----------|------------|
| `GoString` | `string` | `Array[Byte]` (UTF-8) |
| `GoSlice[T]` | `[]T`, shared with Go (no copy) | an array segment with Go's semantics |
| `GoMap[K, V]` | `map[K]V`, shared | a hash map with Go's semantics |
| `GoChan[T]` | `chan T` | a channel with Go's semantics |
| `GoPtr[T]` | `*T` | a mutable cell |
| `GoInt`, `GoUint`, `GoUint64`, `GoUintptr` | `int`, `uint`, `uint64`, `uintptr` (as `Long` in Scala, bit for bit) | `Long` |
| `GoUint32`, `GoByte` | `uint32`, `uint8` (as `Int`, `Byte`) | `Int`, `Byte` |
| `GoComplex` | `complex128` | a pair of `Double`s |
| facade class of a Go struct type `T` | `*T` inside an `any` | — (facade calls throw) |
| facade class of any other Go type | the Go value inside an `any` | — |
| facade trait (a Go interface) | the Go interface value; Scala classes extending it get Go methods | — |
| `GoError` | `error` | — |
| `GoException` | a Scala exception holding a Go `error` | — |
| Go func returned to Scala | a `GoFunction0`–`GoFunction3` wrapping it | — |

[go-assert]: https://go.dev/ref/spec#Type_assertions
[go-assign]: https://go.dev/ref/spec#Assignability
[go-compare]: https://go.dev/ref/spec#Comparison_operators
[go-implements]: https://go.dev/ref/spec#Implementing_an_interface
[go-methodsets]: https://go.dev/ref/spec#Method_sets
[go-numeric]: https://go.dev/ref/spec#Numeric_types
[go-size]: https://go.dev/ref/spec#Size_and_alignment_guarantees
[go-strings]: https://go.dev/ref/spec#String_types
[go-struct]: https://go.dev/ref/spec#Struct_types
