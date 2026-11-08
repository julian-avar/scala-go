---
title: Data representation
---

# Data representation

This page specifies how Scala values are represented in the generated Go code. Programs observe
the representation only through its semantics (equality, identity, type tests, `getClass`),
which follow the JVM except where marked **Deviation:**, and through Go interop, where the
representation is the contract.

Notation: `rt.X` is a type of the runtime package; `S_C` is the struct generated for class `C`,
`C_C` the Go interface of references to `C` (names are mangled, see `linker/Mangling.scala`).

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
  sized numeric types, two's-complement integers and IEEE 754 floating-point numbers (Go:
  [Numeric types][go-numeric], [Size and alignment guarantees][go-size]). Go's `int`, `uint` and
  `uintptr`, whose size is implementation-specific, are not used for Scala values.
- Values of primitive types are unboxed in fields, locals, parameters, results and primitive
  arrays.
- **Boxing.** A boxed primitive is the Go value stored in an `any`; the box class is determined
  by the dynamic Go type (`int32` is an `Integer`, `uint16` a `Character`). Boxing is the
  conversion of the value to an interface type (Go: [Assignability][go-assign]), which allocates
  unless the value is small (gc keeps the values 0–255 in a static table). `Integer.valueOf`,
  `BoxesRunTime.boxToInteger` and implicit boxing are the same conversion.
- **Unboxing** is a Go type assertion (Go: [Type assertions][go-assert]); unboxing `null` gives
  the zero value, as on the JVM.
- **Deviation:** boxes have no identity. Two interface values are equal when their dynamic types
  and values are (Go: [Comparison operators][go-compare]), so two boxes of the same type and
  value are `eq`, their `System.identityHashCode` is `0`, and they share one monitor.

## Strings

| Scala | Go | Notes |
|-------|----|-------|
| `String` (`java.lang.String`) | `*rt.String`: a struct of UTF-16 code units (`[]uint16`) and a cached hash | `null` is a nil pointer. Immutable. Literals are package variables, one per distinct literal |
| `scalago.go.GoString` | Go `string` (UTF-8 bytes) | opt-in; a byte array on the JVM |

- `length`, `charAt`, indices, `substring`, `compareTo` and `hashCode` work on UTF-16 code units,
  so they agree with the JVM for all text, including surrogate pairs and unpaired surrogates.
- Most `String` methods are Go natives; regex-based ones run on Go's `regexp` (RE2).
- Converting to Go text (I/O, interop, `GoString`) transcodes UTF-16 to UTF-8: unpaired
  surrogates become U+FFFD (on the console, `?`, as on the JDK). Converting from Go text replaces
  invalid UTF-8 with U+FFFD.
- `String` and `GoString` are distinct types; `s.toGoString` and `g.toJavaString` copy and
  transcode.
- A Go `string` is a sequence of bytes, conventionally UTF-8: `len` counts bytes, indexing gives
  bytes and `range` decodes UTF-8 (Go: [String types][go-strings]). `GoString` exposes these
  (`byteLength`, `byteAt`); `String` never uses them.
- Go's conversion of an integer to a string gives the UTF-8 encoding of that code point, or
  `"�"` for values that are not valid code points (Go:
  [Conversions to and from a string type][go-string-conv]). **Deviation (bug):**
  `s.indexOf(cp)` with an integer that is not a valid code point (`0x110000`) searches for
  U+FFFD this way: `"a�b".indexOf(0x110000)` is `1` (JVM: `-1`).
- **Deviation (bug):** `s.lastIndexOf(cp)` and `lastIndexOf(cp, from)` with a supplementary
  code point compare it with single UTF-16 code units, so they never find it:
  `"a😀b".lastIndexOf(0x1F600)` is `-1` (JVM: `1`). `indexOf` finds it.

## Arrays

| Scala | Go |
|-------|----|
| `Array[P]` for a primitive `P` | `*rt.Array[p]`, `p` the Go primitive type |
| `Array[R]` for any reference type `R` (`String`, `Any`, a class, an array, `Unit`) | `*rt.Array[any]` |

