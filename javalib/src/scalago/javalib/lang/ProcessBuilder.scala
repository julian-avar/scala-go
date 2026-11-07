package scalago.javalib.lang

import java.io.{File, IOException}
import java.util.{ArrayList, Arrays, List}

import scalago.runtime.{OS, Subprocesses}

/** java.lang.ProcessBuilder, on Go's os/exec (`scalago.runtime.Subprocesses`). As on the JDK, the
 *  program is looked up on this process's `PATH`, and a relative program path is relative to the
 *  directory.
 */
final class ProcessBuilder(private var commandList: List[String]):
  if commandList == null then throw new java.lang.NullPointerException()

  def this(command: Array[String]) = this(new ArrayList[String](Arrays.asList(command)))

  private var dir: File = null
  private var env: ProcessBuilder.Environment = null
  private val redirects = Array(ProcessBuilder.Redirect.PIPE, ProcessBuilder.Redirect.PIPE, ProcessBuilder.Redirect.PIPE)
  private var mergeErrors = false

  def command(): List[String] = commandList

  def command(command: List[String]): ProcessBuilder =
    if command == null then throw new java.lang.NullPointerException()
    commandList = command
    this

  def command(command: Array[String]): ProcessBuilder =
    commandList = new ArrayList[String](Arrays.asList(command))
    this

  def directory(): File = dir

  def directory(directory: File): ProcessBuilder =
    dir = directory
    this

  /** A copy of this process's environment, which the process gets. */
  def environment(): java.util.Map[String, String] =
    if env == null then
      env = new ProcessBuilder.Environment()
      env.putAll(System.getenv())
    env

  def redirectInput(source: ProcessBuilder.Redirect): ProcessBuilder =
    if source.`type`() == ProcessBuilder.Redirect.Type.WRITE || source.`type`() == ProcessBuilder.Redirect.Type.APPEND then
      throw new java.lang.IllegalArgumentException("Redirect invalid for reading: " + source)
    redirects(0) = source
    this

  def redirectOutput(destination: ProcessBuilder.Redirect): ProcessBuilder =
    if destination.`type`() == ProcessBuilder.Redirect.Type.READ then
      throw new java.lang.IllegalArgumentException("Redirect invalid for writing: " + destination)
    redirects(1) = destination
    this

  def redirectError(destination: ProcessBuilder.Redirect): ProcessBuilder =
    if destination.`type`() == ProcessBuilder.Redirect.Type.READ then
      throw new java.lang.IllegalArgumentException("Redirect invalid for writing: " + destination)
    redirects(2) = destination
    this

  def redirectInput(file: File): ProcessBuilder = redirectInput(ProcessBuilder.Redirect.from(file))
  def redirectOutput(file: File): ProcessBuilder = redirectOutput(ProcessBuilder.Redirect.to(file))
  def redirectError(file: File): ProcessBuilder = redirectError(ProcessBuilder.Redirect.to(file))

  def redirectInput(): ProcessBuilder.Redirect = redirects(0)
  def redirectOutput(): ProcessBuilder.Redirect = redirects(1)
  def redirectError(): ProcessBuilder.Redirect = redirects(2)

  def inheritIO(): ProcessBuilder =
    java.util.Arrays.fill(redirects.asInstanceOf[Array[Object]], ProcessBuilder.Redirect.INHERIT)
    this

  def redirectErrorStream(): scala.Boolean = mergeErrors

  def redirectErrorStream(redirectErrorStream: scala.Boolean): ProcessBuilder =
    mergeErrors = redirectErrorStream
    this

  def start(): Process =
    val command = commandList.toArray(new Array[String](0))
    if command.length == 0 then throw new java.lang.ArrayIndexOutOfBoundsException("Index 0 out of bounds for length 0")
    for arg <- command do
      if arg == null then throw new java.lang.NullPointerException()
    val program = command(0)
    def failure(message: String) =
      new IOException("Cannot run program \"" + program + "\"" +
        (if dir == null then "" else " (in directory \"" + dir + "\")") + ": " + message)
    for arg <- command do
      if arg.indexOf('\u0000') >= 0 then throw new IOException("invalid null character in command")
    val environment =
      if env == null then null
      else
        val kv = new Array[String](2 * env.size())
        var i = 0
        env.forEach { (k, v) =>
          kv(i) = k
          kv(i + 1) = v
          i += 2
        }
        kv
    val kinds = redirects.map(_.kind)
    val paths = redirects.map(r => if r.file() == null then null else r.file().getPath())
    val result = Subprocesses.start(command, if dir == null then null else dir.getPath(), environment, kinds, paths,
      mergeErrors)
    if OS.isError(result) then throw failure(Subprocesses.errorMessage(result))
    new ProcessImpl(result, mergeErrors)

