// Documented differences in data and text not pinned elsewhere (docs/_docs/user/differences.md,
// "Data and classes" and "Text"; docs/_docs/spec/data.md, "Arrays"; language.md, "Type tests and
// casts" and "Exceptions"): each line prints what scala-go does today; the comment says what the
// JVM does. When one changes, this case fails: move the line to a case that runs on the JVM too
// (if it now matches), and update the docs. Pinned elsewhere: the class of Array[String] and of
// copies (limits-runtime), identity of boxes and lambda toString (limits-runtime), String.format
// (limits-jdk-text).
case class Point(x: Int)
class Animal
class Dog extends Animal
class Cat extends Animal

object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}"
    println(s"$what: $result")
  def any(x: Any): Any = x

  def main(args: Array[String]): Unit =
    // Arrays of references do not record their element type.
    // JVM: [[I, [LPoint;, [[Ljava.lang.String;, [Lscala.runtime.BoxedUnit;
    attempt("Array(Array(1)) class")(Array(Array(1)).getClass.getName)
    attempt("Array(Point(1)) class")(Array(Point(1)).getClass.getName)
    attempt("Array.ofDim[String](1, 1) class")(Array.ofDim[String](1, 1).getClass.getName)
    attempt("Array[Unit] class")(Array((), ()).getClass.getName)
    // JVM: true, [Ljava.lang.String;
    attempt("Array(\"a\").getClass == classOf[Array[String]]")(Array("a").getClass == classOf[Array[String]])
    attempt("toString prefix")(Array("a").toString.takeWhile(_ != '@'))
    // JVM: false, false, false, false
    attempt("Array(\"a\") isInstanceOf Array[Point]")(any(Array("a")).isInstanceOf[Array[Point]])
    attempt("Array(Dog()) isInstanceOf Array[Cat]")(any(Array(Dog())).isInstanceOf[Array[Cat]])
    attempt("Array(Array(1)) isInstanceOf Array[String]")(any(Array(Array(1))).isInstanceOf[Array[String]])
    attempt("Array[AnyRef](()) isInstanceOf Array[Unit]")(any(Array[AnyRef](scala.runtime.BoxedUnit.UNIT)).isInstanceOf[Array[Unit]])
    // JVM: throws java.lang.ClassCastException
    attempt("Array(\"a\") asInstanceOf Array[Point]")(any(Array("a")).asInstanceOf[Array[Point]].length)
    attempt("Array[Animal] of Dogs asInstanceOf Array[Dog]")(any(Array[Animal](Dog())).asInstanceOf[Array[Dog]].length)
    // JVM: throws java.lang.ArrayStoreException
    attempt("store Integer into String[] seen as Object[]")({ val objs: Array[AnyRef] = Array("a").asInstanceOf[Array[AnyRef]]; objs(0) = Integer.valueOf(1); objs(0) })
    attempt("store Cat into Dog[] seen as Animal[]")({ val as: Array[Animal] = Array(Dog()).asInstanceOf[Array[Animal]]; as(0) = Cat(); as(0).getClass.getName })
    // JVM: [Ljava.lang.String;, [Ljava.lang.String;
    attempt("reflect.Array.newInstance class")(java.lang.reflect.Array.newInstance(classOf[String], 1).getClass.getName)
    attempt("Arrays.copyOf(Array(\"a\"), 1, classOf[Array[String]]) class")(java.util.Arrays.copyOf(Array[AnyRef]("a"), 1, classOf[Array[String]]).getClass.getName)

    // Boxes have no identity. JVM: true, false
    val b1: AnyRef = Integer.valueOf(100000)
    val b2: AnyRef = Integer.valueOf(100000)
    attempt("identityHashCode of a box != 0")(System.identityHashCode(b1) != 0)
    attempt("equal boxes share a monitor")(b1.synchronized(Thread.holdsLock(b2)))

    // ClassCastException messages are Go's. JVM: true
    attempt("cast message names both classes")(try { any(Cat()).asInstanceOf[Dog]; "no" } catch case e: ClassCastException => e.getMessage.contains("Cat cannot be cast to class Dog"))

    // String index errors have older JDKs' messages. JVM: "begin 2, end 1, length 3" is
    // "Range [2, 1) out of bounds for length 3"; parseInt adds " under radix 16"
    attempt("substring(2, 1) message")(try "abc".substring(2, 1) catch case e: StringIndexOutOfBoundsException => e.getMessage)
    attempt("parseInt(\"zz\", 16) message")(try Integer.parseInt("zz", 16) catch case e: NumberFormatException => e.getMessage)
