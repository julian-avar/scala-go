package scalago.ir

/** Binary class name, dot-separated: `java.lang.Object`, `Main$`, `a.b.Outer$Inner`. */
type ClassName = String

object ClassName:
  val Object: ClassName = "java.lang.Object"
  val String: ClassName = "java.lang.String"

/** A method is identified by its name together with its erased signature, so overloads stay distinct. */
final case class MethodName(simple: String, params: List[Type], result: Type):
  def isConstructor: Boolean = simple == MethodName.ConstructorSimpleName
  def descriptor: String = params.map(_.descriptor).mkString(s"$simple(", "", s")${result.descriptor}")
  override def toString: String = descriptor

object MethodName:
  val ConstructorSimpleName = "<init>"
