---
title: Go standard library
---

# Go standard library

`scalago-gostd` holds [generated facades](../interop/generated-facades.md) of 21 Go standard
library packages, ready to call:

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

Each package is an object of `scalago.gostd`, named after the Go package:

| Go packages                                              | Objects                            |
|----------------------------------------------------------|------------------------------------|
| `bufio`, `bytes`, `strings`, `strconv`, `unicode`, `unicode/utf8` | `bufio`, `bytes`, `strings`, `strconv`, `unicode`, `utf8` |
| `fmt`, `errors`, `io`, `os`                              | `fmt`, `errors`, `io`, `os`        |
| `path`, `path/filepath`, `net/url`                       | `path`, `filepath`, `url`          |
| `encoding/json`, `encoding/base64`, `encoding/hex`       | `json`, `base64`, `hex`            |
| `context`, `sync`, `time`, `sort`, `math`                | `context`, `sync`, `time`, `sort`, `math` |

The types (not the functions) of `io/fs`, `reflect` and `syscall` that these packages use are
included too, as objects `fs`, `reflect`, `syscall`. When a module depends on `scalago-gostd`,
generated facades of other Go packages reuse these types instead of generating their own.

Most programs need few of these: the JDK's APIs (`java.nio.file`, `java.time`, ...) work and also
run on the JVM. Use Go's packages to pass their values to other Go code, or for Go's exact
behaviour. The [API documentation](scalago.gostd) lists every member.

## Limitations

- **Only these 21 packages.** Generate others (`net/http`, `os/exec`, `regexp`, `crypto/...`,
  `log/slog`, `database/sql`, `encoding/csv`, ...) with `goFacadePackages`, as the
  [gin example](../examples/gin.md) does for `net` and `net/http`.
- **No `slices`, `maps`, `cmp`:** most of their functions are generic with type-set constraints,
  which generated facades leave out. Declare the instantiations you need in a
  [hand-written facade](../interop/facades.md#generic-functions).
- Members Scala cannot express are left out, as in any
  [generated facade](../interop/generated-facades.md#what-is-left-out).
- Like all facades, they throw on the JVM.
