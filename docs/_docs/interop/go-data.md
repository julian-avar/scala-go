---
title: Go data
---

# Go data

The types of `scalago.go` are Go data, shared with Go code without copying: `GoString` is
`string`, `GoSlice[T]` `[]T`, `GoMap[K, V]` `map[K]V`, `GoChan[T]` `chan T`, `GoPtr[T]` `*T`,
`GoComplex` `complex128` and `GoError` `error`. Scala's own types are converted at the boundary
instead (a `String` is transcoded, an `Array[String]` copied). Go data also runs on the JVM with
the same semantics, except that `GoMap` iterates in insertion order there (in Go's randomized
order on Go).

## GoString

`GoString` is immutable UTF-8 bytes, passed to Go without conversion, while `String` stays
`java.lang.String` (UTF-16). The `go` interpolator works like `s"..."`, checked at compile time;
it inserts `GoString`s as they are and other values as `String.valueOf` renders them, in one Go
concatenation with no UTF-16 conversion. `Ordering[GoString]` orders by
bytes, as Go's `<`. There is no `f`-style formatting: `go"${"%.2f".format(x)}"`.

```scala
import scalago.go.*

val name = "Grüße"
val g = go"Hi $name, ${1 + 1}!"  // or name.toGoString, or GoString(name)
g.byteLength                     // 14: UTF-8 bytes
g.toJavaString                   // "Hi Grüße, 2!"
g == go"Hi Grüße, 2!"            // true: contents are compared, on Go and on the JVM
```

## Scala data for Go code

Scala cannot declare Go struct types: Go sees a Scala class only through Go interfaces. Go code
that inspects values by reflection (`encoding/json`, templates) cannot see inside Scala objects,
and code that binds or scans into structs (gin's `ShouldBindJSON`, argus's `Bind[T]`, database
scanners) cannot fill one. Convert with `GoData`:

```scala
import scalago.go.*

final case class Todo(id: Long, title: String, tags: List[String])

val data = GoData(Todo(1, "write docs", List("docs")))  // map[string]any{"id": 1, "title": ..., "tags": []any{...}}
val config = GoData.map("server.port" -> 8080, "log.level" -> "info")  // GoMap[GoString, Any]
GoData.toScala(data)                                    // Map("id" -> 1, "tags" -> Vector("docs"), "title" -> "write docs")
```

- `GoData(x)`: case classes and maps become `map[string]any` (so `encoding/json` writes fields
  sorted by name, under their Scala names), other collections `[]any`, strings Go `string`s,
  integers Go `int`s, `Option`s their value or `nil`.
- `GoData.toScala`: maps become `Map[String, Any]` sorted by key, slices `Vector`s, values
  `String`, `Long`, `Double` or `Boolean`.
- Both copy. A Scala value passed to Go as `Any` without them stays the same object.
- To bind JSON, bind into `any` (`GoPtr.alloc[Any]`) and convert with `GoData.toScala`
  (`encoding/json` decodes numbers as `float64`, so they come back as `Double`s), or parse the raw
  body with a Scala JSON library ([gin example](../examples/gin.md)).

## Slices, maps and pointers

```scala
import scalago.go.*

val xs = GoSlice.of[Long](3, 1, 2)    // []int64
xs(0) = 30
val tail = xs.slice(1, 3)            // shares memory with xs, as in Go
val more = xs.appended(4)            // Go's append
val ints = GoSlice.make[GoInt](10)   // []int of length 10

val counts = GoMap.make[GoString, GoInt]          // map[string]int
counts(GoString("go")) = GoInt(1)
counts.get(GoString("go")).map(_.toLong)          // Some(1)
counts.remove(GoString("go"))                     // Go's delete

val p = GoPtr.to(42L)    // *int64
p.set(p.get + 1)
```

Facade types are already pointers; use `GoPtr` for others, such as `*int` or `*[]string`.
`GoComplex(1, 2)` has `+`, `-`, `*`, `/`, `real`, `imag` and `==`; `math/cmplx` has the rest.

## Channels

```scala
import scalago.go.*

val ch = GoChan.make[Int](capacity = 10)   // chan int32
ch.send(1)
ch.close()
ch.receive()                               // Some(1); None once closed and drained
for v <- ch do println(v)                  // Go's for v := range ch
```

`send` and `receive` block the calling thread (a goroutine); `trySend` and `tryReceive` do not.
In gears code, use the cancellable `awaitSend` and `awaitReceive` of `scalago.gears`
([Concurrency](../user/concurrency.md)).

## Element types

Elements are read and written in a **canonical** Scala view of the Go element type:

| Go element type                             | Scala view                                    |
|---------------------------------------------|-----------------------------------------------|
| `int`, `int64`, `uint`, `uint64`, `uintptr` | `Long` (or `GoInt`, `GoUint`, `GoUint64`, `GoUintptr`) |
| `int32`, `uint32`                           | `Int` (or `GoUint32`)                         |
| `int16`, `uint16`, `int8`, `uint8`          | `Short`, `Char`, `Byte` (or `GoByte`)         |
| `float32`, `float64`, `bool`                | `Float`, `Double`, `Boolean`                  |
| `string`, `complex128`, `struct{}`          | `GoString`, `GoComplex`, `Unit`               |
| anything else                               | the Go value: a facade, `GoSlice`, ..., `Any` |

The `Go*` numeric types are Scala numbers at run time; they give the Go type when creating data:
`GoSlice.make[GoInt](n)` is an `[]int`, `GoSlice.make[Long](n)` an `[]int64`, and both read as
`Long`s. A facade with the wrong view (`GoSlice[String]` for a Go `[]string`) is a link error that
names the right one.

## Limitations

- No `select` over several channels. Receive on each from its own thread, or write the `select`
  in a small Go function of a [local module](go-libraries.md).
- Unsynchronized concurrent writes to a `GoMap` end the process with Go's fatal error
  `concurrent map writes`, which no `catch` intercepts.
- Arrays, `complex64` and funcs inside Go data have no Scala view but `Any`: pass them on to Go,
  but do not use them from Scala.
- Named slice and map types (`type H map[string]any`) are facade classes, not `GoSlice`s or
  `GoMap`s. Build them from elements or entries (`gin.H("k" -> v)`) or from a `GoMap`; their
  elements cannot be read from Scala.
- No `GoType` instances for facade types, so no `GoSlice.make` or `GoMap.make` with facade
  elements; slices of them come from Go.
