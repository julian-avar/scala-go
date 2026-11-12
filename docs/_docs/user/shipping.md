---
title: Shipping binaries
---

# Shipping binaries

`mill app.goBuild` produces one executable, `out/app/goBuild.dest/app`, that needs no JVM and no
other files.

## Release builds

```scala sc:nocompile
object app extends ScalaGoModule {
  def goBuildFlags = Seq("-trimpath", "-ldflags=-s -w")   // smaller, reproducible binaries
}
```

The repository's small examples are 16 to 50 MB with debug information, which `-s -w` drops.

## Cross-compiling

```scala sc:nocompile
object app extends ScalaGoModule {
  def goBuildEnv = Map("GOOS" -> "linux", "GOARCH" -> "arm64")
}
```

Tested: Linux and macOS on amd64 and arm64. Others: [Limitations](limitations.md#platforms).

## Static binaries

Add `"CGO_ENABLED" -> "0"` to `goBuildEnv` for a statically linked binary, unless a Go library
needs cgo ([cgo](../interop/go-libraries.md#cgo)).

## Time zones

When the program can use named time zones, the binary embeds Go's time zone database (about
450 KB), so it works without system zoneinfo (Windows, minimal containers).

## Building elsewhere

`mill app.goLink` writes a self-contained Go module (generated code, runtime, `go.mod`) to
`out/app/goLink.dest/go`. Copy it to build with Go alone, as in a Docker build stage:

```sh
cd out/app/goLink.dest/go && go build -o app .
```

`go.mod` refers to `goLocalModules` by absolute paths (`replace` directives): copy those too,
and adjust the paths. To ship Scala code as a Go module for Go programs, see
[Calling Scala from Go](../interop/exports.md).
