---
title: Calling Scala from Go
---

# Calling Scala from Go

A Scala library links into a Go module that Go programs require and import. Its Go API is what
the Scala code marks with `@GoExport`.

```scala sc:nocompile
import scalago.mill.ScalaGoLibraryModule

object mylib extends ScalaGoLibraryModule {
  def goModulePath = "example.com/mylib"
}
```

`mill mylib.goLink` writes the Go module to `out/mylib/goLink.dest/go`; its root package, named
after the last element of the module path (or `goPackageName`), is the library's API.
`mill mylib.goBuild` checks the module with `go vet ./...`.

## Exporting

```scala
import scalago.go.*

object Api:
  @GoExport def greet(name: String): String = s"Hello, $name!"   // func Greet(name string) string

  @GoExport @throws[NumberFormatException]
  def parse(s: String): Int = s.trim.toInt                       // func Parse(s string) (int32, error)

@GoExport class Counter(start: Int):                            // type Counter interface { ... }
  private var count = start                                     // func NewCounter(start int32) Counter
  @GoExport def increment(): Unit = synchronized { count += 1 } // Increment()
  @GoExport("Value") def current: Int = synchronized(count)     // Value() int32
```

- A method of a top-level object becomes a Go function; a class, a Go interface of its (and its
  exported superclasses') `@GoExport` methods, with `New<Name>` for the primary constructor.
- Go names are the Scala names capitalized; `@GoExport("Name")` chooses another.
- With `@throws`, a method gets a trailing `error` result, which Scala exceptions become; without,
  an exception is a Go panic.
- Go types follow the Scala types, with the [facade conversions](facades.md#conversions): `Int` is
  `int32`, `GoInt` is `int`, `String` and `GoString` are `string`, `Array[T]` and `GoSlice[T]` are
  `[]T`, a Scala function is a Go func.

## Using the library from Go

```go
package main

import (
	"fmt"

	"example.com/mylib"
)

func main() {
	fmt.Println(mylib.Greet("Go"))
	c := mylib.NewCounter(1)
	go c.Increment() // Scala code is safe to call from any goroutine
}
```

Until the module is published, point the Go program's `go.mod` at it:

```
require example.com/mylib v0.0.0

replace example.com/mylib => ../out/mylib/goLink.dest/go
```

Exported code may be called from any goroutine; share state through `synchronized`, atomics or
other thread-safe classes, as on the JVM. A Scala object is the same Go value every time it
crosses, so `==` works in Go. Objects and static initializers run on first use. To cancel Scala
code when a caller's `context.Context` is done, see [Concurrency](../user/concurrency.md#go-contexts).

## Limitations

- Not supported: exported traits (Go code implementing Scala traits), `val`s and fields,
  secondary constructors, tuple results, overloads with the same Go name, generic methods. Wrap
  them in exported methods. Most are compile or link errors, but `@GoExport` on a `val` or field
  is ignored, and overloads exported under one Go name give Go code that does not compile
  ([known bugs](../contributing/roadmap.md#known-bugs)).
- Go code sees exported classes' methods, never fields, so reflection over struct fields
  (`encoding/json`) cannot see inside them.
- One Scala library per Go binary: link all the Scala code a Go program uses into one library.
  Separately linked libraries would each have their own runtime and standard library.
- No import cycles: a Go package that the library imports through a facade cannot import the
  library. Pass Scala code to it as values (Go interfaces implemented in Scala, funcs).
