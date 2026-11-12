---
title: Go interop
---

# Go interop

Scala and Go code call each other directly, in one process, without serialization.

| To ...                                           | See                                       |
|--------------------------------------------------|-------------------------------------------|
| call a Go package by declaring its API           | [Calling Go](facades.md)                  |
| generate the declarations from Go packages       | [Generated facades](generated-facades.md) |
| share slices, maps, channels and strings with Go | [Go data](go-data.md)                     |
| use a third-party Go module                      | [Go libraries](go-libraries.md)           |
| call Scala code from a Go program                | [Calling Scala from Go](exports.md)       |

Programs that call Go run only as Go ([On the JVM](facades.md#on-the-jvm)); the Go data types run
on both. [Go interoperability](../spec/interop.md) specifies the exact semantics.
