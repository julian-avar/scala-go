package scalago.plugin

import scalago.ir

/** Scala 2's specialized tuple classes (`Tuple2$mcII$sp`, ...) and accessors (`_1$mcI$sp`).
 *
 *  When the Scala library on the class path has them, the compiler's tuple specialization
 *  rewrites `(1, 2)` to `new Tuple2$mcII$sp(1, 2)` and `t._1` to `t._1$mcI$sp()`, also when a
 *  library is compiled from TASTy. But the Scala library's IR is compiled from its TASTy, which
 *  has neither: Scala 2's specialization generated them. So the IR uses the generic tuples, with
 *  boxed elements, as Scala 3 code does without specialization.
 */
object SpecializedTuples:
  private val SpecializedClass = raw"scala\.(Tuple[12])\$$mc[ZCIJD]+\$$sp".r
  private val SpecializedAccessor = raw"(_[12])\$$mc([ZCIJD])\$$sp".r

  /** The generic tuple class of a specialized one (`scala.Tuple2` for `scala.Tuple2$mcII$sp`). */
  def generic(cls: ir.ClassName): Option[ir.ClassName] = cls match
    case SpecializedClass(base) => Some(s"scala.$base")
    case _                      => None

  def isTuple(cls: ir.ClassName): Boolean = cls == "scala.Tuple1" || cls == "scala.Tuple2"

  /** The generic constructor of a tuple with `arity` elements. */
  def constructor(arity: Int): ir.MethodName =
    ir.MethodName(ir.MethodName.ConstructorSimpleName, List.fill(arity)(ir.Type.Object), ir.Type.Void)

  /** For a specialized accessor (`_1$mcI$sp`): the generic one (`_1()Object`) and the element's
   *  primitive type.
   */
  def accessor(name: String): Option[(ir.MethodName, ir.Type)] = name match
    case SpecializedAccessor(generic, code) =>
      val prim = code match
        case "Z" => ir.Type.Boolean
        case "C" => ir.Type.Char
        case "I" => ir.Type.Int
        case "J" => ir.Type.Long
        case _   => ir.Type.Double
      Some((ir.MethodName(generic, Nil, ir.Type.Object), prim))
    case _ => None
