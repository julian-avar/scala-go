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
(with derived `ReadWriter`s), fastparse, pprint, mainargs, os-lib, and gears (see
[Concurrency](../user/concurrency.md#gears)).

## What does not work

- **Java code.** Classes written in Java have no TASTy. Pure-Java libraries, and Java classes
  inside Scala libraries, are missing at link time (a few, such as ujson's float formatting,
  have native implementations).
- **Runtime reflection and code generation:** `Class.forName`, `getMethods`, Java
  serialization, dynamic proxies. Libraries built on macros, givens and `Mirror`s (most Scala 3
  libraries) are fine.
- **Scala 2 libraries**, which have no TASTy.

A library that uses something missing fails to link, naming what it uses: see
[Troubleshooting](../user/troubleshooting.md#link-errors).
