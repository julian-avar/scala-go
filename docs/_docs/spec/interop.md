---
title: Go interoperability
---

# Go interoperability

This page specifies what facade declarations denote, how values cross between Scala and Go, and
what exported Scala code is in Go, in the terms of the [Go specification][go-spec]. How-to:
[Calling Go](../interop/facades.md), [Generated facades](../interop/generated-facades.md),
[Go data](../interop/go-data.md), [Calling Scala from Go](../interop/exports.md). Mechanisms:
[Design](../contributing/design.md#go-interop).

Facade members throw `UnsupportedOperationException` on the JVM. The Go data types (`GoSlice`,
`GoMap`, `GoChan`, `GoPtr`, `GoString`) and `GoData` also run on the JVM, with Go's semantics.

A **facade** is a Scala object, class or trait annotated `@GoImport`, standing for a Go package
or type. The linker checks every facade member a program uses against the real Go declaration
(from the package's export data) and converts values by both the member's Go type and its Scala
type (before erasure, with type aliases followed). A mismatch is a link error at the Scala call
site; unused members are not checked. A **Go value** is a value of a Go type that Scala code
holds in an `any`: a facade reference, a `GoSlice`, a Go `string`, ...

## Facade declarations

### Packages

`@GoImport(path) object P` stands for the Go package `path`, resolved in the generated module
(whose `go.mod` requires the configured modules) and imported under an alias
(`gp_<last path element>`) that cannot clash with generated names.

| Member of `P` | Go entity |
|---------------|-----------|
| `def F(params): R = native` | the function `F`, called as `F(args)` |
| `def V: R = native` (no parameter list) | a read of the variable or constant `V`. A constant is converted by a Go conversion of the constant, which must be representable in the target type (Go: [Representability][go-repr]) |
| `def V_=(x: T): Unit = native` | the assignment `V = x` |
| `def T(x: U): T = native`, `T` a defined type that is not a struct or interface | the conversion `T(x)`, `x` first converted to `T`'s underlying type (Go: [Conversions][go-conversions]) |
| `def T(entries: (K, V)*): T`, `def T(elems: E*): T`, `T` a named map or slice type | a composite literal of `T`: a new map or slice. A key may repeat; the later entry wins |
| class or trait nested in `P` | the defined type of `P` with the class's name ([Types](#types)) |

`@GoName(name)` gives the Go name of a member whose Scala name differs. Several members may stand
for one Go function: one per Scala signature, or per instantiation of a generic function.

### Types

| Declaration | Go type | Facade references hold |
|-------------|---------|------------------------|
| `class T` in `P`, or `@GoImport(path, "T") class C`, for a struct type | the struct type `T` | a `*T` in an `any`; `null` is a nil `*T` |
| the same, for any other non-interface type (`type Level int`) | `T` | a `T` in an `any` |
| `trait T` | the interface type `T` | the interface's dynamic value, in an `any` |
| `trait GoError` (`scalago.go`) | `error` | as above |
| `class T[A, B]` (or `trait`) for a generic type | the instance `T[A', B']` of each use, `A'` the Go type of `A` ([Generic functions](#generic-functions)) | as above |

- Facade classes have no constructor parameters. `new T` is the zero value of `T` (Go:
  [The zero value][go-zero]): for a struct, a pointer to a new zero struct; `new T[A]` of the
  instance. Interface types have no `new`.
- A generic facade's type parameters stand for the Go type's, in order; each use instantiates it
  with the type arguments of its Scala type. Type tests against a generic facade are link errors
  (type arguments are erased); casts to one are unchecked.
- A member without a parameter list is a field, including fields promoted from embedded structs;
  `x_=` assigns it. A member with a parameter list is a method.
- For struct types the held pointer's method set has the methods with receivers `*T` and `T`,
  promoted ones included (Go: [Method sets][go-methodsets]). Other held values are not
  addressable, so a facade of a method with receiver `*T` on them is a link error.
- A Go package may have facade objects in several Scala modules: facades are matched by the Go
  type they stand for, not by Scala class.

## Values across the boundary

A value crossing the boundary is converted by its Scala type `S` and Go type `G`. "To Go"
applies to facade call arguments, assignments of Go variables and fields, and results of Scala
code called by Go (callbacks, Go methods implemented in Scala, exports); "to Scala" to the other
direction. "—" marks a direction that does not occur.

| Scala type `S` | Go type `G` | To Go | To Scala |
|----------------|-------------|-------|----------|
| `Boolean` | `bool` | the value | the value |
| a numeric primitive, or `GoInt`, `GoByte`, ... | any numeric type, also a defined one (`time.Duration`) | `G(x)`: integers sign- or zero-extended then truncated; floats truncated toward zero; out-of-range floats give an implementation-dependent result (Go: [Conversions][go-conversions]) | Go's conversion to `S`'s Go type |
| `String` | `string`, or a defined string type | a new string transcoded from UTF-16; unpaired surrogates become U+FFFD; `null` throws `NullPointerException` | a new string transcoded from UTF-8; invalid bytes become U+FFFD |
| `GoString` (to Scala also `Any`) | `string` | the value | the value |
| `GoComplex` | `complex128`, `complex64` | the value; for `complex64`, each part rounded to `float32` | the value, as a `complex128` |
| a facade of struct type `T` | `T` | a copy of the struct; `null` throws `NullPointerException` | a pointer to a copy |
| a facade of struct type `T` | `*T` | the pointer | the pointer; nil is `null` |
| a facade of another type `T` (also a defined numeric type) | `T` | the value | the value |
| a facade, `Any`, a Scala class | an interface type | see [Interfaces](#interfaces) | the dynamic value; a nil interface is `null` |
| `Array[P]` | `[]G` | shares the array's memory when `P`'s Go type is `G` (`Array[Long]` and `[]int64`), else a new slice of converted elements; `null` is a nil slice | likewise shared, else a new array of converted elements |
| `Seq[E]` | `[]G` | a new slice of converted elements | — |
| `Array[(K, V)]`, `Seq[(K, V)]` | `map[K]V` | a new map | — |
| `GoSlice[E]`, `GoMap[K, V]`, `GoChan[E]`, `GoPtr[E]`, `E` (`K`, `V`) the canonical views of `G`'s element types | a slice, map, channel or pointer type | the value, shared; converted to a defined type with the same underlying type or to a directional channel type (Go: [Assignability][go-assignability]). **Deviation (bug):** a `GoSlice` or `GoMap` for a defined slice or map type is a link error ([Known bugs](../contributing/roadmap.md#known-bugs)) | the value, shared |
| `GoSlice[E]` | an array type `[n]G` | a new array of the slice's first `n` elements; a shorter slice panics | a slice of a copy of the array |
| a function type | a func type, or a defined one | see [Functions](#functions) | a `GoFunction0`–`GoFunction3` |
| `T*` (last parameter) | `...G` | literal arguments one by one; an `Array` or `Seq` passed as `xs*` is a slice passed with `...` | — |
| a tuple | several results | the elements | the results, converted |
| the other results' type | results ending with `error` | see [Errors and panics](#errors-and-panics) | the error is checked |

### Interfaces

A value goes to an interface type `I` (or `any`) as:

| Value | Go interface value |
|-------|--------------------|
| a Scala object | its struct pointer, which implements `I` if the class extends `I`'s facade (always, for `any`). The same object reaches Go as the same interface value and comes back as itself |
| a `String`; a boxed primitive | a Go `string`; the primitive's Go type (`Int` is `int32`, `Long` `int64`, never `int`) |
| a facade of struct type `T` | a copy of the struct (**by value**) if `T`'s method set implements `I` (for `any`: if `T` has no pointer-receiver methods), else the pointer (Go: [Method sets][go-methodsets]). So Go code receives what Go code would pass: a `tea.KeyMsg` in a `tea.Msg`, a `*bytes.Buffer` as an `io.Writer` |
| other Go values | as they are; a value whose type does not implement `I` throws `ClassCastException` |

From Go to Scala the dynamic value is taken as it is. A Go interface holding a nil pointer is not
nil (Go: [Comparison operators][go-compare]), so it is not `null`: an `error` holding a nil
`*MyError` is a non-null `GoError`.

### Functions

- A Scala function passed for a Go func type, or a defined func type (`type Cmd func() Msg`),
  becomes a Go closure that converts its arguments, calls `apply`, converts the result and
  flushes buffered Scala output. `null` is a nil func. Several Go results come from a tuple. If
  the Go results end with `error` and the Scala result type does not, a thrown exception is
  returned as that error.
- A Go func returned to Scala is a `scalago.go.GoFunctionN` (N ≤ 3) that converts with the
  facade's types and throws a trailing non-nil `error`. A nil func is `null`.
- Variadic func types are not supported.

### Named types

- A defined func type is a Scala function type alias (`type Cmd = () => Any`); the converted
  closure is assignable to it (Go: [Assignability][go-assignability]).
- A defined interface type without methods (`type Msg interface{}`) is `Any`.
- A generic func type is a generic function type alias: `iter.Seq[V]` is
  `type Seq[V] = (V => Boolean) => Unit`, so Go iterators are Scala functions taking a yield
  function, and Scala functions are Go iterators.

### Go values in Scala code

A Go value held by Scala code (in an `Any` or a facade type) behaves as follows:

| Operation | Behavior |
|-----------|----------|
| `isInstanceOf[F]`, type patterns | a Go type assertion to `F`'s Go type (Go: [Type assertions][go-assert]); for a struct facade, both `T` and `*T` pass. **Deviation (bug):** a Scala `String` is an instance of every Go interface ([Known bugs](../contributing/roadmap.md#known-bugs)) |
| `asInstanceOf[F]` | as above; `null` passes; a `T` struct value is copied to a new `*T` |
| boxes | a Go value is a Scala box exactly when its dynamic type is `bool`, `uint16`, `int8`, `int16`, `int32`, `int64`, `float32` or `float64`. Go `int`, `uint8`, `uint32`, ... are Go values, not numbers; `GoData.toScala` converts them |
| `==`, `equals` | Go's `==` when the dynamic types are identical and comparable; identity for maps, slices (same array and length), funcs, channels and pointers; `false` for different types |
| `eq` | Go's `==` on the interface values; see [Equality and hashing](language.md#equality-and-hashing) for uncomparable types |
| `hashCode` | consistent with `==`: the address for maps, slices, funcs, channels and pointers; the value for integers; a hash of the bytes for strings |
| `toString` | `Error()` for an `error`, else `String()` for a `fmt.Stringer`, else `<Go type>@<hash in hex>` |
| `getClass.getName` | the Go type (`*strings.Builder`, `int`) |
| `synchronized` | a monitor keyed by the value, or by its address for uncomparable types |

**Deviation (bug):** a Go struct value of an uncomparable type (with a slice, map or func field)
is not `==` to itself; an interface field holding an uncomparable value makes `==` throw
`RuntimeException` ([Known bugs](../contributing/roadmap.md#known-bugs)).

## Generic functions

A generic Go function is called with explicit type arguments (`gp_slices.Max[[]int, int](x)`,
Go: [Instantiations][go-instantiations]), each the Go type of a Scala type:

| Scala type argument | Go type argument |
|---------------------|------------------|
| a primitive, `GoInt`, `GoUint`, ..., `String`, `GoString`, `GoComplex`, `Unit` | its Go type: `Long` is `int64`, `GoInt` is `int`, `String` is `string`, `Unit` is `struct{}` |
| `GoSlice[E]`, `GoMap[K, V]`, `GoChan[E]`, `GoPtr[E]` | `[]E'`, `map[K']V'`, `chan E'`, `*E'` |
| a facade | the Go type it stands for; `*T` for a struct type `T` |
| `Any`, `GoError` | `any`, `error` |
| a function type | the func type of its Go parameter and result types |

A facade member is generic (`def Max[E](x: GoSlice[E]): E`) or monomorphic
(`def Max(x: GoSlice[GoInt]): GoInt`). A Go type parameter is bound, in order of preference, by:

1. the Scala type argument of the member's type parameter of the same name (given or inferred);
2. its constraint's core type, if any (`S ~[]E` is `[]int`, Go: [Core types][go-core]);
3. unifying the facade's Scala types with the Go declaration's: parameters that are not funcs
   (Go data must match exactly, being shared), then funcs, then results.
   `Map(xs []T, f func(T) U)` with `GoSlice[GoInt]` and `Long => GoString` is
   `Map[int, string]`; the function's argument converts.

A type parameter none of these binds is a link error. Values of a member's type parameter types
are unboxed and boxed at the call. A type parameter constrained by an interface may be declared
as that interface (`k: fmt.Stringer` for `K fmt.Stringer`), which satisfies the constraint.
Generic facade members need a parameter list. A type argument `Nothing` or `Null`, or one Scala
inferred for a type parameter no value parameter uses, is a compile error.

Go checks type arguments against constraints when it compiles the generated code (Go:
[Satisfying a type constraint][go-satisfy]). **Deviation (bug):** the linker does not, so
`slices.Max[Any]` is a Go compile error, not a link error
([Known bugs](../contributing/roadmap.md#known-bugs)).

## Types Scala code cannot name

Values of types code outside their package cannot name (unexported, such as `binary.BigEndian`'s
`bigEndian`, or in `internal` packages; Go: [Exported identifiers][go-exported]) are held as they
are, typed `Any` or as an interface they implement. Calls whose parameters or results have such
types go through Go's reflection, which converts each argument to its parameter's type; an
argument of another type throws `ClassCastException`. Fields of such types are read-only.

## Calls

A facade call evaluates the receiver and arguments left to right, flushes buffered Scala output
(keeping it ordered with what Go writes), then makes the Go call with the converted values. An
assignment to a Go field or variable is the same, with the assigned value as the argument.

## Errors and panics

| Situation | Behavior |
|-----------|----------|
| a Go function's results end with `error`, and the facade's result type leaves it out | a non-nil error is thrown as a `scalago.go.GoException` wrapping it |
| the facade's result type keeps it (`(Boolean, GoError)`, or `GoError`) | the error is returned; `nil` is `null` |
| Go code panics during a facade call | the panic unwinds through Scala frames as an exception: a `runtime.Error` as in [Exceptions](language.md#exceptions), any other value a `RuntimeException` with its text. A Scala `try` catches it |
| Scala code called by Go throws, and the Go results end with an `error` the Scala result type leaves out (exports: the method has `@throws`) | the exception becomes the error: a `GoException` gives back its error, any other exception is an `*rt.ScalaError` whose `Error()` is its `toString` |
| the same without an `error` result | the exception unwinds through Go frames as a panic whose value is an `error` describing it, which Go code may `recover`. Back in Scala code on the same goroutine it is the exception itself. Unrecovered on a goroutine Go code started, it ends the program (Go's report, exit status 2, no uncaught-exception handlers) |
| Go code panics below a Scala callback and Go code above the callback recovers | the panic passes through the Scala frames; `recover` returns the original value |

Panics run deferred Go functions and Scala `finally` blocks in stack order (Go:
[Handling panics][go-panics]).

## Go interfaces implemented in Scala

A Scala class extending a facade trait implements the Go interface (Go:
[Implementing an interface][go-implements]): each instantiated struct of the class gets a Go
method per interface method, with its Go name and signature, that converts the arguments, calls
the Scala method virtually and converts the result (a tuple for several results, a thrown
exception for a trailing `error`). The linker checks each method against the Go declaration.
Interfaces with unexported methods (Go: [Uniqueness of identifiers][go-unique]), and variadic or
generic methods, cannot be implemented in Scala.

## Go data

`GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]` and `GoPtr[T]` are opaque types whose values are Go
slices, maps, channels and pointers, shared with Go without copying; `GoString` is Go's
`string`. Elements are read and written in **canonical views**, which depend on the Go element
type alone:

| Go element type | Canonical Scala view (type when creating) |
|-----------------|-------------------------------------------|
| `int`, `int64`, `uint`, `uint64`, `uintptr` | `Long` (`GoInt`, `GoUint`, `GoUint64`, `GoUintptr`) |
| `int32`, `uint32` | `Int` (`GoUint32`) |
| `int16`, `uint16`, `int8`, `uint8` | `Short`, `Char`, `Byte` (`GoByte`) |
| `float32`, `float64`, `bool` | `Float`, `Double`, `Boolean` |
| `string`, `complex128`, `struct{}` | `GoString`, `GoComplex`, `Unit` |
| a struct type | its facade (reading copies the struct to a new pointer; writing copies it back) |
| an interface type | `Any`, or its facade |
| anything else | the Go value: facades, `GoSlice`, `GoMap`, ... |

Unsigned values are kept bit for bit: a `uint64` above `Long.MaxValue` reads as a negative
`Long`. A non-canonical view (`GoSlice[String]` for a `[]string`) is a link error naming the
right one (`GoSlice[GoString]`). Creating Go data takes the Go element type from a `GoType[T]`
instance: `Int` is `int32`, `Long` `int64`, `GoInt` `int`, `GoString` `string`, `Any` `any`, ...

| Operation | Go semantics |
|-----------|--------------|
| `GoSlice.make[T](n)`, `make(n, cap)` | `make([]T, n, cap)`: `n` zero values |
| `s(i)`, `s(i) = x` | indexing; out of range throws `ArrayIndexOutOfBoundsException` |
| `slice(from, until)` | `s[from:until]`, sharing the array |
| `appended`, `appendedAll`, `copyFrom` | `append` (reuses the array while the capacity allows), `copy` (Go: [Appending and copying slices][go-append]) |
| `GoMap.make` | `make(map[K]V)` |
| `m.get(k)`, `m(k) = v`, `remove(k)` | indexing, assignment, `delete`. Keys compare with Go's `==`: a `NaN` key is never found, `-0.0` equals `0.0`; a key of uncomparable dynamic type panics |
| iteration | Go's order, unspecified and different each time |
| store into a nil map | `UnsupportedOperationException`; reading a nil map finds nothing |
| `GoChan.make[T](cap)` | `make(chan T, cap)`; a negative capacity throws `IllegalArgumentException` |
| `send`, `receive()` | block the thread, flush buffered output first, and are interrupted by `Thread.interrupt` (`InterruptedException`). `receive()` is `None` once the channel is closed and drained. On a nil channel they block forever (interruptibly) |
| `trySend`, `tryReceive` | non-blocking (a `select` with `default`) |
| `close()` | closing a closed or nil channel, or sending on a closed one, throws `RuntimeException` (Go: [Close][go-close]) |
| `GoPtr.alloc[T]`, `GoPtr.to(x)` | `new(T)`; a pointer to a copy of `x` |
| `get`, `set` | `*p`, `*p = x`; nil throws `NullPointerException` |
| `isNil` | `== nil` (a nil Go value is not `null`) |
| any operation on `null` | `NullPointerException` |

On the JVM every operation has the same result, except that maps iterate in insertion order.

A channel send is synchronized before the corresponding receive completes (Go:
[Channel communication][go-mem-chan]), so sent values are safely published. Other Go data has no
synchronization of its own. **Deviation:** a `GoMap` written concurrently with other uses ends
the process with Go's fatal error `concurrent map writes`, which no `catch` intercepts, not a
`ConcurrentModificationException`.

### GoData

`GoData(x)` copies Scala data to plain Go data, the values Go code inspecting values by
reflection expects. Both directions copy; identity is not kept.

| Scala | `GoData(x)` |
|-------|-------------|
| strings | Go `string` |
| integers | Go `int` (the default type of Go's integer constants) |
| other numbers, `Boolean`, Go values | unchanged |
| `Option` | its value, or `nil` |
| maps, case classes | `map[string]any` (case classes by `productElementNames`) |
| other collections, arrays | `[]any` |

`GoData.toScala` copies back: Go strings to `String`, integers to `Long`, floats to `Double`,
slices and arrays to `Vector`, maps to `Map[String, Any]` sorted by key. Only values of Go's
predeclared types are data: a `time.Duration` stays a Go value.

## Exports

A library link (`--library true`) turns `@GoExport` definitions into the Go API of the output
module's root package:

| Scala | Go declaration |
|-------|----------------|
| `@GoExport def f(params): R` in a top-level `object` | `func F(params) R`; the name is capitalized unless given |
| the same, with `@throws[E]` | `func F(params) (R, error)` |
| `@GoExport class C(params)` | `type C = scala.X_C`, an alias of an interface type with the class's `@GoExport` methods and those of exported superclasses; `func NewC(params) C` for the primary constructor |
| `@GoExport def m(...)` in an exported class | an interface method, calling the Scala method virtually |

- Go types follow the Scala types: `Int` is `int32`, `Long` `int64`, `GoInt` `int`, `String`
  and `GoString` `string`, `Array[T]` and `GoSlice[T]` `[]T`, `GoError` `error`; `GoMap`,
  `GoChan`, `GoPtr` and facades their Go types; exported classes their interfaces; functions Go
  funcs. Conversions are those above.
- An exported object crosses as itself, so `==` on it in Go is identity.
- Buffered Scala output is flushed when each call returns.
- Modules and static initializers run on first use; Go package initialization only creates
  literals.
- A Go package that the library imports through a facade cannot import the library (Go:
  [Import declarations][go-import]).
- Exported traits, secondary constructors and generic methods are compile errors; tuple results
  are link errors. **Deviation (bug):** `@GoExport` on a field or `val` is ignored, and overloads
  exported under one Go name give Go code that does not compile
  ([Known bugs](../contributing/roadmap.md#known-bugs)).

## Goroutines and threads

Go code may call Scala code from any goroutine, concurrently. Such a goroutine gets a `Thread`
the first time it needs one (`Thread.currentThread`, a monitor, a `ThreadLocal`), kept while it
runs; it is not a non-daemon thread, so the program does not wait for it. Scala and Go code
synchronize through the edges of the [Go memory model][go-mem]: a value passed in a call is
visible to the callee; values shared across goroutines need channels, `sync` or atomics on the
Go side and the corresponding constructs on the Scala side
([Runtime: memory model](runtime.md#memory-model)).

[go-spec]: https://go.dev/ref/spec
[go-mem]: https://go.dev/ref/mem
[go-mem-chan]: https://go.dev/ref/mem#chan
[go-append]: https://go.dev/ref/spec#Appending_and_copying_slices
[go-assert]: https://go.dev/ref/spec#Type_assertions
[go-assignability]: https://go.dev/ref/spec#Assignability
[go-close]: https://go.dev/ref/spec#Close
[go-compare]: https://go.dev/ref/spec#Comparison_operators
[go-conversions]: https://go.dev/ref/spec#Conversions
[go-core]: https://go.dev/ref/spec#Core_types
[go-exported]: https://go.dev/ref/spec#Exported_identifiers
[go-implements]: https://go.dev/ref/spec#Implementing_an_interface
[go-import]: https://go.dev/ref/spec#Import_declarations
[go-instantiations]: https://go.dev/ref/spec#Instantiations
[go-methodsets]: https://go.dev/ref/spec#Method_sets
[go-panics]: https://go.dev/ref/spec#Handling_panics
[go-repr]: https://go.dev/ref/spec#Representability
[go-satisfy]: https://go.dev/ref/spec#Satisfying_a_type_constraint
[go-unique]: https://go.dev/ref/spec#Uniqueness_of_identifiers
[go-zero]: https://go.dev/ref/spec#The_zero_value
