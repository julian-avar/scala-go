# ecosystem

cats-core 2.13.0, upickle 4.4.1, fastparse 3.1.1, pprint 0.9.4, mainargs 0.7.6 and os-lib 0.11.4,
from their ordinary Maven artifacts, compiled to Go from TASTy. The program does not call Go, so it
also runs on the JVM. Source: [`src/Main.scala`](src/Main.scala). Explained in the documentation's
[ecosystem](../../docs/_docs/examples/ecosystem.md) page.

## Running it

From the repository's root:

```sh
./mill examples.ecosystem.check    # on the JVM and as Go; prints "JVM and Go outputs agree (27 lines)"
./mill examples.ecosystem.goRun    # as Go
./mill examples.ecosystem.run      # on the JVM
```

## Limitations

- **Only what the program calls is checked.** The linker includes only reachable code; other parts
  of these libraries may reach a JDK API scala-go lacks, which is a link error naming it.
- **Libraries that do not link are left out:** cats-effect, ZIO and jsoniter-scala (Java classes),
  circe and zio-json (`java.util.Currency`), scribe (`java.util.logging`), scopt (`Calendar`),
  Scala 2 libraries, and libraries using runtime reflection. The
  [roadmap](../../docs/roadmap.md) lists each blocker.
- **The first build is slow:** each library is compiled from TASTy once per version; Mill caches
  the result.
- **Unix only:** the os-lib part runs `sh` and `cat`.

All limitations: [docs/_docs/user/limitations.md](../../docs/_docs/user/limitations.md).
