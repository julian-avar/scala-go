---
title: gotui
---

# gotui

A todo list in the terminal on [bubbletea](https://github.com/charmbracelet/bubbletea), with
[bubbles](https://github.com/charmbracelet/bubbles) components (text input, spinner, key
bindings) and [lipgloss](https://github.com/charmbracelet/lipgloss) styles
([source](https://github.com/julian-avar/scala-go/tree/main/examples/gotui)). All three are used
through [generated facades](../interop/generated-facades.md).

bubbletea is the Elm architecture: a model, an update function and a view. The model, `App`, is a
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

- **Go results as tuples.** `Update` returns Go's `(tea.Model, tea.Cmd)` as a Scala tuple.
- **Value receivers.** `textinput.Model` and `spinner.Model` are Go structs with value receivers:
  `Update` returns an updated copy, stored back into the case class.
- **Scala messages.** Any Scala value is a `tea.Msg`. Saving is simulated with
  `tea.Tick(time.Duration(200_000_000L), _ => Saved(count))`: a Go timer calls a Scala function,
  which returns the Scala message `Saved`.
- **Go interfaces implemented in Scala.** `--demo` passes `tea.WithInput(ScriptedKeys(keys))`:
  `ScriptedKeys` implements `io.Reader` from `scalago-gostd`, whose `io` and `time` types the
  generated facades reuse. It returns `io.EOF` by throwing `GoException(io.EOF)`.

Build it with `./mill examples.gotui.goBuild` and run `out/examples/gotui/goBuild.dest/app` in a
terminal; `./mill examples.gotui.check` runs it headless. The
[README](https://github.com/julian-avar/scala-go/blob/main/examples/gotui/README.md) has the keys,
the expected output and the limitations.
