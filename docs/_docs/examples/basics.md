---
title: basics
---

# basics

A small program built with `ScalaGoModule`
([source](https://github.com/julian-avar/scala-go/tree/main/examples/basics)). It shows:

- case classes and pattern matching, collections, string interpolation;
- a `GoString`, UTF-8 as Go stores strings (`"Grüße"` is 7 bytes);
- Go called through [generated facades](../interop/generated-facades.md): the standard library's
  `strconv` and a local Go module, `go/textstats`;
- [gears](../user/concurrency.md#gears) futures on goroutines, gears being an ordinary Maven
  dependency compiled from its TASTy.

```scala sc:nocompile
// build.mill
object basics extends ScalaGoModule {
  def goLocalModules = Task.Sources(moduleDir / "go" / "textstats")   // a Go module with its go.mod
  def goFacadePackages = Seq("strconv", "example.com/textstats")
}
```

`goLocalModules` adds the module to the generated `go.mod` (a `replace` directive), and
`goFacadePackages` generates the `gofacades.textstats` and `gofacades.strconv` objects. A Go
`[]string` arrives as a `GoSlice[GoString]`. `Longest` returns `(string, error)`: the facade
returns the `String` and throws a non-nil `error` as a `GoException`.

```scala sc:nocompile
import gofacades.*

val words = textstats.Words("It's a truth universally acknowledged...")   // Go's []string
println(s"${words.length} words, the longest is ${strconv.Quote(textstats.Longest(words))}")
try textstats.Longest(GoSlice.empty[GoString])
catch case e: GoException => println(s"Go error: ${e.getMessage}")      // Go's error result
```

Run it with `./mill examples.basics.goRun Ada Grace`. The
[README](https://github.com/julian-avar/scala-go/blob/main/examples/basics/README.md) has the
expected output and the limitations this example hits, with workarounds.
