package scalago.linker

import scalago.ir.*

/** JVM classes whose instances are represented by Go values rather than generated structs
 *  ("hijacked", as Scala.js calls them): strings, boxed primitives, BoxedUnit, Class, BigInteger
 *  and BigDecimal.
 *  Their methods are implemented natively (see [[Natives]]).
 */
object Hijacked:
  val String = ClassName.String
  val ClassClass = "java.lang.Class"
  val BoxedUnit = "scala.runtime.BoxedUnit"
  val BigInteger = "java.math.BigInteger"
  val BigDecimal = "java.math.BigDecimal"

  /** Box class for each primitive type. */
  val boxClasses: Map[Type, ClassName] = Map(
    Type.Boolean -> "java.lang.Boolean",
    Type.Char    -> "java.lang.Character",
    Type.Byte    -> "java.lang.Byte",
    Type.Short   -> "java.lang.Short",
    Type.Int     -> "java.lang.Integer",
    Type.Long    -> "java.lang.Long",
    Type.Float   -> "java.lang.Float",
    Type.Double  -> "java.lang.Double"
  )

  val primitiveOfBox: Map[ClassName, Type] = boxClasses.map(_.swap)

  private val primitiveGoTypes: Map[Type, String] = Map(
    Type.Boolean -> "bool",
    Type.Char    -> "uint16",
    Type.Byte    -> "int8",
    Type.Short   -> "int16",
    Type.Int     -> "int32",
    Type.Long    -> "int64",
    Type.Float   -> "float32",
    Type.Double  -> "float64"
  )

  def primitiveGoType(t: Type): String = primitiveGoTypes(t)

  /** The Go type of instances, as used in type switches and type tests. */
  val instanceGoType: Map[ClassName, String] =
    boxClasses.map((prim, box) => box -> primitiveGoTypes(prim)) ++ Map(
      String     -> "*rt.String",
      ClassClass -> "*rt.Class",
      BoxedUnit  -> "*rt.BoxedUnit",
      BigInteger -> "*rt.BigInteger",
      BigDecimal -> "*rt.BigDecimal"
    )

  /** Go type of references whose static type is a hijacked class with a single Go representation
   *  (other hijacked classes, boxes included, are referenced as `any`).
   */
  val staticGoType: Map[ClassName, String] = Map(
    String     -> "*rt.String",
    ClassClass -> "*rt.Class",
    BigInteger -> "*rt.BigInteger",
    BigDecimal -> "*rt.BigDecimal"
  )

  def isHijacked(cls: ClassName): Boolean = instanceGoType.contains(cls)

  private val serializable = "java.io.Serializable"
  private val comparable = "java.lang.Comparable"
  private val constable = "java.lang.constant.Constable"
  private val constantDesc = "java.lang.constant.ConstantDesc"
  private val number = "java.lang.Number"

  /** Supertypes of each hijacked class (besides java.lang.Object). */
  val ancestors: Map[ClassName, Set[ClassName]] =
    val numeric = Set(number, comparable, serializable, constable, constantDesc)
    boxClasses.values.map { box =>
      box -> (if box == "java.lang.Boolean" || box == "java.lang.Character" then Set(comparable, serializable, constable)
              else numeric)
    }.toMap ++ Map(
      String     -> Set("java.lang.CharSequence", comparable, serializable, constable, constantDesc),
      ClassClass -> Set(serializable, "java.lang.reflect.Type", "java.lang.reflect.GenericDeclaration"),
      BoxedUnit  -> Set(serializable),
      BigInteger -> Set(number, comparable, serializable),
      BigDecimal -> Set(number, comparable, serializable)
    )

  /** The interfaces arrays implement (besides java.lang.Object). */
  val arrayAncestors: Set[ClassName] = Set("java.lang.Cloneable", serializable)

  /** Classes some hijacked value (or array) is an instance of. References of these types are Go
   *  `any`.
   */
  val hijackedAncestors: Set[ClassName] = ancestors.values.flatten.toSet ++ arrayAncestors + ClassName.Object

  /** The hijacked classes that are subtypes of `cls`. */
  def subclassesOf(cls: ClassName): List[ClassName] =
    instanceGoType.keys.toList.sorted.filter(h => h == cls || cls == ClassName.Object || ancestors(h).contains(cls))
