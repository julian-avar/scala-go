---
title: Performance
---

# Performance

A scala-go binary starts in a few milliseconds, with no warm-up. Against a warmed-up JVM,
generated code is usually slower. The repository's benchmarks (`mill bench.run 3` against
`mill bench.goRun 3`, third round, amd64):

| Code                                                                  | Go time / JVM time |
|-----------------------------------------------------------------------|--------------------|
| virtual calls, exceptions                                             | 0.5–0.8            |
| numeric loops, strings, closures, mutable collections, `synchronized` | 1.6–3              |
| boxed streams, immutable collections, `java.time` arithmetic          | 4–7                |
| allocation-heavy recursion, primitive and collector streams           | 10–13              |
| `DateTimeFormatter` parsing                                           | about 25           |

The JVM's JIT inlines across calls and removes short-lived allocations; Go cannot inline calls
through interfaces, and its garbage collector is not generational. A JVM before warm-up (the
first seconds of any program) is close to Go, or slower.

## Tuning

- `GOGC` and `GOMEMLIMIT` trade memory for less garbage collection, as for any Go program.
- In hot code, use arrays of primitives and `while` loops: generic collections box their
  elements, an allocation each.
- Uncontended `synchronized` costs about 55 ns.
- `GoString` and `GoSlice` avoid copying strings and arrays when calling Go in a loop
  ([Go data](../interop/go-data.md)).
- Profile with Go's [`runtime/pprof`](https://pkg.go.dev/runtime/pprof).
