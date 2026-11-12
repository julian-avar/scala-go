---
title: gotui
---

# gotui

A todo list in the terminal on [bubbletea](https://github.com/charmbracelet/bubbletea), with
[bubbles](https://github.com/charmbracelet/bubbles) components (text input, spinner, key
bindings) and [lipgloss](https://github.com/charmbracelet/lipgloss) styles, all through
[generated facades](../interop/generated-facades.md)
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

Any Scala value is a `tea.Msg`: `tea.Tick(time.Duration(200_000_000L), _ => Saved(count))` has a
Go timer call a Scala function that returns one. `--demo` feeds keys through `ScriptedKeys`, a
Scala `io.Reader` (from `scalago-gostd`) that ends with `throw GoException(io.EOF)`.

Build it with `./mill examples.gotui.goBuild` and run `out/examples/gotui/goBuild.dest/app` in a
terminal; `./mill examples.gotui.check` runs it headless.

Limitations:

- `goRun` fails with `could not open a new TTY` (Mill's daemon has no terminal); `./mill -i`
  starts it, but Mill prefixes every output line, which garbles the screen.
- Left out of the generated facades, none needed here: `tea.BatchMsg`'s conversion (a slice of
  funcs), `spinner.NewModel` (a variadic func type; `spinner.New` is the same function).
