---
title: Calling Scala from Go
---

# Calling Scala from Go

A Scala library links into a Go module that Go programs require and import. Its Go API is what
the Scala code marks with `@GoExport`.

## The build

```scala sc:nocompile
import scalago.mill.ScalaGoLibraryModule

object mylib extends ScalaGoLibraryModule {
  def goModulePath = "example.com/mylib"
}
```

- `mill mylib.goLink` writes the Go module to `out/mylib/goLink.dest/go`. Its root package is
  the library's API, named after the last element of the module path, or `goPackageName`.
- `mill mylib.goBuild` checks the module with `go vet ./...`: a library has no executable.

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

- `@GoExport` on a method of a top-level object makes it a Go function of the package.
- `@GoExport` on a class makes it a Go interface type. Its methods are the class's `@GoExport`
  methods and those of exported superclasses. The primary constructor becomes `New<Name>`.
- Go names are the Scala names capitalized. `@GoExport("Name")` chooses another.
- A method annotated with `@throws` gets a trailing `error` result, which Scala exceptions
  become. Without `@throws`, an exception is a Go panic.
- Types convert as for [facades](facades.md#conversions), with the Go type chosen from the Scala
  type: `Int` is `int32`, `GoInt` is `int`, `String` and `GoString` are `string`, `Array[T]` and
  `GoSlice[T]` are `[]T`, an exported class is its interface, a Scala function is a Go func.

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

## Rules

- **Goroutines.** Exported code may be called from any goroutine. Share state through
  `synchronized`, atomics or other thread-safe classes, as on the JVM.
- **Identity.** A Scala object is the same Go value every time it crosses the boundary, so `==`
  works in Go.
- **Initialization.** Objects and static initializers run on first use, as in programs.
- **No import cycles.** A Go package that the library imports through a facade cannot import the
  library. Pass Scala code to such Go code as values instead: Go interfaces implemented in Scala,
  or funcs.
- **One Scala library per Go binary.** Linking is whole-program, so link all the Scala code a Go
  program uses into one library. Two separately linked libraries would each have their own
  runtime and standard library.
- **Go contexts.** To cancel Scala code when a caller's `context.Context` is done, see
  [Concurrency](../user/concurrency.md#go-contexts).

## Limitations

- Not supported: exported traits (Go code implementing Scala traits), exported `val`s and
  fields, secondary constructors, tuple results, overloads with the same Go name, generic
  methods. Wrap them in exported methods. Most are compile or link errors, but `@GoExport` on a
  `val` or field is ignored, and overloads exported under one Go name give Go code that does not
  compile ([known bugs](../contributing/roadmap.md#known-bugs)).
- Exported classes are Go interfaces. Go code sees their methods, never fields, so reflection
  over struct fields (`encoding/json`) cannot see inside them.
- One Scala library per Go binary, and no import cycles (see [Rules](#rules)).
