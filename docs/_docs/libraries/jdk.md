---
title: The JDK
---

# The JDK

javalib is scala-go's Java standard library: written in Scala, compiled to Go with the program.
Where the JDK talks to the operating system, javalib calls Go's standard library.

| Package                         | Coverage                                                         |
|---------------------------------|------------------------------------------------------------------|
| `java.lang`                     | core classes, `Thread`, `StringBuilder`, `Math`, `System`, `ProcessBuilder` |
| `java.util`                     | collections, streams, `Optional`, `Random`, `UUID`, `regex` (RE2), `Properties`, `Base64` |
| `java.util.concurrent`          | executors, `ForkJoinPool`, `CompletableFuture`, locks, atomics, concurrent collections |
| `java.util.function`            | complete                                                         |
| `java.util.zip`                 | deflate, GZIP, zip files                                         |
| `java.math`                     | `BigInteger`, `BigDecimal` (exact, as on the JDK)                |
| `java.io`                       | streams, readers and writers, files, standard input              |
| `java.nio`                      | buffers, charsets, file channels, `Files`, `Path`, the default file system |
| `java.net`, `java.net.http`     | `URI`, `URL`, addresses, TCP sockets; `HttpURLConnection` and `HttpClient` on Go's `net/http` |
| `java.security`, `javax.crypto` | `MessageDigest`, `Mac`, `SecureRandom` on Go's crypto            |
| `java.time`                     | dates, times, durations, formatting and parsing; time zones from Go's tz database |
| `java.text`                     | only what `java.time` needs                                      |

`scala.concurrent.Future`, `scala.sys.process` and os-lib's `os.proc` work on top of these.

## Limitations

- **Missing classes:** `java.util.Scanner`, `java.util.Formatter`, `EnumSet`, `EnumMap`,
  `Calendar`, `java.util.logging`, `java.text`'s number and date formats, TLS configuration and
  ciphers, UDP and NIO socket channels, the atomic field updaters.
- **Charsets:** UTF-8, ISO-8859-1 and US-ASCII only. **Locales:** no locale data.
- **Regular expressions** are RE2's.
- **Classpath resources** are not in the binary.

A class or method javalib lacks is a link error naming it
([Troubleshooting](../user/troubleshooting.md#link-errors)). Behaviour that differs from the JDK
is in [Differences from the JVM](../user/differences.md); the gaps, by priority, in the
[Roadmap](../contributing/roadmap.md).
