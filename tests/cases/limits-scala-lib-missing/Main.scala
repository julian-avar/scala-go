// Limitations (docs/_docs/user/limitations.md, "JDK and Scala libraries"): Scala library methods
// that need a JDK API scala-go does not implement fail to link, naming the API. When one links,
// this case fails: move the call to a case that runs it (scala-math, scala-util, scala-io-sys).
object Main:
  def main(args: Array[String]): Unit =
    // Math.nextAfter (documented as missing)
    println(scala.util.Random(1).between(0.5, 1.5))
    // java.text.MessageFormat (java.text is documented as missing)
    println(scala.io.StdIn.readf1("{0}"))
    // Class.cast (documented as missing), used by Future.mapTo
    println(scala.concurrent.Future.successful[Any]("a").mapTo[String])
    // Class.isInstance (documented as missing), used by ClassTag patterns in PartialFunction.elementWise
    val pf: PartialFunction[Int, Int] = { case x if x > 0 => x }
    println(pf.elementWise.unapplySeq(List(1, 2)))
    // Math.ulp and BigInteger.probablePrime: missing, but not in the documentation's lists
    println(math.ulp(1.0))
    println(math.ulp(1.0f))
    println(BigInt.probablePrime(16, scala.util.Random(1)))
