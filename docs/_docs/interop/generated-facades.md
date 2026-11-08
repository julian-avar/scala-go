---
title: Generated facades
---

# Generated facades

Instead of writing facades by hand, list Go packages in `goFacadePackages`, and the build
generates their facades from the packages' APIs as part of the module's sources:

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

Each Go package becomes one object in the Scala package `gofacades` (`goFacadesPackage`), named
as the Go package (`tea` for `github.com/charmbracelet/bubbletea`). When two packages have the
same name, the objects are named after their import paths instead. The generated sources are in
`out/<module>/goFacadeSources.dest`.

For the most-used standard library packages, depend on `scalago-gostd` instead (see
[Go standard library](../libraries/gostd.md)).

## How Go types map

- Go numbers are Scala numbers (`int` is `Long`, `int32` is `Int`), `string` is `String`,
  `error` is `GoError`, `any` is `Any`.
- Several results are a tuple. A trailing `error` result is thrown as a `GoException`, except
  where the error is the function's value (`errors.New`, `fmt.Errorf`, `ctx.Err()`).
- Go types are classes; Go interfaces are traits that Scala classes can implement. A type
  extends every generated interface whose methods it has (`bytes.Buffer` extends `io.Writer`,
  `io.Reader`, `fmt.Stringer`, ...), since Go typing is structural and Scala's is nominal.
- Go func types are aliases of Scala function types (`type Cmd = () => Any`), and interfaces
  without methods are aliases of `Any` (`type Msg = Any`): Scala lambdas and values are passed
  as they are.
- Other named types get a conversion named as the type, as in Go: `lipgloss.Color("205")`,
  `time.Duration(n)`.
- Inside Go data (slices, maps, channels, pointers), elements have their
  [canonical views](go-data.md#element-types): a `[]string` is a `GoSlice[GoString]`.
- Generic functions whose type parameters are constrained by interfaces take the interface:
  `key.Matches[K fmt.Stringer](k K, ...)` takes `k: fmt.Stringer`.

## Dependencies between packages

The types of other packages that the generated members use are generated too, but only their
types: `time.Duration` for `tea.Tick`, `io.Reader` for `tea.WithInput`. List a package itself to
get its functions, variables and constants (`"time"` for `time.Second`).

## What is left out

Members that cannot be represented are left out, with a comment in the generated source saying
why. The most common cases:

- generic functions with type sets (`~int | string`, `comparable`) and generic types;
- unnamed struct and interface types, arrays and complex numbers;
- funcs stored inside Go data;
- types of `internal` packages;
- constants that do not fit in a `Long`.

To use such a member, add a [hand-written facade](facades.md) for it next to the generated ones.

Generated facades are checked against Go at link time, like hand-written ones, but only where
they are used: unused members cost nothing.
