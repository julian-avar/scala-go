---
title: Specification
---

# Specification

This specification defines what a Scala program means when scala-go compiles it to Go, against
the [Scala 3.4 language specification](https://scala-lang.org/files/archive/spec/3.4/) and the
Java SE behavior Scala programs observe on the JVM.

| Page                              | Covers                                                                                  |
|-----------------------------------|-----------------------------------------------------------------------------------------|
| [Language](language.md)           | Run-time semantics, chapter by chapter of the Scala spec, and the Scala 3 reference features |
| [Runtime](runtime.md)             | Program lifecycle, threads, memory model, GC, initialization, reflection, I/O, platform |
| [Data representation](data.md)    | The Go representation of each kind of Scala value, and the Go interop types            |
| [Go interoperability](interop.md) | Facades, value conversions, Go data, errors and panics, exports, goroutines             |

## Conformance

- **Front end.** `scalac` 3.9.0 with the scala-go plugin: parsing, typing, implicits, inlining,
  macros, pattern-match compilation and erasure are Scala's, so everything decided at compile
  time behaves as on the JVM.
- **Back end.** The plugin IR, the linker and the Go runtime implement the JVM backend's run-time
  semantics: evaluation order, numerics, equality, string conversion, exceptions,
  initialization, dispatch and type tests.
- **JDK.** javalib implements a subset of Java SE. A program that reaches a missing API fails to
  link, naming the member; with `--missing-apis throw` it throws the JVM's linkage error at the
  use instead.
- **Deviations.** Every difference from the JVM is marked **Deviation:**; one that is a defect
  rather than a design decision is marked **Deviation (bug):** and tracked in the
  [roadmap](../contributing/roadmap.md#m0-correct). Unmarked behavior is the JVM's.

## Conventions

- Statements are normative: "X is Y" means scala-go guarantees it. "As in Scala" means the Scala
  compiler fixes the behavior; nothing more is specified.
- "The JVM" is HotSpot with JDK 25, which the test suite compares against.
- Go constructs are those of [the Go specification][go-spec] and the [Go memory model][go-mem].
  Where a Scala construct rests on a Go rule, the rule is cited
  ("Go: [Conversions][go-conversions]"). The Go implementation is gc; where Go leaves behavior to
  the implementation, [Go implementation
  dependence](runtime.md#go-implementation-dependence) states what scala-go relies on.
- Go names (`int32`, `*rt.String`) refer to the generated code and the runtime package `rt`
  (`runtime/rt`). They are not an API: programs observe them only through Go interop,
  `SCALAGO_TRACE=1` stack traces and the generated sources.
- Statements are checked against the implementation (`plugin/`, `linker/`, `runtime/rt/`,
  `javalib/`). Those marked "verified", and deviations, are also checked by running the same
  program on the JVM and as Go; the end-to-end cases in `tests/cases/` check that both print the
  same output.

## Versions

| Item                        | Version                                                     |
|-----------------------------|-------------------------------------------------------------|
| Scala compiler and library  | 3.9.0 (the plugin is built for exactly this version)        |
| Language specification text | Scala 3.4 specification chapters                            |
| JVM compared against        | JDK 25                                                      |
| Go                          | 1.27, the `go` line of the generated `go.mod` (it also selects the language version of the current Go specification) |

The specification describes the scala-go version it ships with; a fixed deviation is deleted.

[go-spec]: https://go.dev/ref/spec
[go-mem]: https://go.dev/ref/mem
[go-conversions]: https://go.dev/ref/spec#Conversions
