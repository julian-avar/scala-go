---
title: Examples
---

# Examples

Complete programs in the repository's
[`examples/`](https://github.com/julian-avar/scala-go/tree/main/examples) directory, each a module
`examples.<name>` of its Mill build. Run them from a checkout with `./mill`;
`./mill examples.__.check` runs every check. gotui, gin and argus use third-party Go modules, so
their first build needs network access; they call Go, so they run only as Go. Each example's
`README.md` has the commands and the expected output.

| Example                   | Shows                                                                 | `check`                          |
|---------------------------|-----------------------------------------------------------------------|----------------------------------|
| [basics](basics.md)       | a local Go module and `strconv` through generated facades; gears      | none                             |
| [ecosystem](ecosystem.md) | cats, upickle, fastparse, pprint, mainargs, os-lib compiled from TASTy | same output on the JVM and as Go |
| [gotui](gotui.md)         | a bubbletea terminal app with bubbles components and lipgloss styles  | scripted keys, final state       |
| [gin](gin.md)             | a JSON API on gin: routes, middleware, JSON binding                   | 14 requests sent to itself       |
| [argus](argus.md)         | configuration files reloaded on change                                | edits files, waits for reloads   |
