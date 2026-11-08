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

```scala sc:nocompile
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

Most programs need few of these: the JDK's own APIs (`java.nio.file`, `java.time`, ...) work
and also run on the JVM. Go's packages are useful when you pass their values to other Go code,
or want Go's exact behaviour. The [API documentation](scalago.gostd) lists every member.
