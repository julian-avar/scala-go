---
title: Specification
---

# Specification

This specification defines what a Scala program means when scala-go compiles it to Go. It is
written against the [Scala 3.4 language specification](https://scala-lang.org/files/archive/spec/3.4/)
and the Java SE behavior that Scala programs observe on the JVM.

| Page                              | Covers                                                                                  |
|-----------------------------------|-----------------------------------------------------------------------------------------|
| [Language](language.md)           | Run-time semantics, chapter by chapter of the Scala spec, and the Scala 3 reference features |
| [Runtime](runtime.md)             | Program lifecycle, threads, memory model, GC, initialization, reflection, I/O, platform |
| [Data representation](data.md)    | The Go representation of each kind of Scala value, and the Go interop types            |
| [Go interoperability](interop.md) | Facades, value conversions, Go data, errors and panics, exports, goroutines             |

## Conformance

- **Front end.** The front end is `scalac` 3.9.0 with the scala-go plugin. Parsing, typing,
  implicit resolution, inlining, macro expansion, pattern-match compilation and erasure are
  Scala's. Everything decided at compile time behaves as on the JVM by construction.
- **Back end.** The plugin IR, the linker and the Go runtime implement the JVM backend's
  run-time semantics: evaluation order, numerics, equality, string conversion, exceptions,
  initialization, dispatch and type tests.
- **JDK.** javalib implements a subset of Java SE in Scala and Go. A program that reaches a
  missing API fails to link and names the missing member. With `--missing-apis throw`, it
  throws the JVM's linkage error at the use instead.
- **Deviations.** Every difference from the JVM is marked **Deviation:**. A difference that is a
  defect rather than a design decision is marked **Deviation (bug):**. Unmarked behavior is the
  JVM's.

## Conventions

- Statements are normative: "X is Y" means scala-go guarantees it.
- "As in Scala" means the Scala compiler fixes the behavior; nothing more is specified.
- "The JVM" is HotSpot with JDK 25, which the test suite compares against.
- Go constructs are those of [The Go Programming Language Specification][go-spec] and the
  [Go memory model][go-mem]. Where a Scala construct rests on a Go rule, the rule is cited
  ("Go: [Conversions][go-conversions]"). Where Go leaves a behavior to the implementation, the
  page states what scala-go relies on; [Runtime: Go implementation
  dependence](runtime.md#go-implementation-dependence) collects these. The Go implementation
  is gc, the Go project's toolchain.
- Go type names (`int32`, `*rt.String`, `any`) refer to the generated code and the runtime
  package `rt` (`runtime/rt`). They are not an API. Programs observe them only through Go
  interop, `SCALAGO_TRACE=1` stack traces and the generated sources.
- Each statement is checked against the implementation (`plugin/`, `linker/`, `runtime/rt/`,
  `javalib/`). Statements marked "verified", and deviations, are also checked by running the
  same program on the JVM and as Go. The end-to-end cases in `tests/cases/` check that both
  print the same output.

## Versions

| Item                        | Version                                                     |
|-----------------------------|-------------------------------------------------------------|
| Scala compiler and library  | 3.9.0 (the plugin is built for exactly this version)        |
| Language specification text | Scala 3.4 specification chapters                            |
| JVM compared against        | JDK 25                                                      |
| Go                          | 1.27, which the generated `go.mod` declares |
| Go specification text       | the current one; the `go` line of the generated `go.mod` selects the language version (1.27) |

The specification describes the scala-go version it ships with. A deviation that is fixed is
deleted from these pages.

[go-spec]: https://go.dev/ref/spec
[go-mem]: https://go.dev/ref/mem
[go-conversions]: https://go.dev/ref/spec#Conversions
