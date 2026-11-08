import java.nio.charset.StandardCharsets.UTF_8

import gofacades.{key, lipgloss, spinner, tea, textinput}
import scalago.gostd.{io, time}
import scalago.go.{GoByte, GoException, GoSlice}

/** A todo list in the terminal, written in Scala on bubbletea (the Elm architecture: a model, an
 *  update function and a view), with components from bubbles and styles from lipgloss.
 *
 *  The executable (`mill examples.gotui.goBuild`) runs it in a terminal; `mill
 *  examples.gotui.goRun` cannot, as Mill's daemon has no terminal. `--demo` feeds it scripted
 *  keys without a terminal and prints the final state.
 */
object Main:
  def main(args: Array[String]): Unit =
    val demo = args.contains("--demo")
    val options =
      if demo then
        // Add three todos, switch to the list, tick the second one, delete the third, quit.
        val keys = Seq("buy milk", "\r", "walk the dog", "\r", "learn bubbletea", "\r", "\t", "j", " ", "j", "d", "q")
        Seq(tea.WithInput(ScriptedKeys(keys)), tea.WithoutRenderer())
      else Seq(tea.WithAltScreen())
    val result = tea.NewProgram(App.initial, options*).Run()
    result match
      case app: App => println(app.summary)
      case other    => println(s"unexpected final model: $other")

/** Keys as a terminal delivers them, one per read: a Go io.Reader implemented in Scala (the facade
 *  of scalago-gostd, which the generated facades of bubbletea use).
 */
final class ScriptedKeys(keys: Seq[String]) extends io.Reader:
  private var rest = keys.toList

  def Read(p: GoSlice[GoByte]): Long = rest match
    case Nil => throw GoException(io.EOF) // the Go error the method returns
    case key :: more =>
      rest = more
      val bytes = key.getBytes(UTF_8)
      for i <- bytes.indices do p(i) = GoByte(bytes(i))
      bytes.length

/** A todo. */
final case class Todo(title: String, done: Boolean)

/** A Scala message: a todo list was saved (Go code passes it around as a tea.Msg). */
final case class Saved(count: Int)

object Keys:
  val up = key.NewBinding(key.WithKeys("up", "k"), key.WithHelp("↑/k", "up"))
  val down = key.NewBinding(key.WithKeys("down", "j"), key.WithHelp("↓/j", "down"))
  val toggle = key.NewBinding(key.WithKeys(" ", "x"), key.WithHelp("space", "done"))
  val delete = key.NewBinding(key.WithKeys("d"), key.WithHelp("d", "delete"))
  val switchFocus = key.NewBinding(key.WithKeys("tab"), key.WithHelp("tab", "switch"))
  val quit = key.NewBinding(key.WithKeys("q", "ctrl+c"), key.WithHelp("q", "quit"))
  val help: String =
    Seq(up, down, toggle, delete, switchFocus, quit).map(b => s"${b.Help().Key} ${b.Help().Desc}").mkString(" • ")

object Styles:
  val title = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("#FAFAFA")).Background(lipgloss.Color("62")).Padding(0, 1)
  val cursor = lipgloss.NewStyle().Foreground(lipgloss.Color("205"))
  val done = lipgloss.NewStyle().Strikethrough(true).Foreground(lipgloss.Color("241"))
  val help = lipgloss.NewStyle().Foreground(lipgloss.Color("241"))
  val box = lipgloss.NewStyle().Border(lipgloss.RoundedBorder()).Padding(0, 1)