- `rt.Array[T]` is a struct holding a Go slice (`Data []T`); an array reference is a pointer to
  it, `null` is nil. Lengths are `Int`s. Indexing is bounds-checked
  (`ArrayIndexOutOfBoundsException`).
- Multi-dimensional arrays are arrays of array references.
- Arrays are covariant as on the JVM (an `Array[String]` is an `Array[AnyRef]`), because all
  arrays of references share one Go type.
- **Deviation:** an array of references does not record its element type:
  - `getClass` is `[Ljava.lang.Object;` (JVM: `[Ljava.lang.String;`, `[[I`, `[LPoint;`), so
    `Array("a").getClass != classOf[Array[String]]`;
  - every array of references is an instance of every `Array[R]` and casts between them never
    fail; a non-empty one holding only `()` is an `Array[Unit]`;
  - storing an element of the wrong class does not throw `ArrayStoreException`;
  - arrays made by `ClassTag.newArray`, `java.lang.reflect.Array.newInstance`,
    `Arrays.copyOf` and `Collection.toArray(T[])` are `Object[]`s.
- `clone` copies; `System.arraycopy` throws `ArrayStoreException` between arrays of different
  Go element types.

## Classes and objects

| Scala | Go |
|-------|----|
| class `C` (instance) | struct `S_C`, allocated on the Go heap; a reference is `*S_C` inside an interface |
| superclass | `S_C` embeds the superclass's struct; root classes embed `rt.ObjectHeader` |
| fields | struct fields of the Go type of the field's type, in declaration order |
| `@volatile` field | `rt.VolatileInt32`, `rt.VolatileInt64`, `rt.VolatileBool`, ... (`sync/atomic`), or `rt.Volatile[T]` (an atomic pointer to an immutable box) for reference types |
| reference of static type `C` | interface `C_C`, which lists the methods called on receivers of type `C` and embeds the parents' interfaces |
| reference of type `Any`, `AnyRef`, `Object`, or a type that strings, boxes or arrays implement (`Comparable`, `CharSequence`, `Number`, `Serializable`, `Cloneable`, ...) | `any` |
| `this` in a class with a single instantiated subclass (leaf classes, objects, lambdas) | `*S_D` directly, so calls and field accesses are static |
| method | a Go function `Impl_C__m(this, args...)`; virtual methods also get forwarding Go methods on each instantiated struct |
| `object O` | one `*S_O_S` in a package variable, created on first use by `Mod_O_S()` under an `rt.InitLock` |
| `@static` field, Java static | package variable, initialized by `Clinit_C()` |
| `new Object()` | `*rt.Object` |
| `null` | a nil interface (or a nil pointer for `String`, arrays, `Class`, `BigInteger`, `BigDecimal`); converted to `any`, a nil pointer becomes a nil `any` |

- In Go terms: a subclass's struct embeds its superclass's struct, so the superclass's fields
  and the Go methods declared on it are promoted (Go: [Struct types][go-struct]). A `*S_C`
  implements `C_C` because its method set, which includes the methods promoted through the
  embedded structs, contains every method of `C_C` (Go: [Method sets][go-methodsets],
  [Implementing an interface][go-implements]). `C_C` has an accessor `A_C() *S_C`, declared on
  `*S_C` and promoted to the subclasses, which returns the embedded `S_C` (fields are accessed
  through it); a trait's interface has a marker method `I_T()` instead. Virtual methods are
  declared on each instantiated struct, as forwarders calling the implementation with the outer
  pointer: a promoted method would receive the embedded struct and lose the object's identity.
- Type tests against classes are Go type assertions on interfaces whose marker methods are
  unique per class, so they are exact (subclasses pass, unrelated classes with the same methods
  do not): Go interfaces are satisfied structurally, and the marker methods make the structure
  name the class.
