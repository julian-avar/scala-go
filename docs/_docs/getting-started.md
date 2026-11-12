---
title: Getting started
---

# Getting started

## Requirements

- JDK 25.
- Go 1.27 on the `PATH`.
- [Mill](https://mill-build.org) 1.x.

scala-go is not on Maven Central. Publish it to your local Ivy repository from a checkout:

```sh
./mill __.publishLocal
```

## A first project

```
hello/
├── build.mill
└── app/
    └── src/
        └── Main.scala
```

`build.mill`:

```scala sc:nocompile
//| mill-version: 1.3.1
//| mvnDeps: ["io.github.scalago:mill-scalago_mill1_3:0.1.0-SNAPSHOT"]
package build

import mill.*, scalalib.*
import scalago.mill.ScalaGoModule

object app extends ScalaGoModule
```

`app/src/Main.scala`:

```scala
@main def hello(names: String*): Unit =
  val who = if names.isEmpty then "world" else names.mkString(", ")
  println(s"Hello, $who!")
```

`ScalaGoModule` sets `scalaVersion` to 3.9.0 (the version the compiler plugin is built for) and
adds the plugin and the scala-go library.

## Running it

| Command              | Result                                                     |
|----------------------|------------------------------------------------------------|
| `mill app.goRun a b` | compiles, links, runs `go build`, runs the executable with arguments `a b` |
| `mill app.goBuild`   | the executable, `out/app/goBuild.dest/app`                 |
| `mill app.goLink`    | the generated Go module, `out/app/goLink.dest/go`          |
| `mill app.run a b`   | runs the same program on the JVM                           |

The plugin only adds `.goir` files next to the `.class` files, so the module still compiles,
runs and tests on the JVM. Test frameworks do not run as Go: test on the JVM, and build and run
the Go binary in CI.

## Next steps

- [Scala libraries](libraries/scala.md): add them to `mvnDeps` as usual.
- [Calling Go](interop/facades.md): use Go packages.
- [Shipping binaries](user/shipping.md): release builds and cross-compiling.
- [Examples](examples/index.md): complete programs.
- [Limitations](user/limitations.md): what does not work yet.
