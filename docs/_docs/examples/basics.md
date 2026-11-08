---
title: basics
---

# basics

A small program ([source](https://github.com/julian-avar/scala-go/tree/main/examples/basics)):
case classes and pattern matching, a `GoString`, Go called through generated facades (the
standard library's `strconv` and a local Go module), and gears futures on goroutines.

```scala sc:nocompile
// build.mill
object basics extends ScalaGoModule {
  def goLocalModules = Task.Sources(moduleDir / "go" / "textstats")   // a Go module with its go.mod
  def goFacadePackages = Seq("strconv", "example.com/textstats")
}
```

```scala sc:nocompile
import gofacades.*

val words = textstats.Words("It's a truth universally acknowledged...")   // Go's []string
println(s"${words.length} words, the longest is ${strconv.Quote(textstats.Longest(words))}")
try textstats.Longest(GoSlice.empty[GoString])
catch case e: GoException => println(s"Go error: ${e.getMessage}")      // Go's error result
```

## Running it

```sh
./mill examples.basics.goRun Ada Grace
```

There is no `check` command: the example's
[README](https://github.com/julian-avar/scala-go/blob/main/examples/basics/README.md) shows the
expected output.

## Limitations

- It calls Go, so it runs only as Go: on the JVM (`./mill examples.basics.run`) the first facade
  call throws `UnsupportedOperationException`.
- The generated `go.mod` refers to the local Go module by absolute path; copy it along to build
  the generated module elsewhere (see [Shipping binaries](../user/shipping.md#building-elsewhere)).
- The generated facade throws `Longest`'s `error` result; a
  [hand-written facade](../interop/facades.md#errors) can return it as a `GoError` instead.