- **Null.** An interface value is nil only if it has neither a dynamic type nor a value; an
  interface holding a nil pointer is not nil (Go: [Comparison operators][go-compare]). Null
  references of pointer types (`String`, arrays, ...) are therefore converted to `any` through
  `rt.AnyRef`, which gives a nil `any`, and null tests on `any` values use `rt.IsNull` (see the
  `==` deviation in [Language](language.md#equality-and-hashing), where an operand misses this
  conversion).
- Classes that are never instantiated have no struct; references to them are `any`, and only
  `null` passes a cast to them.
- **Object header.** `rt.ObjectHeader` is one atomic pointer (8 bytes) to the object's monitor,
  allocated on first `synchronized`, `wait` or `notify`; it also keeps objects from being
  zero-sized, so distinct objects have distinct addresses. A JVM object header is 12–16 bytes.
- **Monitors.** A monitor is a Go mutex, a condition, an owner goroutine id, a recursion count and
  a wait set. Values without a header (strings, boxes, Go values) get monitors from a global
  table whose entries are never removed. An uncontended `synchronized` costs about 55 ns.
- **Identity hash codes** (`System.identityHashCode`, the default `hashCode`, the default
  `toString`) are derived from the object's address, which never changes (Go's collector does
  not move objects). They are `0` for boxes and non-pointer Go values.

## Traits

| Scala | Go |
|-------|----|
| trait `T` | interface `C_T` with a marker method `I_T()` and the methods called on `T` receivers |
| trait method with a body | a Go function taking `this` as an interface; each class mixing in `T` gets a forwarding Go method to it unless it overrides it |
| trait fields | fields of the classes that mix the trait in (as compiled by Scala) |

Method resolution follows the JVM: class methods win over trait default methods, then the most
specific default method.

## Functions and lambdas

- A lambda is an instance of a synthetic class `Owner$$Lambda$n`: a struct whose fields are
  the captured values, implementing the functional interface's abstract method (and its bridges)
  by calling the method the compiler lifted the lambda body into.
- `FunctionN` values are ordinary objects; arguments and results of generic function types are
  boxed, as on the JVM without specialization.
- **Deviation:** a lambda's `toString` is the function trait's (`<function1>`).

## Case classes, tuples, options and collections

- Case classes are classes with compiler-generated members; nothing in the back end is
  specific to them.
- Tuples are `scala.Tuple1`–`Tuple22` (and `TupleXXL`) with elements of type `Object`, boxed.
  The compiler's specialized tuple classes (`Tuple2$mcII$sp`) are replaced by the generic ones.
- `Option`, `Either`, `Try`, `List`, `Vector`, `Map`, `Set`, ranges, `StringBuilder` and all other
  Scala library types are the library's classes, compiled from its TASTy without change. Their
  elements are boxed.
- `java.util` collections are javalib's Scala implementations, with the JDK's iteration orders.

## Classes implemented natively

The instances of these classes are Go values rather than generated structs ("hijacked", as in
Scala.js), and their methods are Go functions (`linker/Hijacked.scala`, `linker/Natives.scala`):

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
- **Deviation:** `BigInteger` and `BigDecimal` cannot be subclassed.
- `scala.math.BigInt` and `scala.math.BigDecimal` are the Scala library's wrappers around them.
- Static members of `Math`, `StrictMath`, `System`, `Character`, `Integer` and the other boxes,
  `java.util.Arrays` (primitive arrays), `Objects`, `java.lang.reflect.Array`,
  `scala.runtime.BoxesRunTime` and `scala.runtime.Statics` are natives too.

## Enums and opaque types

- A Scala 3 `enum` is a sealed abstract class: singleton cases are instances of anonymous
  subclasses held by the companion object, parameterized cases are case classes, as compiled by
  Scala.
- An `enum` extending `java.lang.Enum` has javalib's `Enum` as superclass (`name`, `ordinal`,
  `compareTo`, identity `equals`); its cases are static fields set when the companion object is
  constructed.
- `scala.Enumeration` values are the library's `Val` objects; javalib's `Enumeration` registers
  their names without reflection.
- An opaque type is represented by its underlying type, with no wrapper.

## Go interop types

These types of `scalago.go` represent Go values directly. Their semantics, and the conversions
at facade calls and exports, are specified in [Go interoperability](interop.md):

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

Elements inside Go data have canonical Scala views (Go `int` is `Long`, `string` is
`GoString`, ...); facades hold Go structs as pointers and pass them to Go interfaces by value
where Go code would; `==` on Go values is Go's `==`, or identity for maps, slices and funcs.
[Go interoperability](interop.md) specifies these rules.

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
