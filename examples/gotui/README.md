# gotui

A todo list in the terminal, written in Scala on Go's most popular TUI libraries
([`src/Main.scala`](src/Main.scala)):

- [bubbletea](https://github.com/charmbracelet/bubbletea)'s Elm architecture: `App` is a Scala case
  class implementing the Go interface `tea.Model` (`Init`, `Update`, `View`);
- [bubbles](https://github.com/charmbracelet/bubbles) components (text input, spinner, key
  bindings) and [lipgloss](https://github.com/charmbracelet/lipgloss) styles;
- Go values with value receivers (`input.Update(msg)` returns a copy) and Go funcs as Scala
  functions (`tea.Tick` delivers a Scala message);
- a Go `io.Reader` implemented in Scala (`ScriptedKeys`), using the facade of
  `scalago-gostd`, which the generated facades reuse for `io` and `time`.

## Running it

From the repository's root, build it and run the executable in a terminal:

```sh
./mill examples.gotui.goBuild
out/examples/gotui/goBuild.dest/app        # type a todo, Enter; Tab: the list (j/k, space, d, q); Ctrl+C quits
./mill examples.gotui.check                # headless: scripted keys, checks the final state
```

`check` runs the program with `--demo`: it reads scripted keys from `ScriptedKeys` instead of the
terminal, renders nothing (`tea.WithoutRenderer()`), and prints the final state, which the check
compares:

```
2 todos, 1 done, saved 3 times
[ ] buy milk
[x] walk the dog
```

## Go modules

`goRequires`: `github.com/charmbracelet/bubbletea@v1.3.10`, `github.com/charmbracelet/lipgloss@v1.1.0`,
`github.com/charmbracelet/bubbles@v1.0.0`. Facades are generated for `bubbletea`, `lipgloss` and
the bubbles packages `textinput`, `spinner` and `key`. bubbles' `go.mod` asks for Go 1.24.2 or
later (with Go's default `GOTOOLCHAIN=auto`, an older `go` downloads it). The first build needs
network access.

## Limitations

- **Needs a terminal.** `./mill examples.gotui.goRun` fails with `could not open a new TTY`: Mill
  runs tasks in its background daemon, which has no terminal. Run the built executable directly
  (above). `./mill -i examples.gotui.goRun` (without the daemon) starts it, but Mill prefixes
  every output line, which garbles the screen. `--demo` needs no terminal.
- **Runs only as Go.** Facade members throw on the JVM: `./mill examples.gotui.run --demo` fails
  with `UnsupportedOperationException` at `tea.WithInput`.
- **Go durations are facade values, not numbers:** `time.Duration(200_000_000L)` builds one from
  nanoseconds, as Go's conversion does; Scala cannot do arithmetic on them
  (`200 * time.Millisecond`).
- **Members left out of the generated facades** (see the `// not generated:` comments in
  `out/examples/gotui/goFacadeSources.dest`): `tea.BatchMsg`'s conversion (a slice of funcs),
  `spinner.NewModel` (a variadic func type; `spinner.New` is the same function) and
  `lipgloss.WhitespaceOption`'s conversion (a func of an unexported type). The example needs none
  of them.

See also the documentation's [Limitations](../../docs/_docs/user/limitations.md) page.
