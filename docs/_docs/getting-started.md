---
title: Getting started
---

# Getting started

## Requirements

- A JDK, 17 or later.
- Go 1.22 or later on the `PATH` (preferably a release the Go team supports, currently 1.25 or
  1.26).
- [Mill](https://mill-build.org) 1.x.

scala-go is not published to Maven Central yet. Until it is, publish it to your local Ivy
repository from a checkout of the repository:

```sh
./mill __.publishLocal
```

## A first project

A project is a Mill build with one `ScalaGoModule`:

```
hello/
├── build.mill
└── app/
    └── src/
        └── Main.scala
```

`build.mill`:

```scala sc:nocompile
//| mill-version: 1.1.8
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

`ScalaGoModule` sets the Scala version (3.9.0, the one the compiler plugin is built for) and adds
the plugin and the scala-go library.

## Running it

```sh
mill app.goRun Ada Grace   # compile, link, go build, run
mill app.run Ada Grace     # the same program on the JVM
```

| Command              | Result                                                        |
|----------------------|---------------------------------------------------------------|
| `mill app.goRun a b` | builds the executable and runs it with arguments `a b`        |
| `mill app.goBuild`   | the executable, `out/app/goBuild.dest/app`                    |
| `mill app.goLink`    | the generated Go module, `out/app/goLink.dest/go`              |
| `mill app.run a b`   | runs the program on the JVM, as with any Scala module          |

The plugin only adds `.goir` files next to the `.class` files, so the module still compiles,
runs and tests on the JVM. Test frameworks do not run as Go yet, so develop and test on the
JVM, and build and run the Go binary in CI as well.

## Next steps

- Add Scala libraries to `mvnDeps` as usual: see [Scala libraries](libraries/scala.md).
- Call Go packages: see [Calling Go](interop/facades.md).
- Ship the binary: see [Shipping binaries](user/shipping.md).
