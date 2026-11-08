---
title: Differences from the JVM
---

# Differences from the JVM

scala-go keeps the JVM's semantics where programs can observe them: `String` is
`java.lang.String`, integer overflow wraps, `==` and `hashCode` behave as on the JVM, and
uncaught exceptions print JVM-style stack traces. The differences:

## Not available

- **Runtime reflection:** `Class.forName`, `getMethods`, Java serialization, dynamic proxies.
  `getClass`, `getName` and `isAssignableFrom` work. Libraries that use macros, givens and
  `Mirror`s instead (most Scala 3 libraries) are fine.
- **Java code** in dependencies, and Scala 2 libraries: see [Scala libraries](../libraries/scala.md).
- **Parts of the JDK** that scala-go does not implement: see [The JDK](../libraries/jdk.md).
  Using one is a link error, see [Troubleshooting](troubleshooting.md).

## Behaving differently

- **Regular expressions** use Go's RE2 engine: no lookaround and no backreferences.
- **Locales:** there is no locale data; every locale formats as `Locale.ROOT` (English).
- **Data races** on reference fields can crash the program instead of showing a stale value;
  see [Concurrency](concurrency.md#the-memory-model).
- **Standard output** is buffered and flushed at exit, before reading standard input and around
  Go calls, not after each line.
- **Stack traces** look like the JVM's, but line numbers are per statement, and JDK methods
  implemented natively have no frames.
- **`WeakHashMap`** keeps its keys (Go 1.22 has no weak pointers).
- **HTTP** is HTTP/1.1 on Go's `net/http`, also when HTTP/2 is asked for. TLS trusts the
  system's certificates, not the JDK's `cacerts`. Proxies, cookies and authenticators are not
  supported, and response header names arrive in Go's canonical case (`Content-Type`).
- **Compression:** `java.util.zip` produces different (equally valid) compressed bytes than
  the JDK's zlib.
- **Unicode:** character properties and case mappings come from Go's tables (Unicode 15, as in
  JDK 21); characters added in Unicode 16 have no case mapping.
- **Time zones** come from the system's zoneinfo, or from Go's copy embedded in the binary.
  Zone names are the tz database's abbreviations ("CEST"); there are no long names ("Central
  European Summer Time").
- **Performance** differs: see [Performance](performance.md).
