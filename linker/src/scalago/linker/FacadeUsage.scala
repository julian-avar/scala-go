package scalago.linker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scalago.ir.*

/** Writes a Scala program that uses every member of the Go facades of a Scala package, read from
 *  their IR: linking it and building the Go module checks all of them, which a program that uses
 *  a few would not (facades are checked only where used). The uses are in a branch that never
 *  runs, with `null` arguments. Generic members and classes are instantiated with type arguments
 *  that satisfy the Go constraints (`GoInt` for `cmp.Ordered`, `GoSlice[GoByte]` for
 *  `~[]byte | ~string`), read from the Go API.
 *  {{{
 *  scalago-facadeusage --classpath <cp> --package <scala package> --out <file.scala> [--object <name>]
 *  }}}
 */
object FacadeUsage:
  /** Uses per method: a JVM method's code is at most 64 KB. */
  private val ChunkSize = 150

  /** The source of a Scala type that the plugin recorded. */
  def source(t: ScalaType): String = t match
    case ScalaType.Prim(Type.Void) => "_root_.scala.Unit"
    case ScalaType.Prim(p)         => s"_root_.scala.$p"
    case ScalaType.Arr(e)          => s"_root_.scala.Array[${source(e)}]"
    case ScalaType.Param(n)        => n
    case ScalaType.Ref(ClassName.Object, Nil) => "_root_.scala.Any"
    case ScalaType.Ref(name, args) =>
      val ref = name.stripSuffix("$").split('.').map(FacadeGen.ident).mkString("_root_.", ".", "").replace('$', '.')
      if args.isEmpty then ref else args.map(source).mkString(s"$ref[", ", ", "]")

  private def substitute(t: ScalaType, bindings: Map[String, ScalaType]): ScalaType = t match
    case ScalaType.Param(n)     => bindings.getOrElse(n, t)
    case ScalaType.Ref(n, args) => ScalaType.Ref(n, args.map(substitute(_, bindings)))
    case ScalaType.Arr(e)       => ScalaType.Arr(substitute(e, bindings))
    case other                  => other

  private def value(t: ScalaType): String = s"null.asInstanceOf[${source(t)}]"

  private val GoInt = ScalaType.Ref("scalago.go.GoInt", Nil)

  /** The canonical Scala view of a Go type that is a constraint's term. */
  private def view(t: GoType): ScalaType = t match
    case GoType.Basic(b) =>
      b match
        case "uint"           => ScalaType.Ref("scalago.go.GoUint", Nil)
        case "uint64"         => ScalaType.Ref("scalago.go.GoUint64", Nil)
        case "uintptr"        => ScalaType.Ref("scalago.go.GoUintptr", Nil)
        case "uint32"         => ScalaType.Ref("scalago.go.GoUint32", Nil)
        case "uint8" | "byte" => ScalaType.Ref("scalago.go.GoByte", Nil)
        case "string"         => ScalaType.Ref(GoInterop.GoStringType, Nil)
        case "int64"          => ScalaType.Prim(Type.Long)
        case "int32" | "rune" => ScalaType.Prim(Type.Int)
        case "int16"          => ScalaType.Prim(Type.Short)
        case "uint16"         => ScalaType.Prim(Type.Char)
        case "int8"           => ScalaType.Prim(Type.Byte)
        case "float32"        => ScalaType.Prim(Type.Float)
        case "float64"        => ScalaType.Prim(Type.Double)
        case "bool"           => ScalaType.Prim(Type.Boolean)
        case _                => GoInt
    case GoType.Slice(e) => ScalaType.Ref(GoInterop.GoSliceType, List(view(e)))
    case _               => GoInt

  private final class Generator(api: GoApi, index: FacadeIndex):
    /** A type argument that satisfies Go constraint `c`. */
    private def witness(c: GoType): ScalaType = c match
      case GoType.Named("", "error")          => ScalaType.Ref(GoInterop.GoErrorClass, Nil)
      case GoType.Named(p, n)                 => index.typeOf(p, n).flatMap(_.facade).fold(GoInt)(f => ScalaType.Ref(f.name, Nil))
      case GoType.Constraint(_, term :: _, _) => view(term)
      case _                                  => GoInt

    private def witnesses(names: List[String], constraints: List[(String, GoType)]): Map[String, ScalaType] =
      names.map(n => n -> constraints.collectFirst { case (`n`, c) => witness(c) }.getOrElse(GoInt)).toMap

    /** The type arguments of facade class `c`, if it is generic (its parameters stand for the Go type's, in order). */
    private def classTypeArgs(f: GoFacade): Map[String, ScalaType] =
      if f.typeParams.isEmpty then Map.empty
      else witnesses(f.typeParams, api.typeDecl(f.pkg, f.name).fold(Nil)(d => f.typeParams.zip(d.typeParams.map(_._2))))

    /** The uses of the members of facade `c`. */
    def uses(c: ClassDef): Seq[String] =
      val facade = c.goFacade.get
      val classArgs = classTypeArgs(facade)
      val receiver =
        if facade.isPackage then source(ScalaType.Ref(c.name, Nil))
        else s"(${value(ScalaType.Ref(c.name, facade.typeParams.map(classArgs)))})"
      for
        m <- c.methods
        g <- m.goMember
        if !m.name.isConstructor
      yield
        val constraints =
          if facade.isPackage then api.packages.get(facade.pkg).flatMap(_.funcs.get(g.goName)).fold(Nil)(_.typeParams) else Nil
        val bindings = classArgs ++ witnesses(g.typeParams, constraints)
        val targs = if g.typeParams.isEmpty then "" else g.typeParams.map(n => source(bindings(n))).mkString("[", ", ", "]")
        val params = g.params.map(substitute(_, bindings))
        val name = FacadeGen.ident(m.name.simple)
        // A setter without a getter (one Scala cannot express) is not an assignment.
        if m.name.simple.endsWith("_$eq") then s"$receiver.`${m.name.simple.stripSuffix("_$eq")}_=`(${value(params.head)})"
        else if g.isProperty then s"val _ = $receiver.$name"
        else
          val args = params.map {
            case ScalaType.Ref(GoInterop.SeqClass, List(e)) => s"_root_.scala.Seq.empty[${source(e)}]*"
            case t                                         => value(t)
          }
          s"val _ = $receiver.$name$targs${args.mkString("(", ", ", ")")}"
  end Generator

  /** The program: `object <name>` whose `main` would use every member, but never does. */
  def generate(classes: Seq[ClassDef], api: GoApi, scalaPackage: String, objectName: String): String =
    val facades = classes.filter(c => c.goFacade.isDefined && c.name.startsWith(s"$scalaPackage.")).sortBy(_.name)
    val generator = Generator(api, FacadeIndex(classes))
    val chunks = facades.flatMap(generator.uses).grouped(ChunkSize).toSeq
    val sb = StringBuilder()
    sb ++= "// Code generated by scala-go (FacadeUsage). DO NOT EDIT.\n\n"
    sb ++= s"/** Uses every member of the Go facades of `$scalaPackage` (${chunks.map(_.size).sum} uses), so that\n"
    sb ++= " *  linking and building it checks them all. Running it does nothing.\n */\n"
    sb ++= s"object $objectName:\n"
    sb ++= "  def main(args: Array[String]): Unit =\n"
    sb ++= "    if args.length == -1 then\n"
    for i <- chunks.indices do sb ++= s"      use$i()\n"
    sb ++= "    println(\"ok\")\n"
    for (chunk, i) <- chunks.zipWithIndex do
      sb ++= s"\n  private def use$i(): Unit =\n"
      chunk.foreach(u => sb ++= s"    $u\n")
    sb ++= s"end $objectName\n"
    sb.toString

  def main(args: Array[String]): Unit =
    val options = args.grouped(2).collect { case Array(k, v) => k -> v }.toMap
    if args.length % 2 != 0 || !Seq("--classpath", "--package", "--out").forall(options.contains) then
      System.err.println("usage: scalago-facadeusage --classpath <cp> --package <scala package> --out <file.scala> [--object <name>]")
      sys.exit(2)
    val classpath = options("--classpath").split(java.io.File.pathSeparator).toSeq.filter(_.nonEmpty).map(Path.of(_))
    val classes = IRLoader.load(classpath)
    // The Go API of the facades' packages, for the constraints of generic members.
    val packages = classes.flatMap(_.goFacade).map(_.pkg).filter(_.nonEmpty).distinct.sorted
    val moduleDir = Files.createTempDirectory("scalago-facadeusage-")
    val api =
      try
        Files.writeString(moduleDir.resolve("go.mod"), "module scalago.facadeusage\n\ngo 1.27\n", UTF_8)
        GoApi.load(moduleDir, packages)
      finally Using.resource(Files.walk(moduleDir))(_.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists))
    val out = Path.of(options("--out"))
    Option(out.getParent).foreach(Files.createDirectories(_))
    Files.writeString(out, generate(classes, api, options("--package"), options.getOrElse("--object", "Main")), UTF_8)
end FacadeUsage
