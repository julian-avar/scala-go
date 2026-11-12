---
title: Performance
---

# Performance

A scala-go binary starts in a few milliseconds: no JVM, no warm-up. Against a fully warmed-up
JVM, generated code is usually slower. The repository's benchmarks (`mill bench.run 3` and
`mill bench.goRun 3`, third round, amd64):

| Code                                                                  | Go time / JVM time |
|-----------------------------------------------------------------------|--------------------|
| virtual calls, exceptions                                             | 0.5–0.8            |
| numeric loops, strings, closures, mutable collections, `synchronized` | 1.6–3              |
| boxed streams, immutable collections, `java.time` arithmetic          | 4–7                |
| allocation-heavy recursion, primitive and collector streams           | 10–13              |
| `DateTimeFormatter` parsing                                           | about 25           |

Why: the JVM's JIT inlines across calls and removes short-lived allocations. Go's compiler
cannot inline calls through Go interfaces, and Go's garbage collector is not generational. A JVM
before warm-up (the first seconds of any program, all of a short-lived one) is close to Go, or
slower.

## Tuning

- `GOGC` and `GOMEMLIMIT` trade memory for less garbage collection, as for any Go program.
- In hot code, use arrays of primitives and `while` loops. Generic collections box their
  elements, and each box is an allocation.
- Uncontended `synchronized` costs about 55 ns.
- `GoString` and `GoSlice` avoid copying strings and arrays when calling Go in a loop
  ([Go data](../interop/go-data.md)).
- Profile with Go's [`runtime/pprof`](https://pkg.go.dev/runtime/pprof): the generated code is
  ordinary Go.
