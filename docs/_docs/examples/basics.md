---
title: basics
---

# basics

A small program ([source](https://github.com/julian-avar/scala-go/tree/main/examples/basics)):
case classes, collections, a `GoString` (`"Grüße"` is 7 UTF-8 bytes), Go's `strconv` and a local
Go module, `go/textstats`, through [generated facades](../interop/generated-facades.md), and
[gears](../user/concurrency.md#gears) futures on goroutines (gears is an ordinary Maven
dependency).

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

Run it with `./mill examples.basics.goRun Ada Grace`.

Limitation: the generated `go.mod` refers to `go/textstats` by absolute path (a `replace`); to
build the generated module elsewhere, copy `go/textstats` along and adjust the path.
