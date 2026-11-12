---
title: Examples
---

# Examples

Complete programs in the repository's
[`examples/`](https://github.com/julian-avar/scala-go/tree/main/examples) directory. Each is a
module of the repository's Mill build, `examples.<name>`, built with `ScalaGoModule`. Run them
from a checkout with `./mill`; `./mill examples.__.check` runs every `check`.

| Example                   | Shows                                                                 | `check`                          |
|---------------------------|-----------------------------------------------------------------------|----------------------------------|
| [basics](basics.md)       | a local Go module and `strconv` through generated facades; gears      | none                             |
| [ecosystem](ecosystem.md) | cats, upickle, fastparse, pprint, mainargs, os-lib compiled from TASTy | same output on the JVM and as Go |
| [gotui](gotui.md)         | a bubbletea terminal app with bubbles components and lipgloss styles  | scripted keys, final state       |
| [gin](gin.md)             | a JSON API on gin: routes, middleware, JSON binding                   | 14 requests sent to itself       |
| [argus](argus.md)         | configuration files reloaded on change                                | edits files, waits for reloads   |

Each page explains how the example works. Each example's `README.md` has the commands, the
expected output, and the limitations it hits with the workaround it uses. All limitations are in
[Limitations](../user/limitations.md).

gotui, gin and argus use third-party Go modules: their first build needs network access.
