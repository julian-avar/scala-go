---
title: The JDK
---

# The JDK

javalib is scala-go's Java standard library: written in Scala, compiled to Go with the program.
Where the JDK talks to the operating system, javalib calls Go's standard library.

| Package                         | Coverage                                                         |
|---------------------------------|------------------------------------------------------------------|
| `java.lang`                     | core classes, `Thread`, `StringBuilder`, `Math`, `System`, `ProcessBuilder` |
| `java.util`                     | collections, streams, `Optional`, `Random`, `UUID`, `regex` (RE2), `Formatter`, `Properties`, `Base64` |
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

A class or method javalib lacks is a link error naming it
([Troubleshooting](../user/troubleshooting.md#link-errors)).
[Limitations](../user/limitations.md#jdk-and-scala-libraries) lists what is missing (classes,
methods, charsets other than UTF-8, ISO-8859-1 and US-ASCII, regex features beyond RE2, classpath
resources), [Differences from the JVM](../user/differences.md) the general differences, and the
[Roadmap](../contributing/roadmap.md) the plan.

## Differences by area

Area-specific differences from the JDK, beyond those two pages:

### I/O

- `System.out`'s and `System.err`'s print methods do not take the stream's monitor: holding its
  lock does not keep other threads from printing.
- `FileTime`s have millisecond precision; file attributes given at creation are ignored.
- Directories list in name order (`Files.list`, `walk`, `newDirectoryStream`), not the file
  system's. `Files.walk` with `FOLLOW_LINKS` does not detect cycles.
- `Thread.interrupt` does not interrupt blocking channel operations (no
  `ClosedByInterruptException`).
- `FileLock`'s class is `java.nio.channels.FileLockImpl`.
- `allocateDirect` makes a heap buffer (`isDirect` false). `FileChannel.map` reads the region into
  a heap buffer: `READ_ONLY` and `PRIVATE` mappings do not see later changes to the file;
  `READ_WRITE` is not supported.
- Not implemented: selectors, asynchronous channels, file systems other than the default
  (`FileSystemProvider`, zip file systems), `FileStore`, watch services.

### Networking

- HTTP response header names come in name order; names that differ only in case (`X-b`, `x-B`)
  are one field.
- Streaming modes buffer the request body: `setChunkedStreamingMode` sends a `Content-Length`.
- No `HttpsURLConnection`, `SSLContext`, socket factories or hostname verifiers.
- `URL` opens http, https and file URLs; jar, ftp and mailto URLs parse but do not open.
- `URL.equals` and `hashCode` compare host names without resolving them.
- `InetAddress` caches no lookups and resolves no interface names in scope ids (numeric ids
  work); `isReachable` only tries a TCP connection to port 7.
- A socket stream's `available()` is 0. Closing a socket does not interrupt its `connect`;
  `Socket.bind` takes effect at `connect`.
- Not implemented: HTTP/2 push promises, WebSockets, `NetworkInterface`, selectors.

### Digests and compression

- `SecureRandom` reads Go's `crypto/rand` whatever its algorithm, so seeds add nothing: a seeded
  `SHA1PRNG` is not reproducible.
- Corrupt compressed data gives Go's messages (`flate: corrupt input before offset N`).
- An `Inflater` may deliver output at block ends rather than mid-block.
- `FULL_FLUSH` does not reset the dictionary; `FILTERED` is the default strategy.

### java.time

- No Japanese calendar (the other non-ISO calendars are there).
- `ZoneRules.getTransitions` stops at 2037; `getTransitionRules` is empty.
- Daylight saving time is what the zoneinfo files say: in zones with negative DST (Europe/Dublin's
  winter time) the standard offset can differ from the JDK's.
- `ZoneId.getAvailableZoneIds` lists the system's zoneinfo, empty where there is none (every zone
  still loads).
- Parsing zone names (`z`) chooses arbitrarily among zones sharing an abbreviation.
- The deprecated calendar methods of `java.util.Date` are missing.

### Subprocesses

- `destroy` and `destroyForcibly` both kill the process on Windows.
- Not implemented: `Process.onExit`, `ProcessHandle.of` for other processes, `allProcesses`,
  `children`, `descendants`, `startPipeline`; `ProcessHandle.info` has empty values.
