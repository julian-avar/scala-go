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

Binaries are large: the repository's small examples are 16 to 50 MB with debug information.
`-ldflags=-s -w` drops it.

## Cross-compiling

```scala sc:nocompile
object app extends ScalaGoModule {
  def goBuildEnv = Map("GOOS" -> "linux", "GOARCH" -> "arm64")
}
```

Linux and macOS on amd64 and arm64 are tested. Other targets:
[Limitations: platforms](limitations.md#platforms).

## Static binaries

Add `"CGO_ENABLED" -> "0"` to `goBuildEnv` for a statically linked binary. Go libraries that need
cgo rule this out ([Limitations: Go interop](limitations.md#go-interop)).

## Time zones

When the program can use named time zones, the binary embeds Go's time zone database (about
450 KB). It then also works where the system has no zoneinfo (Windows, minimal containers).

## Building elsewhere

`mill app.goLink` writes a self-contained Go module to `out/app/goLink.dest/go`: the generated
code, the runtime and a `go.mod`. Copy it to build with Go alone, for example in a Docker build
stage:

```sh
cd out/app/goLink.dest/go && go build -o app .
```

`go.mod` refers to `goLocalModules` by absolute paths (`replace` directives). Copy those modules
too, and adjust the paths.

## Go libraries

To ship Scala code as a Go module for Go programs, see
[Calling Scala from Go](../interop/exports.md).
