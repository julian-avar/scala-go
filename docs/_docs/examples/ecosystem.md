---
title: ecosystem
---

# ecosystem

Popular Scala 3 libraries, from their ordinary Maven artifacts, compiled to Go from the TASTy in
their jars ([source](https://github.com/julian-avar/scala-go/tree/main/examples/ecosystem)). None
has a scala-go artifact. The program does not call Go, so it also runs on the JVM, and its check
compares both outputs.

| Library    | Version | What the program uses                                                    |
|------------|---------|--------------------------------------------------------------------------|
| cats-core  | 2.13.0  | `foldMap`, `mapN` on options and `Validated`, `NonEmptyList`, `sequence` |
| upickle    | 4.4.1   | a derived `ReadWriter` for a case class, JSON written and read back      |
| fastparse  | 3.1.1   | an arithmetic expression parser, with a failure                          |
| pprint     | 0.9.4   | pretty-printing a case class                                             |
| mainargs   | 0.7.6   | parsing arguments for an `@main` method                                  |
| os-lib     | 0.11.4  | files and directories in a temporary directory; subprocesses (`call`, `spawn`) |

```scala sc:nocompile
case class Point(x: Int, y: Int, label: String) derives ReadWriter

println((Option(1), Option(2)).mapN(_ + _))                      // cats
println(read[Point](write(Point(1, 2, "a\"b"))))                 // upickle
println(parseExpr("2*(3+4)-5"))                                  // fastparse
println(os.proc("sh", "-c", "cat; echo $FOO").call(stdin = "piped in ", env = Map("FOO" -> "bar")).out.text())  // os-lib
```

`build.mill` lists the six artifacts in `mvnDeps`, nothing else. `ScalaGoModule` compiles each
jar's TASTy to IR once per version, and the linker keeps only the code the program reaches. os-lib's
subprocesses run on javalib's `ProcessBuilder`, which uses Go's `os/exec`.

Run it with `./mill examples.ecosystem.check`, which prints `JVM and Go outputs agree (27 lines)`.
The [README](https://github.com/julian-avar/scala-go/blob/main/examples/ecosystem/README.md) lists
the other commands and the limitations; [Scala libraries](../libraries/scala.md) has the coverage.
