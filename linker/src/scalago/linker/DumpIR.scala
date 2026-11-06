package scalago.linker

import java.io.File
import java.nio.file.Path

/** Debugging aid: prints the IR found on a classpath (`scalago.linker.DumpIR <cp> [class prefix]`). */
object DumpIR:
  def main(args: Array[String]): Unit =
    val classpath = args(0).split(File.pathSeparator).toSeq.map(Path.of(_))
    val prefix = args.lift(1).getOrElse("")
    for c <- IRLoader.load(classpath) if c.name.startsWith(prefix) do
      println(s"${c.kind} ${c.name} extends ${(c.superClass.toList ++ c.interfaces).mkString(", ")}${c.goFacade.fold("")(f => s" go=$f")}")
      for f <- c.fields do println(s"  field ${f.name}: ${f.tpe}")
      for m <- c.methods do
        println(s"  method ${m.name}${m.goMember.fold("")(g => s" go=$g")}")
        m.body.foreach(b => println(s"    $b"))
