---
title: Performance
---

# Performance

A scala-go binary starts in a few milliseconds, with no JVM and no warm-up. Against a fully
warmed-up JVM, generated code is usually slower; the benchmarks of the repository (`mill
bench.run 3` and `mill bench.goRun 3`, third round, amd64) give:

| Code                                                                  | Go time / JVM time |
|-----------------------------------------------------------------------|--------------------|
| virtual calls, exceptions                                             | 0.5–0.8            |
| numeric loops, strings, closures, mutable collections, `synchronized` | 1.6–3              |
| boxed streams, immutable collections, `java.time` arithmetic          | 4–7                |
| allocation-heavy recursion, primitive and collector streams           | 10–13              |
| `DateTimeFormatter` parsing                                           | about 25           |

The JVM's JIT inlines across calls and removes short-lived allocations. Go's compiler cannot
inline calls through Go interfaces, and Go's garbage collector is not generational. A JVM
before warm-up (the first seconds of any program, and all of a short-lived one) is much closer
to Go, or slower.

## Tuning

- `GOGC` and `GOMEMLIMIT` trade memory for less garbage collection, as for any Go program.
- In hot code, prefer arrays of primitives and `while` loops: generic collections box their
  elements, and each box is an allocation.
- `synchronized` costs about 55 ns uncontended: safe, but not free in hot loops.
- `GoString` and `GoSlice` avoid copying strings and arrays when calling Go in a loop (see
  [Go data](../interop/go-data.md)).
- The generated code is ordinary Go, so Go's profiler works: see
  [`runtime/pprof`](https://pkg.go.dev/runtime/pprof).
