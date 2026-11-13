---
title: Troubleshooting
---

# Troubleshooting

## Link errors

The linker keeps only reachable code, and fails if that code uses an API with no
implementation. The error names the missing API and the call chain that reaches it, read
bottom-up from your `main`:

```
Main.scala:21:35: static method java.time.LocalDate.of(III)... is not available
    in Main$.main(Array[java.lang.String])
```

If your code uses it, use another API, or call Go for it through a
[facade](../interop/facades.md). If a library uses it, that library, or that part of it, does not
work under scala-go.

Reachability over-approximates: a virtual call reaches every implementation of the method in
every instantiated class, so code that never runs can need a missing API. Then set
`def goMissingApis = "throw"`: the errors become warnings, and a missing API throws
`NoSuchMethodError` or `NoClassDefFoundError`, with a stack trace, only if it runs.

## Facade errors

A facade that does not match its Go declaration is a link error at the Scala call site, naming
the Go signature. For Go data, it names the expected Scala view: a Go `[]string` is
`GoSlice[GoString]`, not `GoSlice[String]` ([Go data](../interop/go-data.md#element-types)).

## Runtime errors

| Symptom                                                   | Cause and fix                                                     |
|-----------------------------------------------------------|-------------------------------------------------------------------|
| Uncaught exception                                        | JVM-style stack trace with Scala file names and lines; exit status 1 |
| Stack trace hides Go frames                               | `SCALAGO_TRACE=1` keeps every frame, with Go names and positions  |
| A Go panic in Go code called from Scala                   | becomes a Java exception: Go runtime errors map to their Java counterparts (`NullPointerException`, `ArithmeticException`, `ClassCastException`, ...), other panics to `RuntimeException` with the panic's message |
| Output missing after a crash                              | standard output is buffered; a fatal Go error (stack overflow, out of memory, deadlock) ends the program without flushing output written in the last 100 ms or running shutdown hooks |
| `NoSuchMethodError` from a pattern match on a type parameter | type tests with a `ClassTag` are unsupported ([Limitations](limitations.md#language-and-runtime)) |

## Debugging the generated code

The generated module (`mill app.goLink`, in `out/app/goLink.dest/go`) is ordinary Go: `go vet`,
`pprof` and Delve work on it, and `def goBuildFlags = Seq("-race")` adds Go's race detector. Go
compiler errors in generated code point at Scala lines; `def goLineDirectives = false` shows Go
positions instead, which helps when reporting a scala-go bug.

## Builds that need the network

`go` downloads `goRequires` modules, so the first build after adding one, and generating its
facades, needs network access; later builds use Go's module cache. Generating facades also needs
`go` on the `PATH`.
