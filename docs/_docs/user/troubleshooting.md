---
title: Troubleshooting
---

# Troubleshooting

## Link errors

The linker keeps only reachable code, and fails if that code uses an API with no
implementation. The error names the missing API and the call chain that reaches it:

```
Main.scala:21:35: static method java.time.LocalDate.of(III)... is not available
    in Main$.main(Array[java.lang.String])
```

- Read the chain bottom-up: it starts at your `main` and ends at the missing API.
- Used by your code: use another API, or call Go for it through a
  [facade](../interop/facades.md).
- Used by a library: that library, or that part of it, does not work under scala-go.

Reachability over-approximates. A virtual call reaches every implementation of the method in
every instantiated class, so code that never runs can need a missing API. In that case set:

```scala sc:nocompile
def goMissingApis = "throw"
```

The errors become warnings. A missing API throws `NoSuchMethodError` or `NoClassDefFoundError`,
with a stack trace, only if it runs.

## Facade errors

A facade that does not match its Go declaration is a link error at the Scala call site, naming
the Go signature. For Go data, the error names the expected Scala view: a Go `[]string` is
`GoSlice[GoString]`, not `GoSlice[String]` ([Go data](../interop/go-data.md#element-types)).

## Runtime errors

| Symptom                                                   | Cause and fix                                                     |
|-----------------------------------------------------------|-------------------------------------------------------------------|
| Uncaught exception                                        | JVM-style stack trace with Scala file names and lines; exit status 1 |
| Stack trace hides Go frames                               | run with `SCALAGO_TRACE=1` to keep every frame, with Go names and positions |
| A Go panic in Go code called from Scala                   | becomes a Java exception: Go runtime errors map to their Java counterparts (`NullPointerException`, `ArithmeticException`, `ClassCastException`, ...), other panics to `RuntimeException` with the panic's message |
| Output missing after a crash                              | standard output is buffered; a signal (Ctrl-C, `docker stop`) or a fatal Go error (stack overflow, out of memory, deadlock) ends the program without flushing it or running shutdown hooks |
| `NoSuchMethodError` from a pattern match on a type parameter | type tests with a `ClassTag` are unsupported ([Limitations](limitations.md#language-and-runtime)) |
| `f"..."` or `String.format` throws or prints something else | some conversions are unsupported or differ ([Limitations](limitations.md#jdk-and-scala-libraries), [Differences](differences.md#text)) |

## Debugging the generated code

- The generated module (`mill app.goLink`, in `out/app/goLink.dest/go`) is ordinary Go:
  `go vet`, `pprof` and Delve work on it.
- Go compiler errors in generated code point at Scala lines. `def goLineDirectives = false` shows
  Go positions instead, which helps when reporting a scala-go bug.
- `def goBuildFlags = Seq("-race")` builds with Go's race detector.

## Builds that need the network

`go` downloads `goRequires` modules, so the first build after adding one, and generating facades
for it, needs network access. Later builds use Go's module cache. Generating facades also needs
`go` on the `PATH`.
