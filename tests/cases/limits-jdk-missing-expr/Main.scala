// JDK APIs on numbers, characters and strings that scala-go does not implement, found by the
// expr-* and data-* cases (not yet listed in docs/_docs/user/limitations.md). Each use is a link
// error naming the API. When one is implemented, its error disappears and this case fails: move
// the use to the expr-* or data-* case that covers its area, and update the docs.
object Main:
  def main(args: Array[String]): Unit =
    // java.lang.Math: the spec lists clamp among the functions with the JDK's results, but only
    // the integer overloads link
    println(Math.clamp(-1.0, 0.0, 1.0))
    println(Math.clamp(2.0f, 0.0f, 1.0f))
    println(Math.floorMod(-7L, 3))
    println(Math.ulp(1.0))
    println(Math.nextUp(1.0f))
    // Boxes
    println(java.lang.Short.reverseBytes(0x0102.toShort))
    println(java.lang.Byte.parseByte("-128"))
    println(java.lang.Short.parseShort("32767"))
    // Code points
    println(Character.toString(0x1F600))
    println(Character.isSupplementaryCodePoint(0x1F600))
    println(Character.isBmpCodePoint(0xFFFF))
    println(Character.highSurrogate(0x1F600))
    println(Character.lowSurrogate(0x1F600))
    println("a😀".codePointBefore(3))
    println("a😀b".offsetByCodePoints(0, 2))
