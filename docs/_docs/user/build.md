---
title: Build setup
---

# Build setup

scala-go builds with [Mill](https://mill-build.org). The Mill plugin, `mill-scalago`, provides
two module traits:

- `ScalaGoModule`: a program, linked into a Go executable.
- `ScalaGoLibraryModule`: a library for Go code, linked into a Go module (see
  [Calling Scala from Go](../interop/exports.md)).

```scala sc:nocompile
//| mvnDeps: ["io.github.scalago:mill-scalago_mill1_3:0.1.0-SNAPSHOT"]
import scalago.mill.ScalaGoModule

object app extends ScalaGoModule {
  def mvnDeps = Seq(mvn"com.lihaoyi::upickle:4.4.1")
  def goBuildFlags = Seq("-trimpath", "-ldflags=-s -w")
}
```

Both are `ScalaModule`s: `compile`, `run`, `test`, `mvnDeps` and the other usual tasks work as
for any Scala 3 module.

## Tasks

| Task          | Result                                                                      |
|---------------|-----------------------------------------------------------------------------|
| `goLink`      | the generated Go module (`out/<module>/goLink.dest/go`)                      |
| `goBuild`     | the executable (`out/<module>/goBuild.dest/app`); for a library, `go vet`s it |
| `goRun args*` | builds and runs the executable; standard input and output are the terminal's |

## Settings

Override these in the module. Each is a Mill task, so `def goRequires = Seq(...)` works, and so
does a computed `Task { ... }`.

### Go module

| Setting             | Default        | Meaning                                                                       |
|---------------------|----------------|-------------------------------------------------------------------------------|
| `goModulePath`      | `"scalagoapp"` | the module path of the generated Go module (for libraries: the import path)  |
| `goPackageName`     | `""`           | package name of a library's root package (`""`: the module path's last element) |
| `scalaGoMainObject` | `finalMainClass()` | the object whose `main` starts the program                                |

### Go dependencies

| Setting            | Default | Meaning                                                                          |
|--------------------|---------|----------------------------------------------------------------------------------|
| `goRequires`       | none    | Go modules the program uses, as `"module@version"` (`"github.com/google/uuid@v1.6.0"`). The standard library needs nothing. |
| `goLocalModules`   | none    | directories of local Go modules, each with its own `go.mod`: `Task.Sources(moduleDir / "go" / "mylib")` |
| `goFacadePackages` | none    | Go packages to generate Scala facades for (see [Generated facades](../interop/generated-facades.md)) |
| `goFacadesPackage` | `"gofacades"` | the Scala package of the generated facades                                |

### Linking

| Setting            | Default   | Meaning                                                                       |
|--------------------|-----------|-------------------------------------------------------------------------------|
| `goMissingApis`    | `"error"` | `"error"`: a reachable use of an API scala-go does not implement fails the link. `"throw"`: it is a warning, and the use throws `NoSuchMethodError`/`NoClassDefFoundError` if it runs. See [Troubleshooting](troubleshooting.md). |
| `goLineDirectives` | `true`    | Go line directives, so that stack traces and Go compiler errors give Scala positions. `false` shows positions in the generated Go code. |

### Building

| Setting        | Default | Meaning                                                                  |
|----------------|---------|--------------------------------------------------------------------------|
| `goBuildEnv`   | none    | environment of `go build`: `Map("GOOS" -> "linux", "GOARCH" -> "arm64")`, `"CGO_ENABLED" -> "0"` |
| `goBuildFlags` | none    | extra `go build` flags: `Seq("-trimpath", "-ldflags=-s -w")`              |

### Versions

| Setting          | Default            | Meaning                                                           |
|------------------|--------------------|-------------------------------------------------------------------|
| `scalaVersion`   | `3.9.0`            | must be the version the compiler plugin was built for             |
| `scalaGoVersion` | the plugin's version | the version of the scala-go artifacts (compiler plugin, linker, libraries) |

## How a build runs

1. `compile` runs the Scala compiler with the scala-go plugin, which writes `.goir` files next
   to the `.class` files.
2. Scala library dependencies are compiled to IR from the TASTy in their jars, once per
   version (`scalaGoDependencyIR`, cached by Mill).
3. `goLink` runs the linker on the IR of the program, its dependencies, the Scala standard
   library and javalib, the Java standard library of scala-go. It keeps only the code that can
   run, and writes a Go module.
4. `goBuild` runs `go build` on that module.

The generated module is ordinary Go: `go vet`, `go build -race`, `pprof` and Delve work on it.
