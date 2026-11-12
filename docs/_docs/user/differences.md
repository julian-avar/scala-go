---
title: Differences from the JVM
---

# Differences from the JVM

These run on scala-go but behave differently than on the JVM. What does not run at all is in
[Limitations](limitations.md); the [specification](../spec/index.md) marks each difference as a
**Deviation**.

Everything else observable keeps JVM semantics: `String` is `java.lang.String`, integer overflow
wraps, `==` and `hashCode` match the JVM, and uncaught exceptions print JVM-style stack traces.

## Runtime

- **Standard output** is buffered. It is flushed at exit, before writing to standard error,
  before reading standard input and around Go calls; not after each line.
- **Signals** (SIGINT, SIGTERM, SIGHUP) and **fatal Go errors** end the program without running
  shutdown hooks or flushing standard output.
- **Stack overflow and out of memory** end the program. `StackOverflowError` and
  `OutOfMemoryError` are never thrown. A goroutine's stack grows to 1 GB first, so deep recursion
  works.
- **Stack traces** look like the JVM's, but line numbers are per statement, and natively
  implemented JDK methods have no frames.
- **Data races** on reference fields can crash the program instead of showing a stale value;
  `final` fields have no publication guarantee. See
  [Concurrency](concurrency.md#the-memory-model).
- **Finalizers** never run. `WeakReference`, `SoftReference` and `WeakHashMap` hold their
  referents strongly.
- **Floating point:** on arm64, `a * b + c` may be fused into one multiply-add. `Math.round` is
  off by one in rare cases (a [known bug](../contributing/roadmap.md)).
- **Memory and identity:** `Runtime.maxMemory` is `Long.MaxValue` unless `GOMEMLIMIT` is set;
  `java.vm.name` and `java.vendor` are `scala-go`, `java.class.path` is empty, and program
  classes have no class loader (`null`).
- **Fatal errors:** Go's fatal errors print Go's message (`fatal error: stack overflow`) and exit
  with status 2.

## Data and classes

- **Arrays of references** do not know their element type. `Arrays.copyOf` and
  `toArray(new Array[String](0))` return arrays whose class is `Object[]`.
- **Boxed primitives** have no identity. See [Data representation](../spec/data.md).
- **`Class.forName`** returns a `Class` for any name; it never throws `ClassNotFoundException`.

## Text

- **`String.format` and `f"..."`** map onto Go's `fmt`. `%x` and `%o` of negative numbers, `%g`,
  the `(` flag and `BigInteger` arguments give different output. Unsupported conversions throw: see
  [Limitations](limitations.md#jdk-and-scala-libraries).
- **Locales:** no locale data. Every locale formats as `Locale.ROOT` (English).
- **Unicode:** character properties and case mappings come from the Go release's tables (Unicode
  16 with Go 1.27, as in JDK 25).
- **Time zones** come from the system's zoneinfo, or from Go's copy embedded in the binary. Zone
  names are tz abbreviations (`CEST`); there are no long names (`Central European Summer Time`).

## I/O and networking

- **HTTP** is HTTP/1.1 on Go's `net/http`, also when HTTP/2 is requested, without the JDK's
  `Upgrade`/`HTTP2-Settings` headers. `HttpURLConnection` gives response header names in Go's
  canonical case (`X-Lower-Case`) and drops `Connection`; `HttpClient` lowercases them, as the
  JDK does.
- **Interrupts** do not wake a thread blocked in socket I/O (JVM: a virtual thread in
  `ServerSocket.accept` gets `SocketException: Closed by interrupt`).
- **TLS** trusts the system's certificates, not the JDK's `cacerts`.
- **`java.util.zip`** produces different, equally valid, compressed bytes than the JDK's zlib;
  short inputs may come out as a stored (uncompressed) block.

## Performance

Startup is milliseconds; steady-state code is usually slower than a warmed-up JVM. See
[Performance](performance.md).
