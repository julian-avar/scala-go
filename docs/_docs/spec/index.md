---
title: Specification
---

# Specification

This specification defines what a Scala program means when scala-go compiles it to Go. It is
written against the [Scala 3.4 language specification](https://scala-lang.org/files/archive/spec/3.4/)
and the Java SE behavior that Scala programs observe on the JVM.

## Pages

| Page                          | Covers                                                                                  |
|-------------------------------|-----------------------------------------------------------------------------------------|
| [Language](language.md)       | Run-time semantics of the language, chapter by chapter, and the Scala 3 reference features |
| [Runtime](runtime.md)         | The execution model: program lifecycle, threads, memory model, GC, initialization, reflection, I/O, platform |
| [Data representation](data.md) | How each kind of Scala value is represented in Go, and the Go interop types           |
| [Go interoperability](interop.md) | Facades, conversions between Scala and Go values, Go data, errors and panics, exports, goroutines |

## Conformance

- scala-go implements Scala 3 as specified. The front end is the Scala compiler itself
  (`scalac` 3.9.0 with the scala-go plugin), so parsing, typing, implicit resolution, inlining,
  macro expansion, pattern-match compilation and erasure are exactly Scala's. Everything that is
  decided at compile time behaves as on the JVM by construction.
- The back end (plugin IR, linker, Go runtime) implements the run-time semantics of the JVM
  backend: evaluation order, numeric semantics, equality, string conversion, exceptions,
  initialization, dispatch and type tests.
- The JDK is implemented by javalib, a subset of the Java SE API written in Scala and Go. A
  program that reaches an API javalib lacks fails to link and names the missing member (or, with
  `--missing-apis throw`, throws the JVM's linkage error where it is used).
- Where scala-go differs from the JVM, the difference is listed in these pages, marked
  **Deviation:**. A deviation that is a defect rather than a design decision is marked
  **Deviation (bug):**. Behavior not marked as a deviation is the JVM's.

## Reading this specification

- Statements are normative: "X is Y" means scala-go guarantees it.
- "As in Scala" means the behavior is fixed by the Scala compiler and needs no further
  description.
- "The JVM" means the HotSpot JVM with JDK 25, which the test suite compares against.
- Go constructs are those of [The Go Programming Language Specification][go-spec] and the
  [Go memory model][go-mem]. Where the meaning of a Scala construct rests on a Go rule, the
  pages cite it ("Go: [Conversions][go-conversions]"); where Go leaves a behavior to the
  implementation, they say what scala-go relies on (summarized in
  [Runtime: Go implementation dependence](runtime.md#go-implementation-dependence)). The Go
  implementation is gc, the Go project's toolchain.
- Go type names (`int32`, `*rt.String`, `any`) refer to the generated code and the runtime
  package `rt` (`runtime/rt` in the repository). They are not an API: programs cannot observe them
  except through Go interop, `SCALAGO_TRACE=1` stack traces and the generated sources.
- Each statement was checked against the implementation (`plugin/`, `linker/`, `runtime/rt/`,
  `javalib/`) and, where marked as verified or listed as a deviation, by running the same program
  on the JVM and as Go. The end-to-end test cases (`tests/cases/`) check that both print the same
  output.

## Versions

| Item                         | Version                                                     |
|------------------------------|-------------------------------------------------------------|
| Scala compiler and library   | 3.9.0 (the plugin is built for exactly this version)        |
| Language specification text  | Scala 3.4 specification chapters                            |
| JVM compared against         | JDK 25                                                      |
| Go                           | `go 1.22` or later in the generated `go.mod`; tested with Go 1.26 |
| Go specification text        | the current one; the generated code needs only Go 1.22 features (the `go` line of `go.mod` selects the language version) |

The specification describes the scala-go version it ships with. It changes with the
implementation: a deviation that is removed is deleted from these pages.

[go-spec]: https://go.dev/ref/spec
[go-mem]: https://go.dev/ref/mem
[go-conversions]: https://go.dev/ref/spec#Conversions
