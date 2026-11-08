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

- Values of primitive types are unboxed in fields, locals, parameters, results and primitive
  arrays.
- **Boxing.** A boxed primitive is the Go value stored in an `any`; the box class is determined
  by the Go type (`int32` is an `Integer`, `uint16` a `Character`). Converting to `any` is Go's
  interface conversion, which allocates unless the value is small (Go keeps the values 0–255 in a
  static table). `Integer.valueOf`, `BoxesRunTime.boxToInteger` and implicit boxing are the same
  conversion.
- **Unboxing** is a Go type assertion; unboxing `null` gives the zero value, as on the JVM.
- **Deviation:** boxes have no identity. Two boxes of the same type and value are `eq`, their
  `System.identityHashCode` is `0`, and they share one monitor.

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

- Type tests against classes are Go type assertions on interfaces whose marker methods are
  unique per class, so they are exact (subclasses pass, unrelated classes with the same methods
  do not).
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

These types of `scalago.go` represent Go values directly (see the design document's Go interop
section for the conversion rules at facade calls):

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

- **Canonical element views.** Inside Go data, elements have one Scala representation decided by
  the Go element type: `int`, `int64`, `uint`, `uint64`, `uintptr` are `Long`; `int32`, `uint32`
  are `Int`; `int16`, `uint16`, `int8`, `uint8` are `Short`, `Char`, `Byte`; `float32`, `float64`,
  `bool` are `Float`, `Double`, `Boolean`; `string` is `GoString`; struct values are facades (a
  pointer to a copy); everything else is the Go value. The linker rejects other views.
- **Structs: values and pointers.** Facades hold Go structs as pointers. A type test against a
  struct facade accepts both `T` and `*T`; a cast of a `T` value copies it to a new pointer.
  When a struct is passed to a Go interface (or `any`), it goes by value if the value's method set
  implements the interface (for `any`: if the type has no pointer-receiver methods), otherwise as
  the pointer, so Go code receives what Go code would have passed.
- **Equality and strings.** `==` on Go values is Go's `==`, or identity for maps, slices and funcs;
  `toString` uses `Error()` or `String()` when the value has one; `getClass.getName` is the Go
  type (`*strings.Builder`).
- `new T` of a facade class is Go's zero value of `T`.
- **Deviation:** unsynchronized concurrent writes to a `GoMap` can end the process with Go's
  fatal error `concurrent map writes`, which no `catch` intercepts.
