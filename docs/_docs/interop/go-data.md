---
title: Go data
---

# Go data

Scala's own types convert at the Go boundary: a `String` is transcoded, an `Array[String]` is
copied. When data should stay Go data, shared with Go code without copying, use the types of
`scalago.go`:

| Scala           | Go         |
|-----------------|------------|
| `GoString`      | `string`   |
| `GoSlice[T]`    | `[]T`      |
| `GoMap[K, V]`   | `map[K]V`  |
| `GoChan[T]`     | `chan T`   |
| `GoPtr[T]`      | `*T`       |
| `GoError`       | `error`    |

They also run on the JVM, with the same semantics, so code using them can be tested there.

## GoString

Scala strings are `java.lang.String` (UTF-16), so programs behave as on the JVM. `GoString` is
the opt-in Go string (immutable UTF-8 bytes), like Scala Native's `CString`: no conversion when
it is passed to Go.

Write them with the `go` interpolator, which works like `s"..."` (same escapes, checked at
compile time). `GoString` arguments are inserted as they are; anything else is rendered as
`String.valueOf` would.

```scala
import scalago.go.*

val name = "Grüße"
val g = go"Hi $name, ${1 + 1}!"  // or name.toGoString, or GoString(name)
g.byteLength                     // 14: UTF-8 bytes
g.toJavaString                   // "Hi Grüße, 2!"
g == go"Hi Grüße, 2!"            // true: contents are compared, on Go and on the JVM
```

On Go, literal parts are string constants and an interpolation is one Go concatenation, with no
UTF-16 conversion. `Ordering[GoString]` orders by bytes, as Go's `<`. There is no `f`-style
formatting: format into a `String` first (`go"${"%.2f".format(x)}"`).

## Scala data for Go code

Go code that inspects values by reflection (`encoding/json`, templates, configuration
libraries) cannot see inside Scala objects. Convert explicitly:

```scala
import scalago.go.*

final case class Todo(id: Long, title: String, tags: List[String])

val data = GoData(Todo(1, "write docs", List("docs")))  // map[string]any{"id": 1, "title": ..., "tags": []any{...}}
val config = GoData.map("server.port" -> 8080, "log.level" -> "info")  // GoMap[GoString, Any]
GoData.toScala(data)                                    // Map("id" -> 1, "tags" -> Vector("docs"), "title" -> "write docs")
```

`GoData(x)` turns case classes and maps into `map[string]any`, collections into `[]any`,
strings into Go strings, integers into Go `int` and `Option`s into their value or `nil`.
`GoData.toScala` turns Go maps into `Map[String, Any]` (sorted by key) and slices into
`Vector`s, and gives back `String`, `Long`, `Double` and `Boolean`. Both copy: a Scala value
passed to Go as `Any` without them stays the same object, so it comes back as itself.

## Slices

```scala
import scalago.go.*

val xs = GoSlice.of[Long](3, 1, 2)    // []int64
xs(0) = 30
val tail = xs.slice(1, 3)            // shares memory with xs, as in Go
val more = xs.appended(4)            // Go's append
val ints = GoSlice.make[GoInt](10)   // []int of length 10
val bytes = GoSlice.make[GoByte](4)  // []byte
```

## Maps

```scala
import scalago.go.*

val counts = GoMap.make[GoString, GoInt]          // map[string]int
counts(GoString("go")) = GoInt(1)
counts.get(GoString("go")).map(_.toLong)          // Some(1)
counts.remove(GoString("go"))                     // Go's delete
```

Iteration order is Go's, which is randomized (insertion order on the JVM).

## Channels

```scala
import scalago.go.*

val ch = GoChan.make[Int](capacity = 10)   // chan int32
ch.send(1)
ch.close()
ch.receive()                               // Some(1); None once closed and drained
for v <- ch do println(v)                  // Go's for v := range ch
```

`send` and `receive` block the calling thread (a goroutine). `trySend` and `tryReceive` do not.
In gears code, `awaitSend` and `awaitReceive` are cancellable (see
[Concurrency](../user/concurrency.md)). `select` over several channels is not supported yet.

## Pointers

```scala
import scalago.go.*

val p = GoPtr.to(42L)    // *int64
p.set(p.get + 1)
```

Facade types are already pointers; `GoPtr` is for other types, such as `*int` or `*[]string`.

## Element types

Elements are read and written in a **canonical** Scala view, decided by the Go element type:

| Go element type                             | Scala view                                    |
|---------------------------------------------|-----------------------------------------------|
| `int`, `int64`, `uint`, `uint64`, `uintptr` | `Long` (or `GoInt`, `GoUint`, `GoUint64`, `GoUintptr`) |
| `int32`, `uint32`                           | `Int` (or `GoUint32`)                         |
| `int16`, `uint16`, `int8`, `uint8`          | `Short`, `Char`, `Byte` (or `GoByte`)         |
| `float32`, `float64`, `bool`                | `Float`, `Double`, `Boolean`                  |
| `string`                                    | `GoString`                                    |
| a struct                                    | its facade                                    |
| anything else                               | the Go value: a facade, `GoSlice`, ..., `Any` |

Creating Go data needs the Go element type: `GoSlice.make[GoInt](n)` makes an `[]int`, and
`GoSlice.make[Long](n)` an `[]int64`; reading either gives `Long`s. `GoInt`, `GoByte` and the
other Go numeric types are Scala numbers at run time, so they only matter as type arguments and
in facades. A facade with the wrong view (`GoSlice[String]` for a Go `[]string`) is a link error
that names the right one.

## Limitations

- No `select` over several channels.
- Unsynchronized concurrent writes to a `GoMap` end the process with Go's fatal error
  `concurrent map writes`, which no `catch` intercepts.
- Go arrays, complex numbers and funcs inside Go data have no Scala view but `Any`: they can be
  passed on to Go, not used from Scala.
- Named slice and map types (`type H map[string]any`) are facade classes, not `GoSlice`s or
  `GoMap`s; their elements cannot be read from Scala.
- `GoData` makes maps, not Go structs: Go code sees a case class's fields under their Scala
  names, and `encoding/json` writes them sorted by name. Numbers that `encoding/json` decodes into
  `any` are `float64`s, so `GoData.toScala` gives them back as `Double`s.

[Limitations](../user/limitations.md#go-interop) lists all limitations of Go interop.
