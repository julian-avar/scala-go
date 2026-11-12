# basics

A small program: pattern matching, a `GoString`, Go called through generated facades
(`strconv` and the local module [`go/textstats`](go/textstats/textstats.go)), and gears futures on
goroutines. Source: [`src/Main.scala`](src/Main.scala). Explained in the documentation's
[basics](../../docs/_docs/examples/basics.md) page.

## Running it

From the repository's root:

```sh
./mill examples.basics.goRun Ada Grace     # build and run as Go
./mill examples.basics.goBuild             # the executable: out/examples/basics/goBuild.dest/app
```

There is no `check` command. Expected output:

```
Add(Num(1.0),Mul(Num(2.0),Num(3.0))) = 7.0
Hello, Ada!
Hello, Grace!
Grüße: 7 bytes as a Go string
5 words, the longest is "acknowledged"
Go error: no words
name lengths, computed concurrently: List(3, 5)
```

Dependencies: Go's `strconv`; `example.com/textstats` (Go 1.27); gears 0.3.1 with the
`scalago-gears` override (`gears.gearsDep` in this build, `mvn"io.github.scalago::scalago-gears:..."`
outside it).

## Limitations

- **Runs only as Go.** Facade members throw on the JVM: `./mill examples.basics.run` prints three
  lines, then fails at `textstats.Words` with `UnsupportedOperationException: Go facade member
  called on the JVM`. Workaround: put Go calls behind a trait of your own to run the rest on the JVM.
- **The local Go module is referred to by absolute path** (a `replace` in the generated `go.mod`).
  To build the generated module elsewhere, copy `go/textstats` along and adjust the path.
- **`Longest`'s `error` is thrown**, as a `GoException`. To get it as a value, declare the function
  in a hand-written facade with a `(String, GoError)` result.

All limitations: [docs/_docs/user/limitations.md](../../docs/_docs/user/limitations.md).
