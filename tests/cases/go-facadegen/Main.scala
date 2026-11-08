import gofacades.{elm, time}
import scalago.go.*

/** A Scala message, which Go code passes around as an elm.Msg. */
final case class Tick(n: Int)

/** A model in the Elm architecture, implementing a Go interface whose Update has two results. */
final case class App(events: Vector[String], counter: elm.Counter) extends elm.Model:
  def Init(): elm.Cmd = () => Tick(0)

  def Update(msg: elm.Msg): (elm.Model, elm.Cmd) = msg match
    case k: elm.KeyMsg if elm.Matches(k, "q", "ctrl+c") => (log("quit"), elm.Quit)
    case k: elm.KeyMsg if k.Key == "b" => (log("batch"), elm.Batch(() => Tick(1), null, () => Tick(2)))
    case k: elm.KeyMsg =>
      // The Go component gets the key as Go code passes it: by value.
      val (c, cmd) = counter.Update(k)
      (copy(counter = c).log(s"key ${k.String()}: ${c.View()}"), cmd)
    case Tick(n) => (log(s"tick $n"), null)
    case other   => (log(s"other ${elm.Describe(other)}"), null)

  def View(): String = events.mkString("\n")

  private def log(event: String): App = copy(events = events :+ event)

final class Letters(letters: String) extends elm.Source:
  private var i = 0
  def Next(): String =
    if i == letters.length then throw GoException(elm.ErrDone)
    i += 1
    letters(i - 1).toString

object Squares extends elm.Pairs:
  def Pair(i: Long): (String, Long) = (s"n$i", i * i)

object Main:
  def main(args: Array[String]): Unit =
    val app = App(Vector.empty, elm.NewCounter("count"))
    elm.Run(app, "+", "x", "+", "b", "q", "+") match
      case a: App => println(a.View())
      case other  => println(s"unexpected model $other")

    // Named func types: Scala functions as options, which mutate the Go struct through its pointer.
    val c = elm.NewCounter("c", elm.WithStart(5), counter => counter.Label = "renamed")
    println(c.View())
    c.Reset()
    println(c.View())

    // Go structs go to Go interfaces as Go code passes them.
    val k = new elm.KeyMsg
    k.Key = "z"
    k.Alt = true
    println(elm.Describe(k))
    println(elm.Describe(c))
    println(elm.Describe("text"))
    println(elm.Describe(null))

    // Named basic types are converted as in Go; generic functions take their constraint.
    val red = elm.Color("#ff00aa")
    println(red.Hex())
    println(elm.Paint(red, k))
    println(s"${elm.Matches(k, "z", "alt+z")} ${elm.Matches(k, "z")}")

    // Types of other packages: time (standard library) and a package of the module.
    val every = elm.Every(time.Duration(90L * 60 * 1000000000L), t => Tick(t.Hour().toInt))
    println(every())
    println(elm.DefaultStyle.Render("styled"))
    val plain = elm.DefaultStyle
    plain.Upper = false
    println(plain.Render("plain"))

    // Go interfaces implemented in Scala: errors from exceptions, several results from tuples.
    println(elm.Drain(Letters("abc")))
    println(elm.Collect(Squares, 4))
    println(elm.Name(elm.Mark("sealed")))
