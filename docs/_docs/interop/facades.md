---
title: Calling Go
---

# Calling Go

Scala code calls Go through **facades**: Scala declarations of a Go API. Facades can be
written by hand, as on this page, or [generated](generated-facades.md) from the Go packages.

The linker checks every facade member a program uses against the real Go package, and
converts values between the Scala types of the facade and the Go types of the declaration. A
facade that does not match is a link error at the Scala call site.

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

- Methods are the package's functions. Members declared without a parameter list are its
  variables and constants.
- Members are `def`s whose body is `native`.
- Names are Go's. `@GoName("Fields")` on a member gives the Go name when the Scala name differs,
  for example to declare one function with two signatures.

## Declaring types

Classes and traits nested in the object are the package's types:

```scala
import scalago.go.*

@GoImport("strings")
object strings:
  def NewReplacer(oldnew: String*): Replacer = native
  class Replacer:                          // type Replacer struct { ... }
    def Replace(s: String): String = native
  class Builder:                           // type Builder struct { ... }
    def WriteString(s: String): Int = native
    def String(): String = native

@GoImport("net/url")
object url:
  def Parse(rawURL: String): URL = native
  class URL:
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

- Classes are Go types. Struct types are held by pointer. `new strings.Builder` is Go's zero
  value of the type, and `null` is `nil`.
- Traits are Go interfaces. Scala classes can implement them and be passed to Go:

  ```scala sc:nocompile
  class Upper extends io.Writer:
    def Write(p: Array[Byte]): Int =
      print(new String(p, "UTF-8").toUpperCase)
      p.length
  ```

- Fields are members without parameter lists. To assign one, declare `x_=` next to the getter `x`.
- `@GoImport(path, name)` on a top-level class or trait declares a type outside of a package
  object.
- `isInstanceOf` and pattern matching on facade types are Go type assertions.

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
| `(A, B) => C`                  | `func(A, B) C`                                             |
| `(A, B)` as a result           | two results                                                |
| `Any`, `AnyRef`                | `any` and other interface types                            |

A Go func returned to Scala is a Scala function.

## Errors

- If the Go function returns an `error` that the facade's result type leaves out, a non-nil
  error is thrown as a `GoException`, whose `error` is the Go error.
- To handle the error as a value, keep it in the result: `(Boolean, GoError)`. `nil` is `null`.
- A Go panic becomes a `RuntimeException` (Go runtime errors become the matching Java
  exceptions).
- A Scala method implementing a Go method that returns an `error` returns the exception as the
  error: a `GoException` gives back the Go error it wraps (`throw GoException(io.EOF)`).

```scala sc:nocompile
try strconv.Atoi("forty-two")
catch case e: GoException => println(s"not a number: ${e.error.Error()}")

val (b, err) = strconv.ParseBool("maybe")
if err != null then println(err.Error())
```

## Generic functions

A generic Go function is declared once per instantiation, with concrete types; Go infers the
type arguments from them:

```scala
import scalago.go.*

@GoImport("slices")
object slices:
  def Max(xs: GoSlice[GoInt]): GoInt = native          // slices.Max[[]int, int]
  @GoName("Max") def MaxFloat(xs: GoSlice[Double]): Double = native
```

## Conversions to named types

A package member named as a type, with one parameter, is Go's conversion to that type:
`def Duration(d: Long): Duration` in a facade of `time` is `time.Duration(d)`.

## On the JVM

Facade members throw `UnsupportedOperationException` on the JVM, so a program that calls Go
runs only as Go. Keep Go calls behind an interface of your own to test the rest on the JVM.

## Go modules

The standard library needs no setup. Other Go modules are listed in `goRequires`, or in
`goLocalModules` for a local directory; see [Go libraries](go-libraries.md).

## Limitations

- Programs that call Go run only as Go (see [On the JVM](#on-the-jvm)).
- Scala cannot define Go struct types: Go sees a Scala class only through the interfaces it
  implements. Go code that fills structs by reflection (JSON binding, database scanners) cannot
  fill a Scala class; see [Go data](go-data.md#scala-data-for-go-code) for the alternative.
- Generic Go types cannot be declared; generic functions are declared once per instantiation
  (see [Generic functions](#generic-functions)).
- Named numeric types (`time.Duration`) are facade classes, not Scala numbers: convert with the
  conversion (`time.Duration(nanos)`) and the type's methods (`d.Nanoseconds()`).
- Go funcs returned to Scala have at most 3 parameters.

[Limitations](../user/limitations.md#go-interop) lists all limitations of Go interop.
