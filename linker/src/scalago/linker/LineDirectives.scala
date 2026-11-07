package scalago.linker

import scalago.ir.Position

/** A line of a generated method body, with the Scala position of the code it comes from.
 *  `NoPosition` marks lines that cannot fail or call anything (braces, jumps, declarations),
 *  which need no position of their own.
 */
final case class GoLine(text: String, pos: Position):
  def indented: GoLine = copy(text = "\t" + text)

/** Go line directives for the functions generated from Scala methods, in one Go file. They make
 *  the Go toolchain report Scala positions: in stack traces (`runtime.CallersFrames`), Go panics,
 *  and compiler and `go vet` messages.
 *
 *  A directive sets the position of the code after it, and the lines that follow count up from
 *  there, so a directive is only needed where a line's Scala position differs from the one it
 *  would get implicitly. Directives are inline comments, which keep the indentation:
 *  `/*line Main.scala:5:3*/` names the file where it changes, and `/*line :6:5*/` keeps the
 *  previous one. Each function's header gets the position of the method's definition.
 *
 *  These functions come last in their Go file, after the code that has no Scala positions (types,
 *  forwarders, ...). [[end]] returns to the Go file's own positions after them, with a [[Restore]]
 *  placeholder that [[LineDirectives.resolveRestores]] turns into a directive.
 */
final class LineDirectives(enabled: Boolean):
  import LineDirectives.*

  private var file = "" // the file named by the last directive ("": the Go file itself)
  private var nextLine = 0 // the Scala line the next Go line gets implicitly

  /** The lines of a function: its header, positioned at `pos`, and its body, indented. The
   *  closing brace is left to the caller.
   */
  def function(header: String, pos: Position, body: List[GoLine]): List[String] =
    val out = List.newBuilder[String]
    if !enabled then
      out += header
      body.foreach(l => out += "\t" + l.text)
    else
      // Code without positions keeps the Go file's.
      if !isRepresentable(pos) && file.nonEmpty then out ++= end()
      out += line(header, pos, force = true)
      body.foreach(l => out += line("\t" + l.text, l.pos, force = false))
      nextLine = 0 // the caller adds lines (the closing brace): the next line is unknown
    out.result()

  /** Returns to the Go file's own positions (after the last function). */
  def end(): List[String] =
    if file.isEmpty then Nil
    else
      file = ""
      nextLine = 0
      List(Restore)

  private def line(text: String, pos: Position, force: Boolean): String =
    val result =
      if isRepresentable(pos) && (force || pos.source != file || pos.line != nextLine) then
        val tabs = text.takeWhile(_ == '\t')
        val fileName = if pos.source == file then "" else pos.source
        file = pos.source
        nextLine = pos.line
        s"$tabs/*line $fileName:${pos.line}:${math.max(pos.column, 1)}*/${text.substring(tabs.length)}"
      else text
    if nextLine > 0 then nextLine += 1 + text.count(_ == '\n')
    result

object LineDirectives:
  /** Placeholder for a `//line` directive back to the Go file's own position. */
  val Restore = "//line <restore>"

  /** Whether a position can be given in a `/*line ...*/` comment. */
  private def isRepresentable(pos: Position): Boolean =
    pos.line > 0 && pos.source.nonEmpty && !pos.source.contains("*/") && !pos.source.contains('\n')

  /** Replaces each [[Restore]] line of a Go file with a directive to the line after it. */
  def resolveRestores(content: String, goFileName: String): String =
    if !content.contains(Restore) then content
    else
      val lines = content.split("\n", -1)
      for i <- lines.indices if lines(i) == Restore do lines(i) = s"//line $goFileName:${i + 2}"
      lines.mkString("\n")