object ProcessBuilder:
  /** The environment of a ProcessBuilder: names and values are checked as on the JDK. */
  private final class Environment extends java.util.HashMap[String, String]:
    override def put(name: String, value: String): String =
      if name == null || value == null then throw new java.lang.NullPointerException()
      if name.indexOf('=') >= 0 || name.indexOf('\u0000') >= 0 then
        throw new java.lang.IllegalArgumentException("Invalid environment variable name: \"" + name + "\"")
      if value.indexOf('\u0000') >= 0 then
        throw new java.lang.IllegalArgumentException("Invalid environment variable value: \"" + value + "\"")
      super.put(name, value)

    override def putAll(m: java.util.Map[? <: String, ? <: String]): Unit =
      m.forEach((k, v) => put(k, v))

  abstract class Redirect private[lang] ():
    def `type`(): Redirect.Type
    def file(): File = null
    private[lang] def kind: Int

  object Redirect:
    final class Type private (name: String, ordinal: Int) extends Enum[Type](name, ordinal)

    object Type:
      val PIPE: Type = new Type("PIPE", 0)
      val INHERIT: Type = new Type("INHERIT", 1)
      val READ: Type = new Type("READ", 2)
      val WRITE: Type = new Type("WRITE", 3)
      val APPEND: Type = new Type("APPEND", 4)

      def values(): Array[Type] = Array(PIPE, INHERIT, READ, WRITE, APPEND)

      def valueOf(name: String): Type =
        values().find(_.name() == name).getOrElse(
          throw new java.lang.IllegalArgumentException("No enum constant java.lang.ProcessBuilder.Redirect.Type." + name))

    val PIPE: Redirect = new Redirect:
      def `type`(): Type = Type.PIPE
      private[lang] def kind: Int = Subprocesses.Pipe
      override def toString(): String = "PIPE"

    val INHERIT: Redirect = new Redirect:
      def `type`(): Type = Type.INHERIT
      private[lang] def kind: Int = Subprocesses.Inherit
      override def toString(): String = "INHERIT"

    val DISCARD: Redirect = to(new File("/dev/null"))

    def from(file: File): Redirect = new FileRedirect(file, Type.READ, Subprocesses.Read, "read from")
    def to(file: File): Redirect = new FileRedirect(file, Type.WRITE, Subprocesses.Write, "write to")
    def appendTo(file: File): Redirect = new FileRedirect(file, Type.APPEND, Subprocesses.Append, "append to")

    private final class FileRedirect(f: File, t: Type, k: Int, description: String) extends Redirect:
      if f == null then throw new java.lang.NullPointerException()
      def `type`(): Type = t
      override def file(): File = f
      private[lang] def kind: Int = k
      override def toString(): String = "redirect to " + description + " file \"" + f + "\""
      override def equals(that: Any): Boolean = that match
        case that: FileRedirect => (that.`type`() eq t) && that.file() == f
        case _                  => false
      override def hashCode(): Int = f.hashCode() ^ t.hashCode()
