# ecosystem

Popular Scala 3 libraries, used from their ordinary Maven artifacts: none has a scala-go
specific build. `ScalaGoModule` compiles each to Go from the TASTy in its jar, and the linker
keeps what the program uses. [`src/Main.scala`](src/Main.scala) uses:

| Library                  | Version | What the program does                                                |
|--------------------------|---------|----------------------------------------------------------------------|
| cats-core                | 2.13.0  | `foldMap`, `mapN` on options and `Validated`, `NonEmptyList`, `sequence` |
| upickle                  | 4.4.1   | a derived `ReadWriter` for a case class, writing and reading JSON    |
| fastparse                | 3.1.1   | an arithmetic expression parser, with a failure                       |
| pprint                   | 0.9.4   | pretty-printing a case class                                          |
| mainargs                 | 0.7.6   | parsing arguments for an `@main` method                               |
| os-lib                   | 0.11.4  | files and directories in a temporary directory, subprocesses (`call`, `spawn`) |

It does not call Go, so the same program also runs on the JVM, and the check compares the two.

## Running it

From the repository's root:

```sh
./mill examples.ecosystem.check    # runs it on the JVM and as Go, and compares the outputs
./mill examples.ecosystem.goRun    # as Go
./mill examples.ecosystem.run      # on the JVM
```

`check` prints `JVM and Go outputs agree (27 lines)`.

## Limitations

- **Only what the program calls is checked.** The linker includes only reachable code, so other
  parts of these libraries may still reach a JDK API that scala-go lacks (a link error naming it).
- **Libraries that do not link are not in the example:** those with Java classes (cats-effect,
  ZIO, jsoniter-scala), those needing missing JDK classes (circe and zio-json need
  `java.util.Currency`, scribe `java.util.logging`, scopt `Calendar`), Scala 2 libraries and
  libraries using runtime reflection. The design document's
  [Roadmap](../../docs/design.md#3-scala-libraries) lists each blocker.
- **The first build is slow:** each library is compiled from TASTy once per version (Mill caches
  the result).
- The os-lib part runs `sh` and `cat`, so it needs a Unix system (Windows is not supported by
  scala-go yet anyway).

See also the documentation's [Limitations](../../docs/_docs/user/limitations.md) page.
