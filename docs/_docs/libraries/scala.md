---
title: Scala libraries
---

# Scala libraries

Scala 3 libraries need no scala-go specific artifact. Add them to `mvnDeps` as usual:

```scala sc:nocompile
object app extends ScalaGoModule {
  def mvnDeps = Seq(
    mvn"org.typelevel::cats-core:2.13.0",
    mvn"com.lihaoyi::upickle:4.4.1"
  )
}
```

`ScalaGoModule` compiles each dependency to Go from the TASTy files in its jar (once per
version, cached by Mill), and the linker keeps only the parts the program uses. Jars that
already contain scala-go IR, such as `scalago-gears`, are used as they are.

These libraries are checked against the JVM, with the same output on both: cats, upickle
(with derived `ReadWriter`s), fastparse, pprint, mainargs, os-lib (the
[ecosystem example](../examples/ecosystem.md)), and gears (see
[Concurrency](../user/concurrency.md#gears)).

## Limitations

- **Java code.** Classes written in Java have no TASTy. Pure-Java libraries, and Java classes
  inside Scala libraries, are missing at link time (a few, such as ujson's float formatting,
  have native implementations). In the standard library, this rules out
  `scala.collection.concurrent.TrieMap`, whose nodes are Java classes: use
  `java.util.concurrent.ConcurrentHashMap`.
- **Runtime reflection and code generation:** `Class.forName`, `getMethods`, Java
  serialization, dynamic proxies. Libraries built on macros, givens and `Mirror`s (most Scala 3
  libraries) are fine.
- **Scala 2 libraries**, which have no TASTy.
- **Libraries blocked today:** cats-effect and ZIO (Java classes, atomic field updaters), fs2-io
  (NIO channels, TLS), circe and zio-json (`java.util.Currency`), scribe (`java.util.logging`),
  scopt (`Calendar`), requests-scala (TLS, cookies, proxies), munit and utest (no test runner as
  Go). The design document's [Roadmap](../contributing/design.md#3-scala-libraries) lists the
  blockers and the plan for each.

A library that uses something missing fails to link, naming what it uses: see
[Troubleshooting](../user/troubleshooting.md#link-errors). When the missing API is only in code
the program never runs, `def goMissingApis = "throw"` links anyway.
