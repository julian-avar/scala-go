package scalago.plugin

import dotty.tools.dotc.core.Contexts.*
import dotty.tools.dotc.plugins.{PluginPhase, StandardPlugin}

class ScalaGoPlugin extends StandardPlugin:
  val name: String = "scala-go"
  override val description: String = "emit scala-go IR (.goir) for compilation to Go"

  override val optionsHelp: Option[String] = Some(
    """  -P:scala-go:lenient          emit `Unsupported` IR for untranslatable code instead of failing (for libraries)
      |  -P:scala-go:rename:a.b:c.d  emit classes of package a.b (and subpackages) under package c.d
      |  -P:scala-go:static-forwarders  add static forwarders for static objects' methods, as on the JVM (javalib)""".stripMargin
  )

  override def initialize(options: List[String])(using Context): List[PluginPhase] =
    val renames = options.collect { case s"rename:$from:$to" => (from + ".", to + ".") }
    List(GoTypeArgs(), GenGoIR(GoIRCodeGen.Options(lenient = options.contains("lenient"), renames,
      staticForwarders = options.contains("static-forwarders"))))

/** Runs after all tree transformations (the trees are erased, flattened, with constructors
 *  and lambdas lifted) and just before the JVM backend, which still runs as usual.
 */
class GenGoIR(options: GoIRCodeGen.Options) extends PluginPhase:
  override def phaseName: String = GenGoIR.name
  override def description: String = "generate .goir files for scala-go"
  override val runsAfter: Set[String] = Set("repeatableAnnotations")
  override val runsBefore: Set[String] = Set("genBCode")

  override protected def run(using Context): Unit =
    GoIRCodeGen(options).genCompilationUnit(ctx.compilationUnit)

object GenGoIR:
  val name = "genGoIR"
