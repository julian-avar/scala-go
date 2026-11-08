---
title: The JDK
---

# The JDK

scala-go has its own implementation of the Java standard library, javalib, written in Scala and
compiled to Go like the program. Where the JDK talks to the operating system, it uses Go's
standard library.

| Package                       | Coverage                                                         |
|-------------------------------|------------------------------------------------------------------|
| `java.lang`                   | the core classes, `Thread`, `StringBuilder`, `Math`, `System`, `ProcessBuilder` |
| `java.util`                   | collections, streams, `Optional`, `Random`, `UUID`, `regex` (RE2), `Properties`, `Base64` |
| `java.util.concurrent`        | executors, `ForkJoinPool`, `CompletableFuture`, locks, atomics, concurrent collections |
| `java.util.function`          | complete                                                         |
| `java.util.zip`               | deflate, GZIP, zip files                                         |
| `java.math`                   | `BigInteger`, `BigDecimal` (exact, as on the JDK)                |
| `java.io`                     | streams, readers and writers, files, standard input              |
| `java.nio`                    | buffers, charsets, file channels, `Files`, `Path`, the default file system |
| `java.net`, `java.net.http`   | `URI`, `URL`, addresses, TCP sockets, `HttpURLConnection` and `HttpClient` on Go's `net/http` |
| `java.security`, `javax.crypto` | `MessageDigest`, `Mac`, `SecureRandom` on Go's crypto            |
| `java.time`                   | dates, times, durations, formatting and parsing, time zones from Go's tz database |
| `java.text`                   | the parts `java.time` needs                                      |

On top of these, `scala.concurrent.Future`, `scala.sys.process` and os-lib's `os.proc` work.

Using a class or method that javalib does not have is a link error, naming it; see
[Troubleshooting](../user/troubleshooting.md#link-errors). The behaviours that differ from the
JDK are listed in [Differences from the JVM](../user/differences.md), and the known gaps in the
design document's [Status](../contributing/design.md#status).
