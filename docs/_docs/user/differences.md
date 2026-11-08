---
title: Differences from the JVM
---

# Differences from the JVM

scala-go keeps the JVM's semantics where programs can observe them: `String` is
`java.lang.String`, integer overflow wraps, `==` and `hashCode` behave as on the JVM, and
uncaught exceptions print JVM-style stack traces. This page lists the differences users meet
most; the design document's [Status](../contributing/design.md#status) and
[Roadmap](../contributing/design.md#roadmap) have the complete list.

## Not available

- **Runtime reflection:** `getMethods`, `Method.invoke`, annotations at run time, dynamic
  proxies, Java serialization, class loading. `getClass`, class names and `isAssignableFrom`
  work. Libraries that use macros, givens and `Mirror`s instead (most Scala 3 libraries) are
  fine.
- **Type tests with a `ClassTag`** (`case x: T` given a `ClassTag[T]`) throw
  `NoSuchMethodError`: `Class.isInstance` and `Class.cast` are missing. `Class.forName` returns
  a `Class` for any name instead of throwing `ClassNotFoundException`.
- **Classpath resources:** `getResourceAsStream` returns `null`; resources are not embedded in
  the binary.
- **Java code** in dependencies, and Scala 2 libraries: see [Scala libraries](../libraries/scala.md).
- **Parts of the JDK** that scala-go does not implement: see [The JDK](../libraries/jdk.md).
  Using one is a link error, see [Troubleshooting](troubleshooting.md).
- **Tests as Go:** test frameworks do not run as Go yet. Run the tests on the JVM, and run the
  program as Go in CI.

## Behaving differently

- **`String.format` and `f"..."`** are partly mapped onto Go's `fmt`. Some conversions give
  different results (`%x` and `%o` of negative numbers, `%g`, `BigInt` arguments), and some
  throw (argument indices such as `%1$s`, the `,` flag as in `%,d`, `%t` for dates).
  `java.util.Formatter` and `String.formatted` are missing.
- **Regular expressions** use Go's RE2 engine: no lookaround, backreferences or possessive
  quantifiers.
- **Locales:** there is no locale data; every locale formats as `Locale.ROOT` (English).
- **Data races** on reference fields can crash the program instead of showing a stale value;
  see [Concurrency](concurrency.md#the-memory-model).
- **Standard output** is buffered and flushed at exit, before reading standard input and around
  Go calls, not after each line.
- **Signals and fatal errors:** SIGINT, SIGTERM and SIGHUP end the program without running
  shutdown hooks and without flushing standard output. So does a fatal Go error.
- **Stack overflow and out of memory** end the program: `StackOverflowError` and
  `OutOfMemoryError` are never thrown. A goroutine's stack grows up to 1 GB first, so deep
  recursion works.
- **Stack traces** look like the JVM's, but line numbers are per statement, and JDK methods
  implemented natively have no frames.
- **Finalizers** never run. **Weak references** (`WeakReference`, `SoftReference`,
  `WeakHashMap`) hold their referents strongly.
- **Arrays of references** do not know their element type: `Arrays.copyOf` or
  `toArray(new Array[String](0))` return arrays whose class is `Object[]`.
- **HTTP** is HTTP/1.1 on Go's `net/http`, also when HTTP/2 is asked for. TLS trusts the
  system's certificates, not the JDK's `cacerts`. Proxies, cookies and authenticators are not
  supported, and response header names arrive in Go's canonical case (`Content-Type`).
- **Compression:** `java.util.zip` produces different (equally valid) compressed bytes than
  the JDK's zlib.
- **Charsets:** UTF-8, ISO-8859-1 and US-ASCII only.
- **Unicode:** character properties and case mappings come from Go's tables (Unicode 15, as in
  JDK 21); characters added in Unicode 16 have no case mapping.
- **Time zones** come from the system's zoneinfo, or from Go's copy embedded in the binary.
  Zone names are the tz database's abbreviations ("CEST"); there are no long names ("Central
  European Summer Time").
- **Platforms:** little-endian ones only (amd64, arm64, ...). Windows paths are not supported
  yet.
- **Performance** differs: see [Performance](performance.md).
