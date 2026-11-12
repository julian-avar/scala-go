---
title: Go standard library
---

# Go standard library

`scalago-gostd` holds [generated facades](../interop/generated-facades.md) of the whole Go
standard library: every package that code outside it can import (`go list std` without
`internal`, `vendor` and `unsafe`), about 180 packages.

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

Each package is an object of `scalago.gostd` named after the Go package (`net/http` is `http`).
Packages that share a name get the name a Go import alias would give them:

| Go package         | Object          | Go package       | Object         |
|--------------------|-----------------|------------------|----------------|
| `encoding/json`    | `json`          | `encoding/json/v2` | `jsonv2`     |
| `math/rand`        | `mathrand`      | `math/rand/v2`   | `randv2`       |
| `crypto/rand`      | `cryptorand`    |                  |                |
| `text/template`    | `texttemplate`  | `html/template`  | `htmltemplate` |
| `text/scanner`     | `textscanner`   | `go/scanner`     | `goscanner`    |
| `runtime/pprof`    | `runtimepprof`  | `net/http/pprof` | `httppprof`    |

The JDK's APIs (`java.nio.file`, `java.time`, ...) also run on the JVM; use Go's packages for what
the JDK lacks here, to pass values to other Go code, or for Go's exact behaviour. Generated facades
of other packages reuse `scalago-gostd`'s types. The [API documentation](scalago.gostd) lists
every member; `mill gostd.usage.goBuild` links and builds a program that uses them all.

## Limitations

- About 40 of 16,700 members are left out, each with a `// not generated:` comment:
  `unsafe.Pointer` (`sync/atomic.LoadPointer`), pointer methods of non-struct types
  (`net.IP.UnmarshalText`, `slog.Level.UnmarshalJSON`), funcs inside Go data
  (`http.Server.TLSNextProto`), variadic func types (`url.JoinPath` as a field), and the other
  shapes of [What is left out](../interop/generated-facades.md#what-is-left-out), which also
  covers values of types Scala cannot name (`binary.BigEndian`, `json/v2`'s options).
- The facades are generated from Go 1.27's API on Linux/amd64. In packages whose API depends on
  the platform (`syscall`, parts of `os` and `net`), a member another platform lacks is a Go
  compile error where it is used.
- `import scalago.gostd.*` hides Scala's `io`, `math`, `reflect` and `runtime` packages: import
  the objects you use.
