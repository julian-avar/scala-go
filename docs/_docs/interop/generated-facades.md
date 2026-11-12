---
title: Generated facades
---

# Generated facades

The build generates facades for the Go packages listed in `goFacadePackages`, as part of the
module's sources:

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

- Each Go package is one object, named as the Go package (`tea` for
  `github.com/charmbracelet/bubbletea`), in the Scala package `gofacades` (`goFacadesPackage`).
  Packages with the same name are named after their import paths instead.
- The sources are in `out/<module>/goFacadeSources.dest`.
- Generated facades are checked at link time like hand-written ones, only where used: unused
  members cost nothing.

## Reusing dependencies' facades

Generated facades reuse the Go types that the module's dependencies provide: `scalago-gostd`
([Go standard library](../libraries/gostd.md)) or another module's facades. Generated members
take and return the dependency's types, and generated types extend its interface traits, so a
`scalago.gostd.strings.Reader` goes where bubbletea expects an `io.Reader`.

```scala sc:nocompile
object app extends ScalaGoModule {
  def mvnDeps = Seq(mvn"io.github.scalago::scalago-gostd:${scalaGoVersion()}")
  def goRequires = Seq("github.com/charmbracelet/bubbletea@v1.3.10")
  // io.Reader, time.Duration, ... come from scalago.gostd; only bubbletea's types are generated.
  def goFacadePackages = Seq("github.com/charmbracelet/bubbletea")
}
```

A listed package that dependencies provide completely is skipped, with a note. If two modules
both generate facades and one depends on the other, give them different `goFacadesPackage`s.

## How Go types map

| Go                                   | Scala                                                       |
|--------------------------------------|-------------------------------------------------------------|
| numbers, `bool`, `string`            | Scala numbers (`int` is `Long`, `int32` and `rune` are `Int`), `Boolean`, `String` |
| `error`, `any`                       | `GoError`, `Any`                                            |
| several results                      | a tuple                                                     |
| a trailing `error` result            | thrown as a `GoException`, except where the error is the value (`errors.New`, `fmt.Errorf`, `ctx.Err()`) |
| a named type                         | a class                                                     |
| an interface                         | a trait that Scala classes can implement                    |
| a func type                          | an alias of a Scala function type: `type Cmd = () => Any`   |
| a func type returning `error`        | a function that throws: `() => Unit` for `func() error`     |
| an interface without methods         | an alias of `Any`: `type Msg = Any`                         |
| a named type, not a struct           | also a conversion named as the type: `lipgloss.Color("205")`, `time.Duration(n)` |
| named map and slice types            | built like composite literals: `gin.H("message" -> "pong")` |
| elements of Go data                  | their [canonical views](go-data.md#element-types): `[]string` is `GoSlice[GoString]` |
| a type parameter with an interface constraint | the interface: `key.Matches[K fmt.Stringer](k K, ...)` takes `k: fmt.Stringer` |

- A type extends every generated interface whose methods it has (`bytes.Buffer` extends
  `io.Writer`, `io.Reader`, `fmt.Stringer`, ...): Go typing is structural, Scala's nominal.
- Function aliases take Scala lambdas as they are. In a Go func type, an exception becomes the
  `error` result, and several results come from a tuple.

## Dependencies between packages

Types of other packages that generated members use are generated too, but only the types:
`time.Duration` for `tea.Tick`, `io.Reader` for `tea.WithInput`. List the package itself to get
its functions, variables and constants (`"time"` for `time.Second`).

## What is left out

Members that cannot be represented are left out, with a comment in the generated source:
`// not generated: Errors (type github.com/gin-gonic/gin.errorMsgs is not generated)`.

- generic functions with type sets (`~int | string`, `comparable`), and members using a generic
  type's parameters or instances (argus's `Bind[T]`, which returns a `*Bound[T]`)
- unnamed struct and interface types (a `<-chan struct{}` result), arrays, complex numbers
- unexported types in exported APIs, and types of `internal` packages
- funcs stored inside Go data (struct fields of func type, slices of funcs), variadic func
  types, and returned funcs with more than 3 parameters
- constants that do not fit in a `Long`

To use such a member, declare it in a [hand-written facade](facades.md) next to the generated
ones, where the [conversions](facades.md#conversions) can express it. The
[examples](../examples/index.md) list what their generated facades leave out.
