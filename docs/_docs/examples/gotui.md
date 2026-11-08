---
title: gotui
---

# gotui

A todo list in the terminal on [bubbletea](https://github.com/charmbracelet/bubbletea), with
[bubbles](https://github.com/charmbracelet/bubbles) components (text input, spinner, key
bindings) and [lipgloss](https://github.com/charmbracelet/lipgloss) styles
([source](https://github.com/julian-avar/scala-go/tree/main/examples/gotui)). The model is a
Scala case class implementing the Go interface `tea.Model` (simplified here):

```scala sc:nocompile
final case class App(todos: Vector[Todo], input: textinput.Model, spin: spinner.Model, ...) extends tea.Model:
  def Init(): tea.Cmd = textinput.Blink
  def Update(msg: tea.Msg): (tea.Model, tea.Cmd) = msg match
    case k: tea.KeyMsg if key.Matches(k, Keys.quit) => (copy(quitting = true), tea.Quit)
    case Saved(n) => (copy(saves = saves + 1), null)                 // a Scala message
    case _ =>
      val (s, spinCmd) = spin.Update(msg)                            // value receivers return copies
      val (i, inputCmd) = input.Update(msg)
      (copy(spin = s, input = i), tea.Batch(spinCmd, inputCmd))
  def View(): String = ...
```

## Running it

```sh
./mill examples.gotui.goBuild && out/examples/gotui/goBuild.dest/app   # in a terminal
./mill examples.gotui.check     # headless: scripted keys (--demo), checks the final state
```

Go modules: bubbletea v1.3.10, lipgloss v1.1.0, bubbles v1.0.0 (Go 1.24.2 or later).

## Limitations

- It needs a terminal, and `./mill examples.gotui.goRun` fails with `could not open a new TTY`:
  Mill runs tasks in a daemon without one. Run the executable directly.
- Facades throw on the JVM, so it runs only as Go.
- `--demo` reads keys from a Go `io.Reader` implemented in Scala and renders nothing
  (`tea.WithoutRenderer()`), so the check needs no terminal.
- A Go `time.Duration` is a facade value, not a number: `time.Duration(200_000_000L)` instead of
  `200 * time.Millisecond`.
- The generated facades leave out `tea.BatchMsg`'s conversion, `spinner.NewModel` and
  `lipgloss.WhitespaceOption`'s conversion (funcs in Go data, a variadic func type, an unexported
  type); the example does not need them.

More in the example's
[README](https://github.com/julian-avar/scala-go/blob/main/examples/gotui/README.md).
