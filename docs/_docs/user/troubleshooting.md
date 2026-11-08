---
title: Troubleshooting
---

# Troubleshooting

## Link errors

The linker includes only the code that can run, and fails if that code uses something with no
implementation. The error names the missing API and shows how it is reached:

```
Main.scala:21:35: static method java.time.LocalDate.of(III)... is not available
    in Main$.main(Array[java.lang.String])
```

- Read the chain bottom-up: it starts at your `main` and ends at the missing API.
- If the API is used by your code, use another one, or call Go for it with a
  [facade](../interop/facades.md).
- If it is used by a library, the library (or that part of it) does not work under scala-go.

Reachability over-approximates: a virtual call reaches every implementation of the method in
every instantiated class, so code that never runs can need an API that does not exist. In that
case set

```scala sc:nocompile
def goMissingApis = "throw"
```

The errors become warnings, and the missing APIs throw `NoSuchMethodError` or
`NoClassDefFoundError` (with a stack trace) only if they run.

## Facade errors

A facade that does not match its Go declaration is a link error at the Scala call site, naming
the Go signature. For Go data, the error names the expected Scala view: use `GoSlice[GoString]`
for a Go `[]string`, not `GoSlice[String]` (see [Go data](../interop/go-data.md#element-types)).

## Runtime errors

- Uncaught exceptions print JVM-style stack traces with Scala file names and line numbers, and
  the program exits with status 1.
- `SCALAGO_TRACE=1` in the environment keeps every Go frame in stack traces.
- Go panics in Go code called from Scala become `RuntimeException`s with the panic's message.
- **Output missing after a crash?** Standard output is buffered. A signal (Ctrl-C, `docker
  stop`) or a fatal Go error (stack overflow, out of memory, a deadlock) ends the program
  without flushing it, and without running shutdown hooks.
- **`NoSuchMethodError` from a pattern match** on a type parameter with a `ClassTag`: such type
  tests are not supported yet (see [Differences from the JVM](differences.md)).
- **`f"..."` or `String.format` throws or prints something else:** some format conversions are
  not supported yet (see [Differences from the JVM](differences.md)).

## Debugging the generated code

- The generated module (`mill app.goLink`, in `out/app/goLink.dest/go`) is ordinary Go:
  `go vet`, `go build -race`, `pprof` and Delve work on it.
- Go compiler errors in generated code point at Scala lines. `def goLineDirectives = false`
  shows the Go positions instead, which helps when reporting a scala-go bug.
- `def goBuildFlags = Seq("-race")` builds with Go's race detector.

## Builds that need the network

`goRequires` modules are downloaded by `go`, so the first build after adding one needs network
access. Later builds use Go's module cache.
