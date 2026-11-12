---
title: Generated facades
---

# Generated facades

The build generates [facades](facades.md) for the Go packages listed in `goFacadePackages`, as
part of the module's sources (in `out/<module>/goFacadeSources.dest`):

```scala sc:nocompile
object app extends ScalaGoModule {
  def goFacadePackages = Seq("strconv", "net/url")
}
```

```scala sc:nocompile
import gofacades.{strconv, url}

@main def demo(): Unit =
  val u = url.Parse("https://example.com/a?q=scala")
  println(strconv.Quote(u.Query().Get("q")))
```

- Each Go package is an object of the Scala package `gofacades` (`goFacadesPackage`), named as
  the Go package (`tea` for `github.com/charmbracelet/bubbletea`). Packages with the same name
  are named after their import paths (`math_rand`), or as an import alias in `goFacadePackages`
  names them (`"math/rand=mathrand"`).
- Types of other packages that members use are generated too, only the types (`time.Duration` for
  `tea.Tick`). List the package itself to get its functions, variables and constants.
- Facades are reused from dependencies: with `scalago-gostd` ([Go standard
  library](../libraries/gostd.md)) or another facade module in `mvnDeps`, members take and return
  its types and generated types extend its interface traits (a `scalago.gostd.strings.Reader` goes
  where bubbletea expects an `io.Reader`). A listed package that dependencies provide completely
  is skipped, with a note. Modules that both generate facades, one depending on the other, need
  different `goFacadesPackage`s.

## How Go types map

Beyond the [conversions](facades.md#conversions):

- `int` is `Long`; `int32` and `rune` are `Int`. Elements of Go data are their
  [canonical views](go-data.md#element-types): `[]string` is `GoSlice[GoString]`.
- A trailing `error` result is thrown as a `GoException`, except where the error is the value
  (`errors.New`, `fmt.Errorf`, `ctx.Err()`).
- A named type is a class; if not a struct, also a conversion named as the type
  (`lipgloss.Color("205")`); a named map or slice type, also a composite literal
  (`gin.H("message" -> "pong")`).
- An interface is a trait, which every generated type with its methods extends (`bytes.Buffer`
  extends `io.Writer`, `io.Reader`, `fmt.Stringer`, ...). An interface without methods is an
  alias of `Any` (`type Msg = Any`).
- A func type is an alias of a Scala function type (`type Cmd = () => Any`); a trailing `error`
  is thrown by the function (`() => Unit` for `func() error`), several results come from a tuple.
- Generic functions and types are generic methods, classes, traits or aliases
  (`type Seq[V] = (V => Boolean) => Unit` for `iter.Seq`; see [Generics](facades.md#generics)).
  Type parameters with interface constraints, all used by parameters, are the interfaces:
  `key.Matches[K fmt.Stringer](k K, ...)` takes `k: fmt.Stringer`.
- An array `[n]T` is a `GoSlice` of a copy; an array parameter copies the first `n` elements of a
  `GoSlice` (Go panics if it is shorter). `struct{}` is `Unit` (`<-chan struct{}` is
  `GoChan[Unit]`), `complex128` is `GoComplex`.
- Constants beyond `Long` that fit a `uint64` (`math.MaxUint64`) are the `Long` of their bits.

## What is left out

Members that cannot be represented are left out, with a comment in the generated source:
`// not generated: Errors (type github.com/gin-gonic/gin.errorMsgs is not generated)`.

- unnamed struct and interface types other than `struct{}`
- arrays and `complex64` inside Go data (`[][32]byte`), `unsafe.Pointer` (not planned)
- methods with pointer receivers of types that are neither structs nor interfaces
  (`net.IP.UnmarshalText`): Scala holds their values as copies, which the method would change
- funcs inside Go slices and maps (struct fields of func type are generated), variadic func
  types, and returned funcs with more than 3 parameters
- setters of fields whose types code outside their package cannot name
- integer constants beyond `uint64`

Also:

- Package variables have getters only (no comment), and a trailing `error` is always thrown.
- Values of types code outside their package cannot name (unexported, or of an `internal`
  package) are typed as the interface they implement with the most methods (`binary.BigEndian`
  is a `binary.ByteOrder`), or `Any`; reach their other interfaces with a cast
  (`binary.BigEndian.asInstanceOf[binary.AppendByteOrder]`). Calls taking them go through Go's
  reflection: slower, and an argument of the wrong type is a `ClassCastException` at run time.
  Their fields are read-only.

For any of these, declare the member by hand (an error as a value with a `(T, GoError)` result,
an assignable variable with `x_=`) in a [facade](facades.md) next to the generated ones.
