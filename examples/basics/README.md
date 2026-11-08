# basics

A small program built with `ScalaGoModule` ([`src/Main.scala`](src/Main.scala)):

- case classes and pattern matching, collections and string interpolation;
- a `GoString` (UTF-8, as Go stores strings);
- Go called through facades generated from Go packages (`goFacadePackages` in `build.mill`): the
  standard library's `strconv`, and a local Go module, [`go/textstats`](go/textstats/textstats.go)
  (`goLocalModules`). A Go function's `error` result is thrown as a `GoException`;
- [gears](https://github.com/lampepfl/gears) futures on goroutines, gears being an ordinary Scala
  dependency compiled from its TASTy.

## Running it

From the repository's root:

```sh
./mill examples.basics.goRun Ada Grace     # build and run as Go
./mill examples.basics.goBuild             # the executable: out/examples/basics/goBuild.dest/app
```

It prints:

```
Add(Num(1.0),Mul(Num(2.0),Num(3.0))) = 7.0
Hello, Ada!
Hello, Grace!
Grüße: 7 bytes as a Go string
5 words, the longest is "acknowledged"
Go error: no words
name lengths, computed concurrently: List(3, 5)
```

The example has no `check` command; the output above is what to compare against.

## Dependencies

- Go: the standard library (`strconv`) and the local module `example.com/textstats` (Go 1.22).
- Scala: gears 0.3.1 with the scalago-gears override (`gears.gearsDep` in this build; outside the
  repository, `mvn"io.github.scalago::scalago-gears:..."`).

## Limitations

- **Runs only as Go.** It calls Go, and facade members throw on the JVM: `./mill
  examples.basics.run` prints the first three lines, then fails with
  `UnsupportedOperationException: Go facade member called on the JVM` at `textstats.Words`. Keep
  Go calls behind a trait of your own to run the rest on the JVM.
- **Local Go modules are referred to by absolute path** (a `replace` directive in the generated
  `go.mod`): to build the generated module elsewhere, copy `go/textstats` too and adjust the path.
- `Longest` returns `(string, error)`; the generated facade returns the `String` and throws the
  error. To get the error as a value, declare the function in a hand-written facade with a
  `(String, GoError)` result.

See also the documentation's [Limitations](../../docs/_docs/user/limitations.md) page.
