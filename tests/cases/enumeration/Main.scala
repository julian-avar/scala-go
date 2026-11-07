/** A Scala 3 enum that is a Java enum: its cases are static fields of the companion object. */
enum Color(val rgb: Int) extends java.lang.Enum[Color] {
  case Red extends Color(0xff0000)
  case Green extends Color(0x00ff00)
  def hex: String = Integer.toHexString(rgb)
}

enum Simple {
  case A, B
}

object WeekDay extends Enumeration {
  type WeekDay = Value
  val Mon, Tue, Wed, Thu, Fri, Sat, Sun = Value
  def isWorkingDay(d: WeekDay): Boolean = !(d == Sat || d == Sun)
}

object Mixed extends Enumeration {
  val First = Value
  val Named = Value("custom name")
  val Jump = Value(10)
  val Both = Value(20, "twenty")
  val Last: Value = Value
  private val Hidden = Value // no public getter: the JVM finds no name either
  val all = List(Value, Value) // not held by a val of a Value type: unnamed too
  def hidden: Value = Hidden
}

object Planet extends Enumeration {
  protected case class PlanetVal(mass: Double, radius: Double) extends super.Val {
    def surfaceGravity: Double = Planet.G * mass / (radius * radius)
  }
  import scala.language.implicitConversions
  implicit def valueToPlanetVal(x: Value): PlanetVal = x.asInstanceOf[PlanetVal]

  val G: Double = 6.67300e-11
  val Mercury = PlanetVal(3.303e+23, 2.4397e6)
  val Venus = PlanetVal(4.869e+24, 6.0518e6)
  val Earth = PlanetVal(5.976e+24, 6.37814e6)
}

object Codes extends Enumeration(100) {
  val A, B = Value
  val C = Value(50)
  val D = Value
}

object FromNames extends Enumeration {
  nextName = Iterator("alpha", "beta")
  val X, Y, Z = Value
}

abstract class BaseEnum extends Enumeration {
  val Inherited = Value
}

object Extended extends BaseEnum {
  val Own = Value
}

class Colors extends Enumeration {
  val Red, Green, Blue = Value
}

object Outer {
  object Inner extends Enumeration {
    val Only = Value
  }
}

object Main {
  def attempt(label: String)(f: => Any): Unit =
    val result =
      try String.valueOf(f)
      catch case e: RuntimeException => e.getClass.getName + ": " + e.getMessage
    println(s"$label: $result")

  def main(args: Array[String]): Unit = {
    import WeekDay.*
    println(WeekDay)
    println(WeekDay.values)
    println(WeekDay.values.toList)
    println(WeekDay.values.map(_.id))
    println(WeekDay.values.filter(isWorkingDay))
    println(s"${Mon.id} $Mon ${Sun.id} $Sun ${WeekDay.maxId} ${WeekDay(3)} ${WeekDay.withName("Fri")}")
    attempt("withName")(WeekDay.withName("Funday"))
    attempt("apply")(WeekDay(42))
    println(s"${Mon < Tue} ${Sun.compare(Mon)} ${List(Sun, Mon, Wed).sorted} ${List(Sun, Mon, Wed).max}")
    println(s"${Mon == WeekDay.withName("Mon")} ${Mon.hashCode} ${Mon.equals(Codes.A)}")
    val weekend = Sat + Sun
    println(s"$weekend ${weekend.contains(Sat)} ${weekend.contains(Mon)} ${weekend.size} ${weekend.toBitMask.toList}")
    println(WeekDay.ValueSet.fromBitMask(Array(5L)))
    println(WeekDay.ValueSet(Wed, Mon) + Fri - Mon)
    println(WeekDay.ValueSet.empty.isEmpty)
    println(WeekDay.values.range(Tue, Fri))
    println(WeekDay.values.iteratorFrom(Fri).toList)
    println(WeekDay.values.toSeq.reverse)
    val workdays = WeekDay.values.filter(isWorkingDay).map(d => d.toString.length)
    println(workdays)
    println(Map(Mon -> 1, Tue -> 2)(Tue))
    val described = (Mon: WeekDay) match
      case Sat | Sun => "weekend"
      case _         => "weekday"
    println(described)

    println(Mixed.values.toList)
    println(s"${Mixed.First.id} ${Mixed.Named.id} ${Mixed.Jump.id} ${Mixed.Both.id} ${Mixed.Last.id} ${Mixed.maxId}")
    println(Mixed.hidden)
    println(Mixed.all)
    println(Mixed.withName("custom name").id)
    attempt("withName Hidden")(Mixed.withName("Hidden"))

    println(Planet.values.toList)
    println(Planet.values.filter(_.radius > 6.0e6))
    println(f"${Planet.Earth.surfaceGravity}%.2f ${Planet.withName("Venus").mass}")

    println(Codes.values.toList.map(v => s"$v=${v.id}"))
    println(s"${Codes.maxId} ${Codes(101)}")
    println(FromNames.values.toList)
    println(Extended.values.toList)
    println(Extended)

    val colors = new Colors
    println(colors.values.toList)
    println(colors.withName("Green").id)
    println(s"${Outer.Inner.values.toList} ${Outer.Inner}")

    println("-- BigDecimal.RoundingMode")
    val modes = BigDecimal.RoundingMode
    println(modes.values.toList)
    println(modes.values.toList.map(_.id))
    println(modes.withName("HALF_EVEN"))
    println(modes.HALF_UP)
    for m <- modes.values.toList.filter(_ != modes.UNNECESSARY) do
      println(s"$m: ${BigDecimal("2.345").setScale(2, m)} ${BigDecimal("-2.345").setScale(2, m)}")
    println(BigDecimal("2.5").setScale(0, BigDecimal.RoundingMode.HALF_EVEN))
    attempt("UNNECESSARY")(BigDecimal("2.5").setScale(0, BigDecimal.RoundingMode.UNNECESSARY))
    println(BigDecimal("1.005").setScale(2, BigDecimal.RoundingMode.HALF_UP))
    println(BigDecimal(1) to BigDecimal(2) by BigDecimal("0.25"))
    println((BigDecimal(1) until BigDecimal(2) by BigDecimal("0.5")).toList)
    println((BigDecimal("0.1") to BigDecimal("0.5") by BigDecimal("0.1")).sum)

    println("-- Scala 3 enums")
    println(Color.Red) // the first use of Color: reads the companion's static field
    println(Color.values.toList)
    println(Color.valueOf("Green").ordinal)
    println(s"${Color.Green.name()} ${Color.Green.hex} ${Color.Red.compareTo(Color.Green)}")
    println(s"${Simple.values.toList} ${Simple.B.ordinal} ${Simple.fromOrdinal(0)}")
  }
}
