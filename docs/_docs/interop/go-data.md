---
title: Go data
---

# Go data

The types of `scalago.go` are Go data, shared with Go code without copying. Scala's own types
are converted at the boundary instead: a `String` is transcoded, an `Array[String]` copied.

| Scala         | Go        |
|---------------|-----------|
| `GoString`    | `string`  |
| `GoSlice[T]`  | `[]T`     |
| `GoMap[K, V]` | `map[K]V` |
| `GoChan[T]`   | `chan T`  |
| `GoPtr[T]`    | `*T`      |
| `GoError`     | `error`   |

They also run on the JVM with the same semantics, so code using them can be tested there.

## GoString

`GoString` is Go's string: immutable UTF-8 bytes, passed to Go without conversion (like Scala
Native's `CString`). Scala's `String` stays `java.lang.String` (UTF-16), so programs behave as on
the JVM. The `go` interpolator works like `s"..."`: same escapes, checked at compile time.

```scala
import scalago.go.*

val name = "Grüße"
val g = go"Hi $name, ${1 + 1}!"  // or name.toGoString, or GoString(name)
g.byteLength                     // 14: UTF-8 bytes
g.toJavaString                   // "Hi Grüße, 2!"
g == go"Hi Grüße, 2!"            // true: contents are compared, on Go and on the JVM
```

- `GoString` arguments are inserted as they are; others are rendered as `String.valueOf` would.
- On Go, literal parts are string constants and an interpolation is one Go concatenation, with no
  UTF-16 conversion.
- `Ordering[GoString]` orders by bytes, as Go's `<`.
- No `f`-style formatting. Format into a `String` first: `go"${"%.2f".format(x)}"`.

## Scala data for Go code

Go code that inspects values by reflection (`encoding/json`, templates, configuration libraries)
cannot see inside Scala objects. Convert with `GoData`:

```scala
import scalago.go.*

final case class Todo(id: Long, title: String, tags: List[String])

val data = GoData(Todo(1, "write docs", List("docs")))  // map[string]any{"id": 1, "title": ..., "tags": []any{...}}
val config = GoData.map("server.port" -> 8080, "log.level" -> "info")  // GoMap[GoString, Any]
GoData.toScala(data)                                    // Map("id" -> 1, "tags" -> Vector("docs"), "title" -> "write docs")
```

- `GoData(x)`: case classes and maps become `map[string]any`, other collections `[]any`,
  strings Go `string`s, integers Go `int`s, `Option`s their value or `nil`.
- `GoData.toScala`: Go maps become `Map[String, Any]` sorted by key, slices `Vector`s, and values
  `String`, `Long`, `Double` or `Boolean`.
- Both copy. A Scala value passed to Go as `Any` without them stays the same object, and comes
  back as itself.

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

- `GoMap` iteration order is Go's, which is randomized. On the JVM it is insertion order.
- Facade types are already pointers. Use `GoPtr` for other types, such as `*int` or `*[]string`.

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
In gears code, use the cancellable `awaitSend` and `awaitReceive` of `scalago.gears` (see
[Concurrency](../user/concurrency.md)).

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

Creating Go data needs the Go element type: `GoSlice.make[GoInt](n)` makes an `[]int`,
`GoSlice.make[Long](n)` an `[]int64`, and reading either gives `Long`s. `GoInt`, `GoByte` and
the other Go numeric types are Scala numbers at run time; they matter only as type arguments and
in facades. A facade with the wrong view (`GoSlice[String]` for a Go `[]string`) is a link error
that names the right one.

## Limitations

- No `select` over several channels.
- Unsynchronized concurrent writes to a `GoMap` end the process with Go's fatal error
  `concurrent map writes`, which no `catch` intercepts.
- Go arrays, complex numbers and funcs inside Go data have no Scala view but `Any`: pass them on
  to Go, but not use them from Scala.
- Named slice and map types (`type H map[string]any`) are facade classes, not `GoSlice`s or
  `GoMap`s. Their elements cannot be read from Scala.
- `GoData` makes maps, not structs: Go code sees a case class's fields under their Scala names,
  and `encoding/json` writes them sorted by name. `encoding/json` decodes numbers into `any` as
  `float64`, so `GoData.toScala` gives them back as `Double`s.

[Limitations](../user/limitations.md#go-interop) lists all limitations of Go interop.
