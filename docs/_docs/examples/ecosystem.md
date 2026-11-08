---
title: ecosystem
---

# ecosystem

Popular Scala 3 libraries from their ordinary Maven artifacts, compiled to Go from the TASTy in
their jars ([source](https://github.com/julian-avar/scala-go/tree/main/examples/ecosystem)):
cats-core 2.13.0, upickle 4.4.1, fastparse 3.1.1, pprint 0.9.4, mainargs 0.7.6 and os-lib 0.11.4.
The program does not call Go, so it also runs on the JVM, and its check compares both outputs.

```scala sc:nocompile
case class Point(x: Int, y: Int, label: String) derives ReadWriter

println((Option(1), Option(2)).mapN(_ + _))                      // cats
println(read[Point](write(Point(1, 2, "a\"b"))))                 // upickle
println(parseExpr("2*(3+4)-5"))                                  // fastparse
println(os.proc("sh", "-c", "cat; echo $FOO").call(stdin = "piped in ", env = Map("FOO" -> "bar")).out.text())  // os-lib
```

## Running it

```sh
./mill examples.ecosystem.check    # on the JVM and as Go: "JVM and Go outputs agree (27 lines)"
```

## Limitations

- Only the parts of the libraries that the program calls are checked; other parts may need a JDK
  API that scala-go lacks (a link error that names it).
- Libraries that do not link today are left out: cats-effect, ZIO, fs2-io, circe and zio-json,
  jsoniter-scala, scribe, scopt, requests-scala, munit and utest. The design document's
  [Roadmap](../contributing/design.md#3-scala-libraries) lists their blockers.
- The first build compiles each library from TASTy, once per version.
- The os-lib part runs `sh` and `cat`: Unix only.

See [Scala libraries](../libraries/scala.md).
