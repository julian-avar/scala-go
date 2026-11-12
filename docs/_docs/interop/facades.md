---
title: Calling Go
---

# Calling Go

A **facade** is a Scala declaration of a Go API. Write it by hand, as on this page, or
[generate](generated-facades.md) it. The linker checks every facade member a program uses
against the real Go declaration, and converts values between the facade's Scala types and the Go
types. A mismatch is a link error at the Scala call site; unused members are not checked.

The standard library needs no setup. List other Go modules in `goRequires`, or local
directories in `goLocalModules` ([Go libraries](go-libraries.md)).

## Declaring a package

`@GoImport(path)` on an object declares the Go package `path`:

```scala
import scalago.go.*

@GoImport("strconv")
object strconv:
  def Itoa(i: Int): String = native                       // func Itoa(i int) string
  def Atoi(s: String): Int = native                       // func Atoi(s string) (int, error)
  def ParseBool(s: String): (Boolean, GoError) = native   // keeps the error
  def IntSize: Int = native                               // a constant

@main def demo(): Unit =
  println(strconv.Atoi("42") + 1)
```

- Members are `def`s whose body is `native`. Use `def`, not `val`.
- A member with a parameter list is a package function. One without is a variable or constant.
- Names are Go's. `@GoName("Fields")` gives the Go name when the Scala name differs, for example
  to declare one function with two signatures.

## Declaring types

Classes and traits nested in the object are the package's types:

```scala
import scalago.go.*

@GoImport("net/url")
object url:
  def Parse(rawURL: String): URL = native
  class URL:                               // type URL struct { ... }
    def Host: String = native              // a field
    def Path: String = native
    def Path_=(p: String): Unit = native   // assigning it: u.Path = "/"
    def Query(): Values = native           // a method
  class Values:                            // type Values map[string][]string
    def Get(key: String): String = native

@GoImport("io")
object io:
  trait Writer:                            // type Writer interface { ... }
    def Write(p: Array[Byte]): Int
```

| Scala                                    | Go                                   |
|------------------------------------------|--------------------------------------|
| `class T`                                | type `T`; a struct type is held by pointer |
| `trait T`                                | interface `T`                        |
| member without a parameter list          | a field; declare `x_=` next to `x` to assign it |
| member with a parameter list             | a method                             |
| `new T`                                  | the zero value of `T`                |
| `null`                                   | `nil`                                |
| `isInstanceOf[T]`, `case x: T`           | a type assertion                     |
| `@GoImport(path, name)` on a top-level class or trait | type `name` of package `path`, outside a package object |

A Scala class can implement a facade trait and be passed to Go as that interface:

```scala sc:nocompile
class Upper extends io.Writer:
  def Write(p: Array[Byte]): Int =
    print(new String(p, "UTF-8").toUpperCase)
    p.length
```

Scala cannot define Go struct types: Go sees a Scala class only through the interfaces it
implements. Go code that fills structs by reflection (JSON binding, database scanners) cannot fill
one; use [`GoData`](go-data.md#scala-data-for-go-code).

## Conversions

| Scala                          | Go                                                         |
|--------------------------------|------------------------------------------------------------|
| `Int`, `Long`, `Double`, ...   | any numeric type, converted as Go converts (`int(x)`)      |
| `Boolean`                      | `bool`                                                     |
| `String`                       | `string`, transcoded from UTF-16 to UTF-8 and back         |
| `GoString`                     | `string`, as is                                            |
| `GoError`                      | `error`                                                    |
| a facade class or trait        | its Go type                                                |
| a Scala class                  | a Go interface it implements                               |
| `Array[T]`                     | `[]T`; shared when the representations match (`Array[Byte]` and `[]byte`), else copied |
| `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go value, shared (see [Go data](go-data.md)) |
| `T*` (last parameter)          | a variadic parameter `...T`                                |
| `(A, B) => C`                  | `func(A, B) C`; a Go func returned to Scala is a Scala function of at most 3 parameters |
| `(A, B)` as a result           | two results                                                |
| `Any`, `AnyRef`                | `any` and other interface types                            |

## Errors

- A Go `error` result that the facade's result type leaves out is checked: a non-nil error is
  thrown as a `GoException`, whose `error` is the Go error.
- To get the error as a value, keep it in the result type: `(Boolean, GoError)`. `nil` is `null`.
- A Go panic becomes a `RuntimeException`. Go runtime errors become the matching Java exceptions.
- A Scala method implementing a Go method that returns an `error` returns a thrown exception as
  that error. A `GoException` gives back the Go error it wraps (`throw GoException(io.EOF)`).

```scala sc:nocompile
try strconv.Atoi("forty-two")
catch case e: GoException => println(s"not a number: ${e.error.Error()}")

val (b, err) = strconv.ParseBool("maybe")
if err != null then println(err.Error())
```

## Generics

Declare a generic Go function as a generic member, with the Go type parameters' names; a type
parameter whose constraint has a core type (`S ~[]E`) is that type. Each call instantiates it
with its type arguments, as Scala gives or infers them: canonical views, such as `GoInt` for
`int` and `GoString` for `string`. A generic Go type is a generic class or trait.

```scala
import scalago.go.*

@GoImport("slices")
object slices:
  def Max[E](x: GoSlice[E]): E = native                   // Max[S ~[]E, E cmp.Ordered](x S) E
  def Index[E](s: GoSlice[E], v: E): Long = native

@GoImport("sync/atomic")
object atomic:
  class Pointer[T]:                                       // type Pointer[T any] struct
    def Load(): GoPtr[T] = native
    def Store(p: GoPtr[T]): Unit = native

slices.Max(GoSlice.of[GoInt](GoInt(3), GoInt(7)))         // slices.Max[[]int, int]: 7
val p = new atomic.Pointer[GoString]                      // new(atomic.Pointer[string])
```

Go checks the constraints when it compiles the generated code: `slices.Max[Any]` compiles in
Scala, not in Go. Give the type arguments that no parameter determines
(`reflect.TypeFor[GoInt]()`): the plugin rejects those Scala would infer as `Any` or `Nothing`. A monomorphic member also works, one per instantiation
(`@GoName("Max") def maxFloat(x: GoSlice[Double]): Double`); Go's type arguments then come from
its Scala types. Write `GoSlice.of[GoInt](...)` in arguments of generic members: with the element
type unknown, Scala cannot choose `GoSlice.of`'s Go type. Type tests against generic facades are
not supported. [Specification](../spec/interop.md#generic-functions).

## Conversions to named types

A package member named as a type, with one parameter, is Go's conversion to that type:
`def Duration(d: Long): Duration` in a facade of `time` is `time.Duration(d)`. Named numeric
types are facade classes, not Scala numbers: no arithmetic. Convert in with the conversion
(`time.Duration(nanos)`), out with the type's methods (`d.Nanoseconds()`).

## On the JVM

Facade members throw `UnsupportedOperationException` on the JVM, so a program that calls Go runs
only as Go. Constructing a facade class instance (`new F`) does not throw; using its members does.
`GoError`, `GoException`, [Go data](go-data.md) and `GoString` work on both. To test the rest on the JVM, keep Go calls behind a trait of your own.

[Limitations](../user/limitations.md#go-interop) lists all limitations of Go interop.
