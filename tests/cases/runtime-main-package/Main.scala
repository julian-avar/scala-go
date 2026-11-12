package app.launch

/** The entry point is an object in a package (main.txt names it). Objects are initialized at
 *  their first access, not at program start, and never if the program does not reach them.
 */
object First:
  println("First initialized")
  val value = 1

object Lazy:
  println("Lazy initialized")
  val value = 2

object Unused:
  println("Unused initialized")
  val value = 3

object Launcher:
  println("Launcher initialized")

  def main(args: Array[String]): Unit =
    println(s"main: args=${args.length} thread=${Thread.currentThread().getName} class=${getClass.getName}")
    println(s"First: ${First.value}")
    if args.nonEmpty then println(Unused.value)
    println("before Lazy")
    println(s"Lazy: ${Lazy.value} ${Lazy.value}")
