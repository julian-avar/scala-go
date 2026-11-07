import java.io.*
import java.lang.ProcessBuilder.Redirect
import java.lang.ProcessBuilder as JProcessBuilder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Using

/** Subprocesses through java.lang.ProcessBuilder and scala.sys.process, with commands found on
 *  Linux and macOS (sh, echo, cat, printf). Only deterministic facts are printed.
 */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def error(body: => Any): String =
    try s"ok ${body}"
    catch case e: Exception => s"${e.getClass.getName}: ${e.getMessage}"

  def output(p: java.lang.Process): String = new String(p.getInputStream.readAllBytes(), UTF_8)

  def processBuilder(root: Path): Unit =
    section("ProcessBuilder")
    val pb = new JProcessBuilder("sh", "-c", "echo out; echo err >&2; exit 3")
    println(s"${pb.command()} ${pb.directory()} ${pb.redirectInput()} ${pb.redirectOutput()} ${pb.redirectErrorStream()}")
    val p = pb.start()
    println(s"stdout '${output(p).trim}' stderr '${new String(p.getErrorStream.readAllBytes(), UTF_8).trim}'")
    println(s"waitFor ${p.waitFor()} exitValue ${p.exitValue()} alive ${p.isAlive} pid ${p.pid() > 0}")

    val merged = new JProcessBuilder("sh", "-c", "echo one; echo two >&2; echo three").redirectErrorStream(true).start()
    println(s"merged '${output(merged).replace('\n', ' ').trim}' ${merged.getErrorStream.read()} ${merged.waitFor()}")

    val cat = new JProcessBuilder("cat").start()
    Using.resource(new PrintStream(cat.getOutputStream, true, "UTF-8")) { in =>
      in.println("through cat: grüße")
      in.println("second line")
    }
    println(s"cat: ${cat.inputReader().lines().iterator().asScala.mkString(" | ")} -> ${cat.waitFor()}")

    val env = new JProcessBuilder("sh", "-c", "echo \"$SCALAGO_A-$SCALAGO_B-${HOME:+home}\"")
    env.environment().put("SCALAGO_A", "a")
    env.environment().put("SCALAGO_B", "b")
    println(s"environment '${output(env.start()).trim}'")
    env.environment().clear()
    env.environment().put("SCALAGO_A", "only")
    println(s"cleared environment '${output(env.start()).trim}'")
    println(error(env.environment().put("A=B", "x")))
    println(error(env.environment().put("A", null)).takeWhile(_ != ':'))

    val dir = Files.createDirectory(root.resolve("work dir"))
    Files.writeString(dir.resolve("file.txt"), "in the directory\n")
    val inDir = new JProcessBuilder("cat", "file.txt").directory(dir.toFile).start()
    println(s"directory: ${output(inDir).trim}")

    section("redirects")
    val target = root.resolve("out.txt").toFile
    val toFile = new JProcessBuilder("echo", "to a file").redirectOutput(target).start()
    println(s"${toFile.waitFor()} stream ${toFile.getInputStream.read()} file '${Files.readString(target.toPath).trim}'")
    new JProcessBuilder("echo", "appended").redirectOutput(Redirect.appendTo(target)).start().waitFor()
    println(s"appended '${Files.readString(target.toPath).replace('\n', ' ').trim}'")
    val fromFile = new JProcessBuilder("cat").redirectInput(target).start()
    println(s"from a file '${output(fromFile).replace('\n', ' ').trim}' ${error(fromFile.getOutputStream.write(1))}")
    val discard = new JProcessBuilder("sh", "-c", "echo discarded; echo kept >&2").redirectOutput(Redirect.DISCARD).start()
    println(s"discard '${output(discard)}' '${new String(discard.getErrorStream.readAllBytes(), UTF_8).trim}'")
    println(s"${Redirect.PIPE} ${Redirect.INHERIT} ${Redirect.DISCARD.`type`()} ${Redirect.from(new File("x"))} ${Redirect.to(new File("y"))}")
    println(s"${Redirect.appendTo(new File("z"))} ${Redirect.to(new File("y")) == Redirect.to(new File("y"))} ${Redirect.Type.values().toList}")
    println(error(new JProcessBuilder("cat").redirectInput(Redirect.to(target))).replace(root.toString, "<root>"))
    println(error(new JProcessBuilder("cat").redirectOutput(Redirect.from(target))).replace(root.toString, "<root>"))
    println("before an inheriting child")
    val inherit = new JProcessBuilder("sh", "-c", "echo inherited stdout").inheritIO().start()
    println(s"inherited: ${inherit.waitFor()} ${inherit.getInputStream.read()}")

    section("failures")
    println(error(new JProcessBuilder("scalago-no-such-program").start()))
    println(error(new JProcessBuilder("scalago-no-such-program").directory(new File("/")).start()))
    println(error(new JProcessBuilder("cat").directory(root.resolve("missing").toFile).start()).replace(root.toString, "<root>"))
    println(error(new JProcessBuilder("cat").redirectInput(root.resolve("missing").toFile).start()).replace(root.toString, "<root>"))
    println(error(new JProcessBuilder(new java.util.ArrayList[String]()).start()))
    println(error(new JProcessBuilder("echo", "a\u0000b").start()))

    section("lifecycle")
    val sleeper = new JProcessBuilder("sh", "-c", "read line; echo got $line").start()
    println(s"alive ${sleeper.isAlive} ${error(sleeper.exitValue())} waitFor(50ms) ${sleeper.waitFor(50, TimeUnit.MILLISECONDS)}")
    sleeper.getOutputStream.write("x\n".getBytes(UTF_8))
    sleeper.getOutputStream.flush()
    println(s"${output(sleeper).trim} waitFor(10s) ${sleeper.waitFor(10, TimeUnit.SECONDS)} ${sleeper.exitValue()}")
    val killed = new JProcessBuilder("sh", "-c", "read line").start()
    killed.destroy()
    println(s"destroyed: ${killed.waitFor()} alive ${killed.isAlive}")
    val forced = new JProcessBuilder("sh", "-c", "trap '' TERM; read line").start()
    Thread.sleep(100)
    forced.destroyForcibly()
    println(s"destroyed forcibly: ${forced.waitFor()} ${forced.toHandle.isAlive}")
    println(s"current pid ${ProcessHandle.current().pid() == ProcessHandle.current().pid()} ${ProcessHandle.current().pid() > 0} alive ${ProcessHandle.current().isAlive}")
    println(error(ProcessHandle.current().destroy()))
    val rt = Runtime.getRuntime.exec(Array("sh", "-c", "echo $X"), Array("X=from exec"))
    println(s"Runtime.exec '${output(rt).trim}' ${rt.waitFor()}")

  def sysProcess(root: Path): Unit =
    section("scala.sys.process")
    println(Seq("echo", "hello", "world").!!.trim)
    println(s"exit ${Seq("sh", "-c", "exit 4").!}")
    println(s"${"echo split into words".!!.trim}")
    val out = new StringBuilder
    val err = new StringBuilder
    val logger = ProcessLogger(line => out.append(s"[$line]"), line => err.append(s"<$line>"))
    val status = Seq("sh", "-c", "echo a; echo b; echo c >&2; exit 2").!(logger)
    println(s"logger $status $out $err")
    println(error(Seq("sh", "-c", "exit 1").!!))
    println(Process(Seq("sh", "-c", "echo $SCALAGO_V"), None, "SCALAGO_V" -> "extra env").!!.trim)
    println(Process("cat file.txt", root.resolve("work dir").toFile).!!.trim)
    val file = root.resolve("sys.txt").toFile
    println(s"#> ${(Seq("echo", "into a file") #> file).!} ${Files.readString(file.toPath).trim}")
    println(s"#< ${(Seq("cat") #< file).!!.trim}")
    println(s"#&& ${(Seq("sh", "-c", "exit 0") #&& Seq("echo", "and")).!!.trim} #|| ${(Seq("sh", "-c", "exit 1") #|| Seq("echo", "or")).!!.trim}")
    println(s"run: ${Seq("sh", "-c", "exit 5").run().exitValue()}")
    println("before !")
    println(s"! ${Seq("echo", "printed by the child").!}")

  def main(args: Array[String]): Unit =
    val root = Files.createTempDirectory("scalago-processes")
    try
      processBuilder(root)
      sysProcess(root)
    finally
      Using.resource(Files.walk(root)) { s =>
        s.sorted(java.util.Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
      }
