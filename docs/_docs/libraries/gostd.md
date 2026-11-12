---
title: Go standard library
---

# Go standard library

`scalago-gostd` holds [generated facades](../interop/generated-facades.md) of the whole Go
standard library: every package that Go code outside the standard library can import (`go list
std`, without `internal`, `vendor` and `unsafe`), about 180 packages.

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

Each package is an object of `scalago.gostd`, named after the Go package (`net/http` is `http`,
`crypto/sha256` is `sha256`). Packages that share a name get the name a Go import alias would
give them:

| Go package         | Object          | Go package       | Object         |
|--------------------|-----------------|------------------|----------------|
| `encoding/json`    | `json`          | `encoding/json/v2` | `jsonv2`     |
| `math/rand`        | `mathrand`      | `math/rand/v2`   | `randv2`       |
| `crypto/rand`      | `cryptorand`    |                  |                |
| `text/template`    | `texttemplate`  | `html/template`  | `htmltemplate` |
| `text/scanner`     | `textscanner`   | `go/scanner`     | `goscanner`    |
| `runtime/pprof`    | `runtimepprof`  | `net/http/pprof` | `httppprof`    |

Import the objects you use: `import scalago.gostd.*` would hide Scala's own `io`, `math`,
`reflect` and `runtime` packages behind Go's.

Most programs need few of these: the JDK's APIs (`java.nio.file`, `java.time`, ...) work and also
run on the JVM. Use Go's packages for what the JDK lacks here, to pass their values to other Go
code, or for Go's exact behaviour. When a module depends on `scalago-gostd`, generated facades of
other Go packages reuse its types instead of generating their own. The
[API documentation](scalago.gostd) lists every member.

The facades are generated from the API of the Go that builds `scalago-gostd` (1.27, on Linux).
Packages whose API depends on the platform (`syscall`, parts of `os` and `net`) have the members
of Linux/amd64; a member another platform lacks is a Go compile error where it is used.

## Checked

`mill gostd.usage.goBuild` links and builds a program that uses every member of every facade
(in code that never runs), so the linker accepts each of them and Go compiles what it emits.

## Limitations

What generated facades leave out ([details](../interop/generated-facades.md#what-is-left-out)),
with a `// not generated:` comment saying why: about 40 of 16,700 members.

- **`unsafe.Pointer`** (`sync/atomic.LoadPointer`, `reflect.Value.UnsafePointer`).
- **Methods with pointer receivers of non-struct types** (`net.IP.UnmarshalText`,
  `slog.Level.UnmarshalJSON`): Scala holds these values as copies, which the method would change.
- What else Scala cannot express: funcs inside Go data (`http.Server.TLSNextProto`), variadic func
  types (`url.JoinPath` as a field), returned funcs with more than 3 parameters, arrays inside Go
  data, unnamed struct types other than `struct{}`, setters of fields of unexported types.

Also:

- Like all facades, they throw on the JVM.
- **Generics:** generic functions are generic methods (`slices.Max[E](x: GoSlice[E]): E`), generic
  types generic classes (`atomic.Pointer[T]`) or aliases (`iter.Seq[V]`, a Scala function). Type
  arguments are canonical views (`GoInt`, `GoString`); Go checks constraints when it compiles.
  [Generics](../interop/facades.md#generics).
- **Values of types Scala cannot name** (unexported, `internal`): `binary.BigEndian` is a
  `binary.ByteOrder`, `json/v2`'s options are `Any`; calls taking them use reflection.
- **Arrays are copies:** `sha256.Sum256(data)` returns a `GoSlice[GoByte]` of a copy of the Go
  `[32]byte`; an array parameter copies from a slice, which must be long enough (else Go panics).
- **`complex128`** is [`GoComplex`](../interop/go-data.md#complex-numbers).
- **Func types' results** are as functions': a trailing `error` is thrown (by the Scala
  function, or by a Go func returned to Scala), several results are a tuple.
- **Structural to nominal.** A generated type extends every generated interface whose methods
  it has (`class Buffer extends io.Writer, io.Reader, fmt.Stringer, ...`).