final case class App(
    todos: Vector[Todo],
    cursor: Int,
    input: textinput.Model,
    spin: spinner.Model,
    typing: Boolean,
    saving: Int,
    saves: Int,
    quitting: Boolean
) extends tea.Model:

  def Init(): tea.Cmd = textinput.Blink

  def Update(msg: tea.Msg): (tea.Model, tea.Cmd) = msg match
    case k: tea.KeyMsg if key.Matches(k, Keys.quit) && !(typing && k.String() == "q") =>
      if saving > 0 then (copy(quitting = true), null) else (copy(quitting = true), tea.Quit)
    case k: tea.KeyMsg if key.Matches(k, Keys.switchFocus) =>
      if typing then input.Blur() else input.Focus()
      (copy(typing = !typing), null)
    case k: tea.KeyMsg if typing => onTyping(k)
    case k: tea.KeyMsg => (onListKey(k), null)
    case Saved(n) =>
      val next = copy(saving = saving - 1, saves = saves + 1)
      (next, if next.quitting && next.saving == 0 then tea.Quit else null)
    case _ =>
      // Everything else (spinner ticks, cursor blinks) goes to the components.
      val (s, spinCmd) = spin.Update(msg)
      val (i, inputCmd) = input.Update(msg)
      (copy(spin = s, input = i), tea.Batch(spinCmd, inputCmd))

  private def onTyping(k: tea.KeyMsg): (tea.Model, tea.Cmd) =
    if k.Type == tea.KeyEnter && input.Value().trim.nonEmpty then
      val title = input.Value().trim
      input.Reset()
      val next = copy(todos = todos :+ Todo(title, false), saving = saving + 1)
      (next, tea.Batch(spin.Tick, save(next.todos.size)))
    else
      val (i, cmd) = input.Update(k)
      (copy(input = i), cmd)

  private def onListKey(k: tea.KeyMsg): App =
    if key.Matches(k, Keys.up) then copy(cursor = math.max(0, cursor - 1))
    else if key.Matches(k, Keys.down) then copy(cursor = math.min(todos.size - 1, cursor + 1))
    else if key.Matches(k, Keys.toggle) && todos.nonEmpty then
      copy(todos = todos.updated(cursor, todos(cursor).copy(done = !todos(cursor).done)))
    else if key.Matches(k, Keys.delete) && todos.nonEmpty then
      val rest = todos.patch(cursor, Nil, 1)
      copy(todos = rest, cursor = math.max(0, math.min(cursor, rest.size - 1)))
    else this

  /** Saving takes a while: a Go timer delivers a Scala message. A Go `time.Duration` is not a
   *  Scala number, so it is converted from nanoseconds (`200 * time.Millisecond` is not possible).
   */
  private def save(count: Int): tea.Cmd = tea.Tick(time.Duration(200_000_000L), _ => Saved(count))

  def View(): String =
    if quitting && saving == 0 then return ""
    val items =
      if todos.isEmpty then Seq("  nothing to do")
      else
        todos.zipWithIndex.map { (t, i) =>
          val mark = if i == cursor && !typing then Styles.cursor.Render("›") else " "
          val text = if t.done then Styles.done.Render(s"[x] ${t.title}") else s"[ ] ${t.title}"
          s"$mark $text"
        }
    val status = if saving > 0 then s"${spin.View()} saving…" else s"saved $saves times"
    val body = lipgloss.JoinVertical(lipgloss.Left, (items :+ "" :+ input.View() :+ "" :+ status)*)
    Seq(Styles.title.Render("Todos"), Styles.box.Render(body), Styles.help.Render(Keys.help)).mkString("\n")

  def summary: String =
    val done = todos.count(_.done)
    (s"${todos.size} todos, $done done, saved $saves times" +: todos.map(t => s"${if t.done then "[x]" else "[ ]"} ${t.title}"))
      .mkString("\n")

object App:
  def initial: App =
    val input = textinput.New()
    input.Placeholder = "What needs doing?"
    input.CharLimit = 80
    input.Width = 40
    input.Focus()
    val spin = spinner.New(spinner.WithSpinner(spinner.Dot), spinner.WithStyle(Styles.cursor))
    App(Vector.empty, 0, input, spin, typing = true, saving = 0, saves = 0, quitting = false)
