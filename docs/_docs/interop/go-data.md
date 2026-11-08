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

```scala
import scalago.go.*

val g = "Grüße".toGoString     // or GoString("Grüße")
g.byteLength                   // 7: UTF-8 bytes
g.toJavaString                 // "Grüße"
g.sameAs(GoString("Grüße"))    // true: compare contents with sameAs, not ==
```

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
