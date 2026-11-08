---
title: Go standard library
---

# Go standard library

`scalago-gostd` has the [generated facades](../interop/generated-facades.md) of the most-used
Go standard library packages, so programs can call them without generating facades themselves:

```scala sc:nocompile
object app extends ScalaGoModule {
  def mvnDeps = Seq(mvn"io.github.scalago::scalago-gostd:${scalaGoVersion()}")
}
```

```scala
import scalago.gostd.{strings, strconv}

@main def demo(): Unit =
  println(strings.Repeat("go", 3))
  println(strconv.Quote("tab\there"))
```

Each package is an object of `scalago.gostd`, named as the Go package:

| Go packages                                              | Objects                            |
|----------------------------------------------------------|------------------------------------|
| `bufio`, `bytes`, `strings`, `strconv`, `unicode`, `unicode/utf8` | `bufio`, `bytes`, `strings`, `strconv`, `unicode`, `utf8` |
| `fmt`, `errors`, `io`, `os`                              | `fmt`, `errors`, `io`, `os`        |
| `path`, `path/filepath`, `net/url`                       | `path`, `filepath`, `url`          |
| `encoding/json`, `encoding/base64`, `encoding/hex`       | `json`, `base64`, `hex`            |
| `context`, `sync`, `time`, `sort`, `math`                | `context`, `sync`, `time`, `sort`, `math` |

The types of `io/fs`, `reflect` and `syscall` that these packages use are included too (objects
`fs`, `reflect`, `syscall`), without their functions.

[Generated facades](../interop/generated-facades.md) of other Go packages use these types when
the module depends on `scalago-gostd`, instead of generating their own.

Most programs need few of these: the JDK's own APIs (`java.nio.file`, `java.time`, ...) work
and also run on the JVM. Go's packages are useful when you pass their values to other Go code,
or want Go's exact behaviour. The [API documentation](scalago.gostd) lists every member.

## Limitations

- Only the 21 packages above. Others (`net/http`, `os/exec`, `regexp`, `crypto/...`,
  `log/slog`, `database/sql`, `encoding/csv`, ...) need `goFacadePackages`, as the
  [gin example](../examples/gin.md) does for `net` and `net/http`.
- `slices`, `maps` and `cmp` are not included: most of their functions are generic with type-set
  constraints, which generated facades leave out. Declare the instantiations you need in a
  [hand-written facade](../interop/facades.md#generic-functions).
- The generated facades leave out what Scala cannot express, as for any
  [generated facade](../interop/generated-facades.md#what-is-left-out).
- Like all facades, they throw on the JVM.
