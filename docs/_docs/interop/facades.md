---
title: Calling Go
---

# Calling Go

A **facade** declares a Go API in Scala, by hand as on this page or
[generated](generated-facades.md). The linker checks each facade member a program uses against the
Go declaration (a mismatch is a link error at the Scala call site; unused members are not
checked) and converts values between their types. Modules other than the standard library go in
`goRequires` or `goLocalModules` ([Go libraries](go-libraries.md)). The full rules are in
[Go interoperability](../spec/interop.md).

## Declaring a package

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

- Members are `def`s (not `val`s) whose body is `native`: with a parameter list a function,
  without one a variable or constant. `x_=` assigns a variable.
- Names are Go's. `@GoName("Fields")` gives the Go name when the Scala name differs, for example
  to declare one function with two signatures.
- A member named as a type, with one parameter, is Go's conversion to that type:
  `def Duration(d: Long): Duration` in a facade of `time` is `time.Duration(d)`.

## Declaring types

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

- A class nested in the object is a Go type (a struct type is held by pointer), a trait an
  interface. `@GoImport(path, name)` on a top-level class or trait declares type `name` of
  package `path`.
- `new T` is Go's zero value of `T`, `null` is `nil`, `isInstanceOf[T]` and `case x: T` are type
  assertions.
- Assigning a field changes the struct you hold. Methods with value receivers return copies:
  `val (input2, cmd) = input.Update(msg)`. A struct goes back to Go by value where its methods
  allow (`tea.KeyMsg`, `lipgloss.Style`), so the library's type switches see it.
- A Scala class extending a facade trait (`class Upper extends io.Writer`) is passed to Go as
  that interface. Scala cannot define Go struct types:
  see [Scala data for Go code](go-data.md#scala-data-for-go-code).

## Conversions

| Scala                          | Go                                                         |
|--------------------------------|------------------------------------------------------------|
| `Int`, `Long`, `Double`, ...   | any numeric type, converted as Go converts (`int(x)`)      |
| `String`; `GoString`           | `string`: transcoded between UTF-16 and UTF-8 at each crossing; as is (use it in hot paths) |
| `Array[T]`                     | `[]T`: shared when the representations match (`Array[Byte]` and `[]byte`), else copied |
| `GoSlice[T]`, `GoMap[K, V]`, `GoChan[T]`, `GoPtr[T]` | the Go value, shared ([Go data](go-data.md)) |
| a facade type; a Scala class   | its Go type; a Go interface it implements                  |
| `Any`, `GoError`               | `any` and other interface types; `error`                   |
| `T*` (last parameter)          | a variadic parameter `...T`                                |
| `(A, B) => C`                  | `func(A, B) C`; a Go func returned to Scala is a Scala function of at most 3 parameters |
| `(A, B)` as a result           | two results                                                |

Named numeric types (`time.Duration`) are facade classes, not Scala numbers: no arithmetic.
Convert in with the conversion (`time.Duration(nanos)`), out with the type's methods
(`d.Nanoseconds()`).

## Errors

- A Go `error` result that the facade's result type leaves out is checked: a non-nil error is
  thrown as a `GoException`, whose `error` is the Go error. Keep it in the result type
  (`(Boolean, GoError)`) to get it as a value; `nil` is `null`. Generated facades always leave it
  out.
- A Go panic becomes a `RuntimeException`; Go runtime errors become the matching Java exceptions.
- A Scala method implementing a Go method that returns an `error` returns a thrown exception as
  that error; a `GoException` gives back the Go error it wraps (`throw GoException(io.EOF)`).

## Generics

A generic Go function is a generic member with the Go type parameters' names; a type parameter
whose constraint has a core type (`S ~[]E`) is that type. A generic Go type is a generic class or
trait. Each use is instantiated with the Go types of its Scala type arguments, given or inferred
(`GoInt` is `int`, `Long` is `int64`). A monomorphic member per instantiation also works:
`@GoName("Max") def maxFloat(x: GoSlice[Double]): Double`.

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

- Scala does not check Go's constraints: `slices.Max` of a `GoSlice[Any]` links, then fails
  `go build` ([known bug](../contributing/roadmap.md#known-bugs)).
- A type argument that no parameter determines must be given (`reflect.TypeFor[GoInt]()`): the
  plugin rejects those Scala would infer as `Any` or `Nothing`.
- `GoSlice.of(...)` in an argument of a generic member needs its element type
  (`GoSlice.of[GoInt](...)`), otherwise its Go type is ambiguous.
- Type tests against generic facade types are link errors, and casts to them are unchecked.
- A struct facade as a type argument stands for the pointer type: `atomic.Pointer[bytes.Buffer]`
  is Go's `atomic.Pointer[*bytes.Buffer]`.

## On the JVM

Facade members throw `UnsupportedOperationException` on the JVM, so a program that calls Go runs
only as Go. `new F` of a facade class does not throw; using its members does. `GoError`,
`GoException`, [Go data](go-data.md) and `GoString` work on both. To test the rest on the JVM,
keep Go calls behind a trait of your own.
