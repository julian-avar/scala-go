---
title: Shipping binaries
---

# Shipping binaries

`mill app.goBuild` produces one executable, `out/app/goBuild.dest/app`. It needs no JVM and no
other files: the generated code and the runtime are pure Go.

## Release builds

```scala sc:nocompile
object app extends ScalaGoModule {
  def goBuildFlags = Seq("-trimpath", "-ldflags=-s -w")   // smaller, reproducible binaries
}
```

## Cross-compiling

Any platform Go supports works:

```scala sc:nocompile
object app extends ScalaGoModule {
  def goBuildEnv = Map("GOOS" -> "windows", "GOARCH" -> "amd64")
}
```

## Static binaries

Add `"CGO_ENABLED" -> "0"` to `goBuildEnv` for a statically linked binary, unless Go libraries
the program uses need cgo.

## Time zones

The binary embeds Go's time zone database (about 450 KB) only when the program can use named
time zones, so it also works where the system has no zoneinfo (Windows, minimal containers).

## Building elsewhere

`mill app.goLink` writes a self-contained Go module (`out/app/goLink.dest/go`): the generated
code, the runtime and a `go.mod`. Copy it to build with Go alone, for example in a Docker build
stage:

```sh
cd out/app/goLink.dest/go && go build -o app .
```

`goLocalModules` are referred to by absolute paths (`replace` directives in `go.mod`): copy them
too, and adjust the paths.

## Go libraries

To ship Scala code as a Go module for Go programs, see
[Calling Scala from Go](../interop/exports.md).
