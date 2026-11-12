import java.lang.reflect.Array as JArray

import scala.reflect.ClassTag

import rtclasses.*

/** Class objects: names (getName, getTypeName, getSimpleName, toString) of program, JDK,
 *  primitive and array classes; isArray, isPrimitive, getComponentType; isAssignableFrom;
 *  interning; class loaders of JDK classes; assertion status; java.lang.reflect.Array
 *  and ClassTag on primitive arrays.
 */
class Outer:
  class Inner
object Outer:
  class Nested

object Main:
  val interfaces = Set[Class[?]](classOf[Shape], classOf[Runnable], classOf[java.util.Map.Entry[?, ?]])

  /** getTypeName of arrays and toString of interfaces are known bugs (known-bugs-runtime). */
  def describe(c: Class[?]): Unit =
    val component = Option(c.getComponentType).fold("-")(_.getName)
    val typeName = if c.isArray then "" else s" | ${c.getTypeName}"
    val string = if interfaces.contains(c) then "" else s" | $c"
    println(s"${c.getName}$typeName | ${c.getSimpleName}$string | array=${c.isArray} primitive=${c.isPrimitive} component=$component")

  def main(args: Array[String]): Unit =
    println("-- names")
    val classes: Seq[Class[?]] = Seq(
      classOf[Int], classOf[Long], classOf[Double], classOf[Boolean], classOf[Char], classOf[Byte],
      classOf[Short], classOf[Float], classOf[Unit],
      java.lang.Integer.TYPE, classOf[java.lang.Integer], classOf[String], classOf[Object],
      classOf[java.util.ArrayList[?]], classOf[java.util.Map.Entry[?, ?]], classOf[Thread.State],
      classOf[Array[Int]], classOf[Array[Array[Double]]], classOf[Array[Byte]], classOf[Array[Char]],
      classOf[Array[String]], classOf[Array[Array[String]]], classOf[Array[Object]],
      classOf[Square], classOf[Shape], classOf[Point], classOf[Registry.Entry], Registry.getClass,
      classOf[Outer], classOf[Outer#Inner], classOf[Outer.Nested], Outer.getClass, Main.getClass,
      classOf[Main.type], classOf[Option[?]], classOf[Some[?]], None.getClass, classOf[List[?]], Nil.getClass,
      classOf[RuntimeException], classOf[Runnable])
    classes.foreach(describe)

    println("-- getClass")
    val values: Seq[Any] = Seq(1, 1L, 1.5, 'c', true, "s", new Square, Point(1, 2), new Outer, new Outer.Nested,
      Array(1, 2), Array(1.5), Some(1), Nil, List(1), new java.util.ArrayList[Int], BigInt(1),
      new IllegalStateException, Thread.currentThread(), (), new Object)
    values.foreach(v => println(v.getClass.getName))

    println("-- identity")
    println(s"${classOf[Square] eq new Square().getClass} ${classOf[String] == "a".getClass} ${classOf[Int] eq Integer.TYPE}")
    println(s"${(1: Any).getClass eq classOf[java.lang.Integer]} ${Array(1).getClass eq classOf[Array[Int]]} ${classOf[Int] ne classOf[java.lang.Integer]}")
    println(s"${classOf[Square].hashCode == new Square().getClass.hashCode} ${Set(classOf[Square], new Square().getClass, classOf[Shape]).size}")

    println("-- isAssignableFrom")
    def assignable(to: Class[?], from: Class[?]): Unit =
      println(s"${to.getSimpleName} <- ${from.getSimpleName}: ${to.isAssignableFrom(from)}")
    assignable(classOf[Shape], classOf[Square])
    assignable(classOf[Square], classOf[Shape])
    assignable(classOf[Object], classOf[Square])
    assignable(classOf[Square], classOf[Square])
    assignable(classOf[Product], classOf[Point])
    assignable(classOf[java.io.Serializable], classOf[Point])
    assignable(classOf[Int], classOf[Int])
    assignable(classOf[Long], classOf[Int])
    assignable(classOf[Object], classOf[Int])
    assignable(classOf[Object], classOf[java.lang.Integer])
    assignable(classOf[Number], classOf[java.lang.Integer])
    assignable(classOf[Comparable[?]], classOf[String])
    assignable(classOf[CharSequence], classOf[String])
    assignable(classOf[Object], classOf[Array[Int]])
    assignable(classOf[Array[Int]], classOf[Array[Int]])
    assignable(classOf[Array[Long]], classOf[Array[Int]])
    assignable(classOf[Exception], classOf[IllegalStateException])
    assignable(classOf[RuntimeException], classOf[Exception])

    println("-- loaders, assertions")
    println(s"${classOf[String].getClassLoader} ${classOf[Int].getClassLoader} ${classOf[java.util.ArrayList[?]].getClassLoader}")
    println(s"desiredAssertionStatus: ${classOf[Square].desiredAssertionStatus}")

    println("-- java.lang.reflect.Array")
    val ints = JArray.newInstance(classOf[Int], 3)
    JArray.set(ints, 1, 42)
    JArray.set(ints, 2, 7)
    println(s"${ints.getClass.getName} ${JArray.getLength(ints)} ${JArray.get(ints, 1)} ${JArray.get(ints, 2)} ${ints.asInstanceOf[Array[Int]].mkString(",")}")
    val doubles = JArray.newInstance(classOf[Double], 2).asInstanceOf[Array[Double]]
    doubles(0) = 2.5
    println(s"${doubles.getClass.getName} ${JArray.get(doubles, 0)} ${JArray.getLength(Array("a", "b"))} ${JArray.get(Array("a", "b"), 1)}")
    val matrix = JArray.newInstance(classOf[Int], 2, 3)
    println(s"${JArray.getLength(matrix)} ${JArray.getLength(JArray.get(matrix, 0))} ${JArray.get(matrix, 1).getClass.getName}")
    try JArray.get(ints, 3)
    catch case e: ArrayIndexOutOfBoundsException => println("get out of bounds: ArrayIndexOutOfBoundsException")
    try JArray.getLength("not an array")
    catch case e: IllegalArgumentException => println("getLength of a non-array: IllegalArgumentException")

    println("-- ClassTag")
    def tagged[T](using tag: ClassTag[T]): String =
      val array = tag.newArray(2)
      s"${tag.runtimeClass.getName} ${array.getClass.getName} ${array.length}"
    println(tagged[Int])
    println(tagged[Long])
    println(tagged[Char])
    println(s"${summon[ClassTag[Square]].runtimeClass.getName} ${summon[ClassTag[Int]] == ClassTag.Int} ${ClassTag(classOf[Int]) == ClassTag.Int}")
    println(Array.ofDim[Int](2, 2).apply(0).getClass.getName)
    println(Array.fill(2)(1.5f).getClass.getName)
