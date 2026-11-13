---
title: Differences from the JVM
---

# Differences from the JVM

These run on scala-go but behave differently than on the JVM. What does not run is in
[Limitations](limitations.md); the [specification](../spec/index.md) marks each difference as a
**Deviation**, and the [roadmap](../contributing/roadmap.md#known-bugs) lists the known bugs.
Everything else observable keeps JVM semantics: `String` is `java.lang.String`, integer overflow
wraps, `==` and `hashCode` match the JVM, and uncaught exceptions print JVM-style stack traces.

## Runtime

- **Standard output** is buffered: flushed at exit, before writing to standard error, before
  reading standard input and around Go calls, at line ends on a terminal, and 100 ms after output
  was last buffered; otherwise not after each line.
- **Fatal Go errors** end the program without running shutdown hooks or flushing standard output
  written in the last 100 ms. They print Go's message (`fatal error: stack overflow`) and exit
  with status 2.
- **Stack overflow and out of memory** end the program; `StackOverflowError` and
  `OutOfMemoryError` are never thrown. A goroutine's stack grows to 1 GB first, so deep recursion
  works.
- **Stack traces** look like the JVM's, but a frame's line is where its statement starts, and
  JDK methods implemented in Go have no frames.
- **Data races** on reference fields can crash the program instead of showing a stale value;
  `final` fields have no publication guarantee ([Concurrency](concurrency.md#the-memory-model)).
- **Finalizers** never run. `WeakReference`, `SoftReference` and `WeakHashMap` hold their
  referents strongly.
- **Floating point:** on arm64, `a * b + c` may fuse into one multiply-add.
- **Identity:** `Runtime.maxMemory` is `Long.MaxValue` unless `GOMEMLIMIT` is set; `java.vm.name`
  and `java.vendor` are `scala-go`, `java.class.path` is empty, and program classes have no class
  loader (`null`).

## Data and classes

- **Arrays of references** do not know their element type: `Arrays.copyOf` and
  `toArray(new Array[String](0))` return arrays whose class is `Object[]`.
- **Boxed primitives** have no identity ([Data representation](../spec/data.md)).
- **`Class.forName`** finds only the classes the program uses (names with `classOf`, instantiates or
  extends); others throw `ClassNotFoundException`. It does not initialize the class.

## Text

- **Locales:** no locale data; every locale formats as `Locale.ROOT` (English).
- **Unicode:** character properties and case mappings follow the Go release's tables (Unicode 16
  with Go 1.27, as in JDK 25).
- **Time zones** come from the system's zoneinfo, or Go's copy embedded in the binary. Zone names
  are tz abbreviations (`CEST`), with no long names.

## I/O and networking

Per-area details are in [The JDK](../libraries/jdk.md).

- **HTTP** is HTTP/1.1 on Go's `net/http`, also when HTTP/2 is requested. `HttpURLConnection`
  gives response header names in Go's canonical case (`X-Lower-Case`) and drops `Connection`;
  `HttpClient` lowercases them, as the JDK does.
- **TLS** trusts the system's certificates, not the JDK's `cacerts`.
- **Interrupts** do not wake a thread blocked in socket or channel I/O.
- **`java.util.zip`** compresses to different, equally valid bytes than the JDK's zlib.

## Performance

Startup takes milliseconds; steady-state code is usually slower than a warmed-up JVM
([Performance](performance.md)).
