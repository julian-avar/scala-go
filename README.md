# scala-go

Compile Scala 3 programs to native executables through Go. A Scala compiler plugin emits an
intermediate representation (`.goir`) next to the `.class` files. A whole-program linker turns
the program's IR into a Go module that `go build` compiles, together with IR for the Scala
standard library (recompiled from its TASTy) and a Java standard library written in Scala.

See [docs/design.md](docs/design.md) for the architecture, object model, status and roadmap.

**Status:** early, but real programs work. Classes, traits, objects, exceptions, pattern
matching, lambdas, `lazy val` and the standard collections all run, and Scala code can call Go
packages. Concurrency, `BigDecimal`, regex and reflection don't work yet.

## Using it with Mill

```scala
//| mvnDeps: ["io.github.scalago:mill-scalago_mill1_3:0.1.0-SNAPSHOT"]
import scalago.mill.ScalaGoModule

object app extends ScalaGoModule   // Scala 3.9.0
```

```sh
mill app.goRun arg1 arg2    # compile, link, go build, run
mill app.goLink             # just the Go module (out/app/goLink.dest/go)
```

Nothing is published yet: `mill __.publishLocal` publishes everything to the local Ivy
repository. Requires a JDK and Go ≥ 1.22 on the `PATH`.

Strings are `java.lang.String` (UTF-16), so programs behave as on the JVM. `scalago.go.GoString`
is the opt-in Go (UTF-8) string, like Scala Native's `CString`:

```scala
import scalago.go.*
val g = "Grüße".toGoString    // a Go string on Go, a byte array on the JVM
g.byteLength                  // 7
```

## Calling Go

Declare the Go API you need as a facade. The linker checks it against the real Go package and
converts values at the boundary:

```scala
import scalago.go.*

@GoImport("strings")
object strings:
  def Repeat(s: String, count: Int): String = native          // count is a Go int
  def FieldsFunc(s: String, f: Int => Boolean): Array[String] = native
  class Builder:                                              // strings.Builder (new = zero value)
    def WriteString(s: String): Int = native                  // the (int, error) error is checked
    def String(): String = native

@GoImport("strconv")
object strconv:
  def Atoi(s: String): Int = native                           // a non-nil error throws GoException
  def ParseBool(s: String): (Boolean, GoError) = native       // or keep the error

@GoImport("io")
object io:
  trait Writer:                                               // Go interfaces can be implemented in Scala
    def Write(p: Array[Byte]): Int                            // ([]byte) (int, error)
```

Go modules other than the standard library go in `goRequires` (`"module@version"`), or in
`goLocalModules` for local directories with a `go.mod` (see `example/`). Facade members throw on
the JVM. See [docs/design.md](docs/design.md#go-interop) for the conversion rules.

## Layout

| Path                 | What                                                                    |
|----------------------|-------------------------------------------------------------------------|
| `ir/`                | IR trees and their binary serialization                                 |
| `plugin/`            | Scala 3 compiler plugin (phase `genGoIR`)                               |
| `linker/`            | Linker: lambdas, reachability, Go codegen; CLI `scalago.linker.Main`    |
| `runtime/rt/`        | Go runtime package (strings, exceptions, natives), bundled in the linker |
| `runtime/goapi/`     | Helper the linker runs to read the APIs of Go packages                  |
| `javalib/`           | JDK classes in Scala (emitted as `java.*`) and Scala-library overrides  |
| `library/`           | User-facing APIs (`GoString`, Go facades: `GoImport`, `GoException`)    |
| `mill-build/src/`    | `ScalaGoModule`, used by this build and published as `mill-scalago`     |
| `example/`           | A program built with `ScalaGoModule`: `mill example.goRun Ada Grace`    |
| `tests/cases/`       | End-to-end programs with their expected output                          |

The `stdlib` module produces the Scala standard library's IR (`mill stdlib.ir`).

## Development

```sh
mill linker.test                 # end-to-end tests (compile, run on JVM and as Go, compare)
cd runtime && go test ./...      # runtime unit tests
mill example.goRun               # the example, through ScalaGoModule
```

Each directory in `tests/cases/` holds a program and an `expected.txt`. Cases with a
`go-only.txt` (they call Go) skip the JVM, subdirectories with a `go.mod` are local Go modules, and
a `link-errors.txt` lists errors that linking must report instead of running. The entry point is
`Main` unless `main.txt` names another one. The test compiles the program with the plugin and
runs it on the JVM. It then links it to Go, runs `go vet` and `go build`, and runs the binary.
Both outputs must equal `expected.txt`. The work directory of a failing case is kept under
`/tmp/scalago-<case>-*` for inspection.
