---
title: Go interoperability
---

# Go interoperability

This page specifies how Scala code and Go code use each other: what facade declarations denote,
how values cross the boundary, and what exported Scala code is in Go. It is stated in the terms
of the [Go specification][go-spec] (types, assignability, conversions, method sets) and the
[Go memory model][go-mem]. The user guide shows how to use these features:
[Calling Go](../interop/facades.md), [Generated facades](../interop/generated-facades.md),
[Go data](../interop/go-data.md), [Calling Scala from Go](../interop/exports.md).

Interop is Go-only: facade members throw `UnsupportedOperationException` on the JVM. The Go
data types (`GoSlice`, `GoMap`, `GoChan`, `GoPtr`, `GoString`) and `GoData` also have a JVM
implementation with Go's semantics.

## Terms

| Term | Meaning |
|------|---------|
| facade | a Scala declaration annotated `@GoImport` (an object, class or trait) and its members, standing for a Go package or type |
| Go value | a value of a Go type held by Scala code, in an `any`: a facade reference, a `GoSlice`, a Go `string`, ... |
| Scala type of a member | the type the facade declares, before erasure (the plugin records it in the IR), with type aliases followed |
| Go type of a member | the type of the real Go declaration, read by the linker from the package's export data (`goapi`) |
| canonical view | the one Scala type that stands for a Go type inside Go data (see [Go data](#go-data)) |

The linker never trusts a facade: every facade member a program uses is checked against the Go
declaration, and conversions are chosen from both types. A mismatch is a link error at the Scala
call site. Unused members are not checked.

## Facade declarations

### Packages

`@GoImport(path) object P` stands for the Go package with import path `path`. The generated code
imports it under an alias (`gp_<last path element>`, numbered on clashes), so its name never
clashes with generated identifiers (Go: [Import declarations][go-import]). The package is
resolved in the generated Go module, whose `go.mod` requires the configured modules.

| Member of `P` | Go entity | Use |
|---------------|-----------|-----|
| `def F(params): R = native` | the package-level function `F` (Go: [Function declarations][go-funcs]) | a call `gp.F(args)` |
| `def V: R = native` (no parameter list) | the package-level variable or constant `V` (Go: [Variable declarations][go-vars], [Constant declarations][go-consts]) | a read of `gp.V`. A constant is converted with a Go conversion of the constant, which must be representable in the target type (Go: [Representability][go-repr]) |
| `def V_=(x: T): Unit = native` | assignment to the variable `V` | `gp.V = x` (Go: [Assignment statements][go-assign-stmt]) |
| `def T(x: U): T = native`, `T` a defined type that is not a struct or interface | the conversion `T(x)` (Go: [Conversions][go-conversions]) | `x` is converted to `T`'s underlying type first |
| `def T(entries: (K, V)*): T`, `def T(elems: E*): T`, `T` a named map or slice type | a composite literal of `T` (Go: [Composite literals][go-composite]) | a new map or slice; unlike a Go map literal, a key may repeat, and the later entry wins |
| class or trait nested in `P` | the defined type of `P` with the class's name | see [Types](#types) |

`@GoName(name)` gives the Go name of a member whose Scala name differs; several members may
stand for one Go function (one per instantiation of a generic function, or per Scala signature).

### Types

| Declaration | Go type | Facade references hold |
|-------------|---------|------------------------|
| `class T` in `P`, or `@GoImport(path, "T") class C`, for a struct type | the defined struct type `T` (Go: [Type definitions][go-typedefs]) | a `*T` in an `any`; `null` is a nil `*T` |
| the same, for any other non-interface type (`type Values map[string][]string`, `type Level int`) | `T` | a `T` in an `any` |
| `trait T` | the interface type `T` (Go: [Interface types][go-interfaces]) | the interface's dynamic value, in an `any` |
| `trait GoError` (in `scalago.go`) | the predeclared `error` | as above |

- Facade classes have no constructor parameters and facade members no type parameters (the
  plugin checks this). `new T` is Go's zero value of `T` (Go: [The zero value][go-zero]): a
  pointer to a new zero struct, or the zero `T`. Interface types have no `new`.
- A member of a facade class without a parameter list is a field (Go: [Selectors][go-selectors]),
  including fields promoted from embedded structs; `x_=` assigns it. A member with a parameter
  list is a method.
- Methods are called on the held value: for struct types on the pointer, whose method set holds
  the methods declared with receiver `*T` and with receiver `T` (Go: [Method sets][go-methodsets]),
  including promoted ones. For other types the held value is not addressable, so only methods
  with receiver `T` can be called (Go: [Calls][go-calls]).
  **Deviation (bug):** the linker checks such calls against the method set of `*T`, so calling
  a method with a pointer receiver on a non-struct type is not a link error; the generated code
  does not compile (`cannot call pointer method UnmarshalText on slog.Level`).
- A Go package may have facade objects in several Scala modules. Facades are matched by the Go
  type they stand for, not by Scala class.

## Values across the boundary

A value crossing the boundary is converted according to its Scala type `S` and the Go type `G`.
Scala-to-Go conversions apply to the arguments of facade calls, to the results of Scala code
called by Go (callbacks, Go methods implemented in Scala, exports) and to assignments of Go
variables and fields; Go-to-Scala conversions to the other direction.

### Scala to Go

| Scala type `S` | Go type `G` | Go value |
|----------------|-------------|----------|
| `Boolean` | `bool` | the value |
| a numeric primitive (`Int`, `Long`, `Double`, ...), or `GoInt`, `GoByte`, ... | any numeric type | `G(x)`, Go's numeric conversion: integers are sign- or zero-extended then truncated, floating-point values truncated toward zero, and out-of-range floating-point values give an implementation-dependent result (Go: [Conversions][go-conversions]) |
| a numeric primitive | a defined numeric type (`time.Duration`) | `G(x)` |
| `String` | `string`, or a defined string type | the UTF-16 text transcoded to UTF-8 (a new Go string); unpaired surrogates become U+FFFD |
| `GoString` | `string` | the value |
| a facade of struct type `T` | `T` | a copy of the struct (`*p`); `null` throws `NullPointerException` |
| a facade of struct type `T` | `*T` | the pointer |
| a facade of another type `T` | `T` | the value |
| a facade, or `Any` | an interface type `I` (or `any`) | see [Interfaces](#interfaces) |
| a Scala class | an interface type it implements | the object (see [Go interfaces implemented in Scala](#go-interfaces-implemented-in-scala)) |
| `String` | `any` | a Go `string` |
| a primitive | `any` | the primitive's Go type in the interface (`Int` is `int32`, `Long` is `int64`): not `int` |
| `Array[P]` | `[]G` | shares the array's memory when `P`'s Go type is `G` (`Array[Long]` and `[]int64`, `Array[Byte]` and `[]byte`); otherwise a new slice of converted elements. `null` is a nil slice |
| `Seq[E]` | `[]G` | a new slice of converted elements |
| `Array[(K, V)]`, `Seq[(K, V)]` | `map[K]V` | a new map |
| `GoSlice[E]`, `GoMap[K, V]`, `GoChan[E]`, `GoPtr[E]` | a slice, map, channel or pointer type | the value, shared, if `E` (`K`, `V`) is the canonical view of `G`'s element type; converted when `G` is a defined type with the same underlying type or a directional channel type (Go: [Assignability][go-assignability], [Conversions][go-conversions]) |
| `(A, B) => C` | `func(A', B') C'`, or a defined func type | see [Functions](#functions) |
| `T*` (last parameter) | `...G` | literal arguments are passed one by one; an `Array` or `Seq` passed as `xs*` is converted to a slice and passed with `...` (Go: [Passing arguments to `...` parameters][go-variadic]) |

A Scala `String` argument that is `null` throws `NullPointerException` when transcoded: Go's
`string` has no null.

### Go to Scala

| Go type `G` | Scala type `S` | Scala value |
|-------------|----------------|-------------|
| `bool` | `Boolean` | the value |
| a numeric type | a numeric primitive | `S`'s Go type applied to the value: Go's numeric conversion, as above |
| a defined numeric type | a numeric primitive, or its facade | the number, or the Go value |
| `string`, or a defined string type | `String` | a new string, transcoded from UTF-8 (invalid bytes become U+FFFD) |
| `string` | `GoString` or `Any` | the Go string |
| struct type `T` | a facade of `T` | a pointer to a copy of the value |
| `*T`, `T` a struct type | a facade of `T` | the pointer; nil is `null` |
| another defined type `T` | a facade of `T` | the value |
| an interface type | `Any` or a facade | the interface's dynamic value; a nil interface is `null` |
| `[]E` | `Array[S]` | shares the memory when `E` is `S`'s Go type, else a new array of converted elements |
| a slice, map, channel or pointer type | `GoSlice`, `GoMap`, `GoChan`, `GoPtr` of its canonical views | the value, shared |
| a func type | a Scala function type | a `GoFunction0`–`GoFunction3` (see [Functions](#functions)) |
| several results | a tuple | the results, converted |
| results ending with `error` | the other results' type | the error is checked (see [Errors and panics](#errors-and-panics)) |

### Interfaces

A Go interface value holds a dynamic type and value (Go: [Interface types][go-interfaces]). Values
go to an interface type `I` (or `any`) as follows:

- A Scala object goes as itself: its struct pointer, which implements `I` if the class extends
  `I`'s facade (or, for `any`, always). Identity is kept: the same object reaches Go as the same
  interface value, and comes back as itself.
- A Scala `String` goes to `any` as a Go `string`; a boxed primitive as its Go primitive.
- A facade of struct type `T` (held as `*T`) goes **by value** if the value's method set
  implements `I`, and as the pointer otherwise (`rt.ToGoInterface`, `rt.ToGoAny`). For `any`, it
  goes by value if `T` has no methods with a pointer receiver, so that `*T`'s method set is `T`'s
  (Go: [Method sets][go-methodsets], [Implementing an interface][go-implements]). Go code then
  receives what Go code would have passed: a `tea.KeyMsg` in a `tea.Msg`, a `*bytes.Buffer` as
  an `io.Writer`. Passing by value copies the struct (Go: [Assignability][go-assignability]).
  The decision depends on the type alone and is cached per type.
- Other Go values go as they are; a value whose type does not implement `I` throws
  `ClassCastException` (a failed type assertion).

In the other direction an interface's dynamic value is taken as it is. A Go interface holding a
nil pointer is not nil (Go: [Comparison operators][go-compare]), so it is not `null` in Scala
either: an `error` result holding a nil `*MyError` is a non-null `GoError`, as `err != nil` is
true in Go.

### Functions

- A Scala function passed for a Go func type, or a defined func type (`type Cmd func() Msg`,
  assignable from the unnamed func type of the same signature, Go:
  [Assignability][go-assignability]), becomes a Go closure that converts its arguments, calls the
  function's `apply` and converts the result. `null` is a nil func. A Go result list that ends
  with `error` while the Scala result type does not have one makes the closure return the
  exception the function throws as that error; several Go results come from a tuple. After each
  call buffered Scala output is flushed.
- A Go func returned to Scala is a `scalago.go.GoFunctionN` (N ≤ 3) that converts arguments and
  results with the facade's types and throws a trailing non-nil `error`. A nil func is `null`.
- Variadic and generic func types are not supported.

### Named types

- A defined type whose underlying type is a func type is a Scala function type; FacadeGen
  declares it as a type alias (`type Cmd = () => Any`). Go accepts the converted closure because
  the types' underlying types are identical and one of them is not named.
- A defined interface type without methods (`type Msg interface{}`) is `Any`: every value
  implements it.
- A conversion to a defined type and a composite literal of a named slice or map type are
  package members (see [Packages](#packages)).
- `GoSlice`, `GoMap` and `GoChan` values passed for a defined slice, map or channel type are
  converted with Go's conversion between types with identical underlying types; a bidirectional
  channel is converted to a directional one.

### Go values in Scala code

A Go value held by Scala code (in an `Any` or a facade type) behaves as follows:

| Operation | Behavior |
|-----------|----------|
| `isInstanceOf[F]`, type patterns | a Go type assertion to `F`'s Go type (Go: [Type assertions][go-assert]); for a struct facade, both `T` and `*T` pass |
| `asInstanceOf[F]` | as above; `null` passes; a `T` struct value is copied to a new `*T` |
| boxes | a Go value is a Scala box exactly when its dynamic type is `bool`, `uint16`, `int8`, `int16`, `int32`, `int64`, `float32` or `float64` (the Go types of Scala's primitives). Go `int`, `uint8`, `uint32`, ... are Go values, not numbers: `GoData.toScala` converts them |
| `==`, `equals` | Go's `==` when the dynamic types are identical and comparable; identity for maps, slices (same array and length), funcs, channels and pointers; `false` for different types |
| `eq` | Go's `==` on the interface values. **Deviation (bug):** for types Go cannot compare it panics (see [Language](language.md#equality-and-hashing)) |
| `hashCode` | consistent with `==`: the address for maps, slices, funcs, channels and pointers, the value for integers, a hash of the bytes for strings |
| `toString` | `Error()` if the value is an `error`, else `String()` if it has one (`fmt.Stringer`), else `<Go type>@<hash in hex>` |
| `getClass.getName` | the Go type (`*strings.Builder`, `int`) |
| `synchronized` | a monitor from the global table, keyed by the value, or by the address for uncomparable types |

**Deviation (bug):** a Go struct value of a type that Go cannot compare (a struct with a slice,
map or func field) is not `==` to itself, and one whose comparable type has an interface field
holding an uncomparable value makes `==` throw `RuntimeException` (Go's run-time panic). (From
the implementation, `rt.foreignEquals`.)

## Generic functions

Generic Go functions are called through monomorphic facades: one facade member per
instantiation, with concrete Scala types. The Go types of the declaration that mention type
parameters are replaced by the Go types of the facade's Scala types (`GoSlice[GoInt]` is `[]int`);
the arguments are converted to those types, and the call writes no type arguments, so Go infers
them from the arguments (Go: [Type inference][go-inference]). Every type parameter must
therefore be inferable from the arguments' types.

- A type parameter constrained by an interface (`func Matches[K fmt.Stringer](k K, ...)`) is
  given the interface type: the argument is converted to the interface, which satisfies the
  constraint because an interface type implements itself (Go:
  [Satisfying a type constraint][go-satisfy]); `K` is inferred as the interface type.
- Constraints with type sets (`~int | string`, `comparable`) have no such type; FacadeGen leaves
  these functions out, and handwritten facades must name a type in the set.
- Generic Go types are not supported.

## Calls

A facade call evaluates the receiver and the arguments left to right into Go variables, flushes
buffered Scala output (so that it stays ordered with what Go code writes), then makes the Go
call with the converted values. An assignment to a Go field or variable is the same with the
assigned value as the argument.

## Errors and panics

| Situation | Behavior |
|-----------|----------|
| a Go function's results end with `error`, and the facade's result type leaves it out | a non-nil error is thrown as `scalago.go.GoException`, whose `error` is the Go error |
| the facade's result type keeps it (`(Boolean, GoError)`, or `GoError` alone) | the error is returned; `nil` is `null` |
| Go code panics during a facade call | the panic unwinds through the Scala frames as an exception: a `runtime.Error` becomes the Java exception of [Language](language.md#exceptions), any other value a `RuntimeException` with the value's text. A Scala `try` catches it (with `recover`, Go: [Handling panics][go-panics]) |
| Scala code called by Go (a callback, a Go method implemented in Scala, an export) throws, and the Go result list ends with `error` that the Scala result type leaves out (for exports: the method has `@throws`) | the exception becomes the error (`rt.RecoverAsError`): a `GoException` gives back the error it wraps, any other exception is an `*rt.ScalaError` whose `Error()` is the exception's `toString` |
| the same without an `error` result | the exception unwinds through the Go frames as a Go panic whose value (`*rt.Exception`) is an `error` describing it. Go code may `recover` it. If the Go code was called by Scala code on the same goroutine, the exception reaches that Scala code as itself. If nothing recovers it (on a goroutine that Go code started), the program ends with Go's panic report and exit status 2, without Scala's uncaught-exception handlers |

Deferred Go functions run as the panic passes through Go frames, and `finally` blocks run as it
passes through Scala frames, in stack order.

## Go interfaces implemented in Scala

A Scala class extending a facade trait implements the Go interface: each instantiated struct of
the class gets, for each method of the interface, a Go method with the Go name and the Go
signature, which converts the arguments to Scala, calls the Scala method virtually and converts
the result (several Go results from a Scala tuple, a trailing `error` from an exception, see
above). The struct pointer's method set then contains the interface's methods, so it implements
the interface (Go: [Implementing an interface][go-implements]). The linker checks each method
against the Go interface's declaration.

- Methods of Go interfaces are exported names; interfaces with unexported methods cannot be
  implemented outside their package (Go: [Uniqueness of identifiers][go-unique]), so not in Scala.
- Variadic and generic interface methods cannot be implemented in Scala.

## Go data

`GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]` and `GoPtr[T]` are opaque types whose values are Go
slices, maps, channels and pointers, shared with Go code without copying. `GoString` is Go's
`string`. Elements are read and written in their **canonical views**, decided by the Go element
type alone:

| Go element type | Canonical Scala view |
|-----------------|----------------------|
| `int`, `int64`, `uint`, `uint64`, `uintptr` | `Long` (`GoInt`, `GoUint`, `GoUint64`, `GoUintptr` when creating) |
| `int32`, `uint32` | `Int` (`GoUint32`) |
| `int16`, `uint16`, `int8`, `uint8` | `Short`, `Char`, `Byte` (`GoByte`) |
| `float32`, `float64`, `bool` | `Float`, `Double`, `Boolean` |
| `string` | `GoString` |
| a struct type | its facade (reading copies the struct to a new pointer; writing copies it back) |
| an interface type | `Any`, or its facade |
| anything else | the Go value: facades, `GoSlice`, `GoMap`, ... |

Unsigned values are kept bit for bit (a `uint64` above `Long.MaxValue` reads as a negative
`Long`). A facade whose Go data type does not use canonical views (`GoSlice[String]` for a
`[]string`) is a link error that names the right view (`GoSlice[GoString]`).

Creating Go data needs the Go element type, given by a `GoType[T]` instance: `Int` is `int32`,
`Long` is `int64`, `GoInt` is `int`, `GoString` is `string`, `Any` is `any`, and so on.

| Operation | Go semantics |
|-----------|--------------|
| `GoSlice.make[T](n)`, `make(n, cap)` | `make([]T, n, cap)`: `n` zero values (Go: [Making slices, maps and channels][go-make]) |
| `s(i)`, `s(i) = x` | index expressions (Go: [Index expressions][go-index]); an index out of range throws `ArrayIndexOutOfBoundsException` |
| `slice(from, until)` | `s[from:until]`, sharing the array (Go: [Slice expressions][go-slices]) |
| `appended`, `appendedAll`, `copyFrom` | `append` (which reuses the array while the capacity allows) and `copy` (Go: [Appending and copying slices][go-append]) |
| `GoMap.make` | `make(map[K]V)` |
| `m.get(k)`, `m(k) = v`, `remove(k)` | index expressions, assignment and `delete` (Go: [Deletion of map elements][go-delete]); keys compare with Go's `==`, so a `NaN` key is never found and `-0.0` equals `0.0`. A key whose dynamic type is not comparable panics |
| iteration | Go's order, which is unspecified and differs between iterations (Go: [For statements with range clause][go-range]) |
| store into a nil map | `UnsupportedOperationException` (Go: a run-time panic); reading a nil map finds nothing |
| `GoChan.make[T](cap)` | `make(chan T, cap)`; a negative capacity throws `IllegalArgumentException` |
| `send`, `receive()` | send and receive (Go: [Send statements][go-send], [Receive operator][go-recv]); they block the thread, flush buffered output first, and are interrupted by `Thread.interrupt` (`InterruptedException`). `receive()` is `None` once the channel is closed and drained |
| `trySend`, `tryReceive` | non-blocking (a `select` with `default`) |
| `close()` | `close`; closing a closed or nil channel, or sending on a closed one, throws `RuntimeException` (Go's run-time panic) (Go: [Close][go-close]) |
| send and receive on a nil channel | block forever (interruptibly), as in Go |
| any operation on `null` | `NullPointerException` (a nil Go value is not `null`: `isNil` tells) |
| `GoPtr.alloc[T]`, `GoPtr.to(x)` | `new(T)` (Go: [Allocation][go-new]), and a pointer to a copy of `x` |
| `get`, `set` | `*p`, `*p = x`; nil throws `NullPointerException` |
| `isNil` | `== nil` |

Channel operations are Go's synchronization: a send is synchronized before the corresponding
receive completes ([Go memory model: channel communication][go-mem-chan]), so values sent from
one thread are safely published to the receiving one. Other Go data has no synchronization of
its own: concurrent use needs `synchronized`, a lock or a channel, and a `GoMap` written
concurrently with other uses ends the process with Go's fatal error `concurrent map writes`,
which no `catch` intercepts. **Deviation:** this is not a `ConcurrentModificationException`.

On the JVM the same operations have the same results, except that iteration over a `GoMap` is in
insertion order.

### GoData

`GoData(x)` copies Scala data to plain Go data, the values that Go code inspecting values by
reflection expects: strings become Go `string`s, Scala integers Go `int`s (the default type of
Go's integer constants, Go: [Constants][go-constants]), other numbers and `Boolean`s stay,
`Option` its value or `nil`, maps and case classes `map[string]any` (case classes by
`productElementNames`), other collections and arrays `[]any`; Go values stay as they are.
`GoData.toScala` copies back: Go strings to `String`, integers to `Long`, floating-point numbers
to `Double`, slices and arrays to `Vector`, maps to `Map[String, Any]` sorted by key; only Go's
predeclared types are data (a `time.Duration` stays a Go value). Both directions copy; identity
is not kept.

## Exports

A library link (`--library true`) turns `@GoExport` definitions into the Go API of the output
module's root package:

| Scala | Go declaration |
|-------|----------------|
| `@GoExport def f(params): R` in a top-level `object` | `func F(params) R` (Go: [Function declarations][go-funcs]); the name is capitalized, so exported (Go: [Exported identifiers][go-exported]), unless given |
| the same, with `@throws[E]` | `func F(params) (R, error)` |
| `@GoExport class C(params)` | `type C = scala.X_C`, an alias (Go: [Alias declarations][go-alias]) of an interface type whose methods are the class's `@GoExport` methods (and those of exported superclasses); `func NewC(params) C` for the primary constructor |
| `@GoExport def m(...)` in an exported class | a method of the interface, declared on every instantiated struct of the class and its subclasses, calling the Scala method virtually |

- Go types are chosen from the Scala types: `Int` is `int32`, `Long` `int64`, `GoInt` `int`,
  `String` and `GoString` `string`, `Array[T]` and `GoSlice[T]` `[]T`, `GoMap`, `GoChan`, `GoPtr`
  their Go types, exported classes their interfaces, facades their Go types, `GoError` `error`,
  Scala functions Go funcs; conversions are those above.
- An exported object crosses the boundary as itself (the interface holds its struct pointer), so
  `==` on it in Go is identity.
- Errors and panics are as in [Errors and panics](#errors-and-panics); buffered Scala output is
  flushed when each call returns.
- The library's modules and static initializers run on first use, as in programs; Go package
  initialization only creates literals.
- A Go package that the library imports through a facade cannot import the library: Go forbids
  import cycles (Go: [Import declarations][go-import]).
- Not supported: exported traits, fields and `val`s, secondary constructors, tuple results,
  generic methods, and overloads with the same Go name.

## Goroutines and threads

Go code may call Scala code (exports, callbacks, Go methods implemented in Scala) from any
goroutine, concurrently. Such a goroutine is a thread for Scala code: it gets a `Thread` object
the first time it needs one (`Thread.currentThread`, a monitor, a `ThreadLocal`), kept while the
goroutine runs. Scala code synchronizes with Go code through the edges of the
[Go memory model][go-mem]: a value passed in a call is visible to the callee, and values shared
across goroutines need channels, `sync` primitives or atomics on the Go side and the
corresponding constructs on the Scala side (see [Runtime: memory model](runtime.md#memory-model)).
Scala code started by Go code with a `go` statement is not a non-daemon thread: the program does
not wait for it.

[go-spec]: https://go.dev/ref/spec
[go-mem]: https://go.dev/ref/mem
[go-mem-chan]: https://go.dev/ref/mem#chan
[go-alias]: https://go.dev/ref/spec#Alias_declarations
[go-append]: https://go.dev/ref/spec#Appending_and_copying_slices
[go-assert]: https://go.dev/ref/spec#Type_assertions
[go-assign-stmt]: https://go.dev/ref/spec#Assignment_statements
[go-assignability]: https://go.dev/ref/spec#Assignability
[go-calls]: https://go.dev/ref/spec#Calls
[go-close]: https://go.dev/ref/spec#Close
[go-compare]: https://go.dev/ref/spec#Comparison_operators
[go-composite]: https://go.dev/ref/spec#Composite_literals
[go-constants]: https://go.dev/ref/spec#Constants
[go-consts]: https://go.dev/ref/spec#Constant_declarations
[go-conversions]: https://go.dev/ref/spec#Conversions
[go-delete]: https://go.dev/ref/spec#Deletion_of_map_elements
[go-exported]: https://go.dev/ref/spec#Exported_identifiers
[go-funcs]: https://go.dev/ref/spec#Function_declarations
[go-implements]: https://go.dev/ref/spec#Implementing_an_interface
[go-import]: https://go.dev/ref/spec#Import_declarations
[go-index]: https://go.dev/ref/spec#Index_expressions
[go-inference]: https://go.dev/ref/spec#Type_inference
[go-interfaces]: https://go.dev/ref/spec#Interface_types
[go-make]: https://go.dev/ref/spec#Making_slices_maps_and_channels
[go-methodsets]: https://go.dev/ref/spec#Method_sets
[go-new]: https://go.dev/ref/spec#Allocation
[go-panics]: https://go.dev/ref/spec#Handling_panics
[go-range]: https://go.dev/ref/spec#For_range
[go-recv]: https://go.dev/ref/spec#Receive_operator
[go-repr]: https://go.dev/ref/spec#Representability
[go-satisfy]: https://go.dev/ref/spec#Satisfying_a_type_constraint
[go-selectors]: https://go.dev/ref/spec#Selectors
[go-send]: https://go.dev/ref/spec#Send_statements
[go-slices]: https://go.dev/ref/spec#Slice_expressions
[go-typedefs]: https://go.dev/ref/spec#Type_definitions
[go-unique]: https://go.dev/ref/spec#Uniqueness_of_identifiers
[go-variadic]: https://go.dev/ref/spec#Passing_arguments_to_..._parameters
[go-vars]: https://go.dev/ref/spec#Variable_declarations
[go-zero]: https://go.dev/ref/spec#The_zero_value
