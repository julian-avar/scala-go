---
title: Go libraries
---

# Go libraries

To use a Go module, require it and [generate facades](generated-facades.md) for the packages you
use:

```scala sc:nocompile
object app extends ScalaGoModule {
  // Modules from the Go module proxy, as module@version.
  def goRequires = Seq("github.com/google/uuid@v1.6.0")
  // Local modules: directories with their own go.mod.
  def goLocalModules = Task.Sources(moduleDir / "go" / "textstats")
  def goFacadePackages = Seq("github.com/google/uuid", "example.com/textstats")
}
```

- `go` downloads required modules through the module proxy and its cache, so the first build
  needs network access. Go's `go.sum` checks apply.
- A local module is named by the module path in its `go.mod`, also in hand-written facades:
  `@GoImport("example.com/textstats")`.
- A module may require a newer Go than 1.27. The generated `go.mod` then asks for it too, and Go's
  default `GOTOOLCHAIN=auto` downloads it.

The facades follow the library's Go API ([Calling Go](facades.md#declaring-types) describes how
its values behave). The [examples](../examples/index.md) use bubbletea, gin and argus.

## cgo

A library that needs cgo needs a C compiler, and rules out `CGO_ENABLED=0` builds unless it has a
fallback without cgo. [argus](../examples/argus.md) imports `mattn/go-sqlite3`: with a C compiler,
the first build compiles SQLite; with `CGO_ENABLED=0`, it builds a stub, which suits programs that
use no SQLite. Facades of packages that use cgo directly, and stack traces through C calls, are
untested.
