---
title: Scala libraries
---

# Scala libraries

Scala 3 libraries need no scala-go artifact. Add them to `mvnDeps` as usual:

```scala sc:nocompile
object app extends ScalaGoModule {
  def mvnDeps = Seq(mvn"org.typelevel::cats-core:2.13.0", mvn"com.lihaoyi::upickle:4.4.1")
}
```

`ScalaGoModule` compiles each dependency to Go from the TASTy in its jar, once per version (Mill
caches it); the linker keeps only what the program uses. Jars that already contain scala-go IR,
such as `scalago-gears`, are used as they are.

Checked against the JVM, with identical output: cats, upickle (derived `ReadWriter`s), fastparse,
pprint, mainargs, os-lib ([ecosystem example](../examples/ecosystem.md)) and gears
([Concurrency](../user/concurrency.md#gears)).

## Limitations

- **Java code** has no TASTy. Pure-Java libraries, and Java classes inside Scala libraries, are
  missing at link time; a few (ujson's float formatting) have native implementations. This rules
  out `scala.collection.concurrent.TrieMap`, whose nodes are Java classes: use
  `java.util.concurrent.ConcurrentHashMap`.
- **Runtime reflection and code generation** (`Class.forName`, `getMethods`, Java serialization,
  dynamic proxies) do not exist. Macros, givens and `Mirror`s (most Scala 3 libraries) work.
- **Scala 2 libraries** have no TASTy.
- **Blocked:** cats-effect and ZIO (Java classes, atomic field updaters), fs2-io (NIO channels,
  TLS), circe and zio-json (`java.util.Currency`), scribe (`java.util.logging`), scopt
  (`Calendar`), requests-scala (TLS, cookies, proxies), munit and utest (no test runner as Go).
  The [Roadmap](../contributing/roadmap.md) has the plan for each.

A library that uses a missing API fails to link, naming it
([Troubleshooting](../user/troubleshooting.md#link-errors)). If the missing API is only in code
the program never runs, `def goMissingApis = "throw"` links anyway.
