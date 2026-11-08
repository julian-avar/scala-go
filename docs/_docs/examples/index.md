---
title: Examples
---

# Examples

The repository's [`examples/`](https://github.com/julian-avar/scala-go/tree/main/examples)
directory has complete programs, each a Mill module of the repository's build
(`examples.<name>`) built with `ScalaGoModule`. Run them from a checkout of the repository with
`./mill`; those with a `check` command test themselves (`./mill examples.__.check` runs them all).

| Example                  | Shows                                                                | Check                         |
|--------------------------|----------------------------------------------------------------------|-------------------------------|
| [basics](basics.md)      | a program, a local Go module and a standard library package called through generated facades, gears | —                    |
| [ecosystem](ecosystem.md) | cats, upickle, fastparse, pprint, mainargs and os-lib compiled from TASTy | same output on the JVM and as Go |
| [gotui](gotui.md)        | a bubbletea terminal app with bubbles components and lipgloss styles | scripted keys, final state    |
| [gin](gin.md)            | a JSON API on gin: routes, middleware, JSON binding                  | requests sent to itself       |
| [argus](argus.md)        | configuration files reloaded on change, with argus                   | edits files, waits for reloads |

Each page, and each example's `README.md`, ends with the example's limitations: what did not
work, and the workaround the example uses. All limitations are collected in
[Limitations](../user/limitations.md).

The examples that use third-party Go modules (gotui, gin, argus) need network access for their
first build, to download the modules.
