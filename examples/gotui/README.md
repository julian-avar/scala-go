# gotui

A todo list in the terminal, in Scala on [bubbletea](https://github.com/charmbracelet/bubbletea),
[bubbles](https://github.com/charmbracelet/bubbles) and
[lipgloss](https://github.com/charmbracelet/lipgloss). Source: [`src/Main.scala`](src/Main.scala).
Explained in the documentation's [gotui](../../docs/_docs/examples/gotui.md) page.

## Running it

From the repository's root, build it and run the executable in a terminal:

```sh
./mill examples.gotui.goBuild
out/examples/gotui/goBuild.dest/app        # type a todo, Enter; Tab: the list (j/k, space, d, q); Ctrl+C quits
./mill examples.gotui.check                # headless: scripted keys, checks the final state
```

`check` runs the program with `--demo`: scripted keys instead of the terminal, no rendering
(`tea.WithoutRenderer()`). It expects this final state:

```
2 todos, 1 done, saved 3 times
[ ] buy milk
[x] walk the dog
```

Go modules (`goRequires`): bubbletea v1.3.10, lipgloss v1.1.0, bubbles v1.0.0. Facades are generated
for `bubbletea`, `lipgloss` and the bubbles packages `textinput`, `spinner` and `key`. bubbles needs
Go 1.24.2 or later; with Go's default `GOTOOLCHAIN=auto`, an older `go` downloads it. The first
build needs network access.

## Limitations

- **`goRun` fails with `could not open a new TTY`:** Mill runs tasks in its daemon, which has no
  terminal. Run the built executable directly. `./mill -i examples.gotui.goRun` (no daemon) starts
  it, but Mill prefixes every output line, which garbles the screen.
- **Runs only as Go.** Facade members throw on the JVM: `./mill examples.gotui.run --demo` fails
  with `UnsupportedOperationException` at `tea.WithInput`.
- **A Go `time.Duration` is a facade value, not a number:** no `200 * time.Millisecond`. Build
  it from nanoseconds, as Go's conversion does: `time.Duration(200_000_000L)`.
- **Left out of the generated facades** (`// not generated:` comments in
  `out/examples/gotui/goFacadeSources.dest`), none needed here: `tea.BatchMsg`'s conversion (a
  slice of funcs), `spinner.NewModel` (a variadic func type; `spinner.New` is the same function),
  `lipgloss.WhitespaceOption`'s conversion (a func of an unexported type).

All limitations: [docs/_docs/user/limitations.md](../../docs/_docs/user/limitations.md).
