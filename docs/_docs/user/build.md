---
title: Build setup
---

# Build setup

scala-go builds with [Mill](https://mill-build.org). The plugin `mill-scalago` provides two
`ScalaModule` traits (`compile`, `run`, `test`, `mvnDeps` work as usual): `ScalaGoModule` links
into a Go executable, `ScalaGoLibraryModule` into a Go module for Go code to import
([Calling Scala from Go](../interop/exports.md)).

```scala sc:nocompile
//| mvnDeps: ["io.github.scalago:mill-scalago_mill1_3:0.1.0-SNAPSHOT"]
import scalago.mill.ScalaGoModule

object app extends ScalaGoModule {
  def mvnDeps = Seq(mvn"com.lihaoyi::upickle:4.4.1")
  def goBuildFlags = Seq("-trimpath", "-ldflags=-s -w")
}
```

## Tasks

| Task          | Result                                                                        |
|---------------|-------------------------------------------------------------------------------|
| `goLink`      | the generated Go module, `out/<module>/goLink.dest/go`                        |
| `goBuild`     | the executable, `out/<module>/goBuild.dest/app`; for a library, runs `go vet ./...` on the module |
| `goRun args*` | builds and runs the executable with Mill's standard input and output; a non-zero exit status fails the task |

`goRun` has no terminal (Mill runs tasks in a background daemon): run terminal UIs such as the
[gotui example](../examples/gotui.md) from `goBuild`'s executable.

## Settings

Each setting is a Mill task: override it with a value (`def goRequires = Seq(...)`) or a
`Task { ... }`.

| Setting             | Default            | Meaning                                                          |
|---------------------|--------------------|------------------------------------------------------------------|
| `goModulePath`      | `"scalagoapp"`     | module path of the generated Go module; a library's import path  |
| `goPackageName`     | `""`               | a library's root package name; `""` is the module path's last element |
| `scalaGoMainObject` | `finalMainClass()` | the object whose `main` starts the program                       |
| `goRequires`        | none               | Go modules used, as `"module@version"` (`"github.com/google/uuid@v1.6.0"`); the standard library needs none |
| `goLocalModules`    | none               | directories of local Go modules, each with a `go.mod`: `Task.Sources(moduleDir / "go" / "mylib")` |
| `goFacadePackages`  | none               | Go packages to generate Scala facades for ([Generated facades](../interop/generated-facades.md)) |
| `goFacadesPackage`  | `"gofacades"`      | Scala package of the generated facades                           |
| `goMissingApis`     | `"error"`          | `"error"`: a reachable use of an unimplemented API fails the link. `"throw"`: a warning, and the use throws `NoSuchMethodError`/`NoClassDefFoundError` if it runs ([Troubleshooting](troubleshooting.md#link-errors)) |
| `goLineDirectives`  | `true`             | emit Go line directives, so stack traces and Go compiler errors give Scala positions; `false` gives generated-Go positions |
| `goBuildEnv`        | none               | environment of `go build`: `Map("GOOS" -> "linux", "GOARCH" -> "arm64", "CGO_ENABLED" -> "0")` |
| `goBuildFlags`      | none               | extra `go build` flags: `Seq("-trimpath", "-ldflags=-s -w")`, `Seq("-race")` |
| `scalaVersion`      | `3.9.0`            | must be the version the compiler plugin was built for            |
| `scalaGoVersion`    | the plugin's       | version of the scala-go artifacts (plugin, linker, libraries)    |

## How a build runs

1. `compile` runs scalac with the scala-go plugin, which writes `.goir` files next to the
   `.class` files.
2. `scalaGoDependencyIR` compiles Scala library dependencies to IR from their jars' TASTy, once
   per version (cached by Mill).
3. `goLink` links the IR of the program, its dependencies, the Scala standard library and
   javalib, keeping only reachable code, into a Go module.
4. `goBuild` runs `go build` on it.

The generated module is ordinary Go: `go vet`, `go build -race`, `pprof` and Delve work on it
([Troubleshooting](troubleshooting.md#debugging-the-generated-code)).
