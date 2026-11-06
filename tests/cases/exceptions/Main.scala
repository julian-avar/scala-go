class ValidationError(val field: String, msg: String) extends Exception(msg)

object Main {
  def risky(n: Int): Int =
    if (n < 0) throw new IllegalArgumentException("negative: " + n)
    else 100 / n

  def attempt(n: Int): String =
    try "ok " + risky(n)
    catch {
      case e: IllegalArgumentException => "bad argument: " + e.getMessage
      case e: ArithmeticException      => "arithmetic: " + e.getMessage
    }

  def withFinally(n: Int): Int = {
    var log = ""
    val r =
      try {
        log += "try;"
        if (n == 0) return -1
        risky(n)
      } finally {
        log += "finally;"
        println(log)
      }
    r + 1
  }

  def firstNegative(xs: Array[Int]): Int = {
    var i = 0
    while (i < xs.length) {
      try {
        if (xs(i) < 0) return i
      } catch {
        case _: Exception => ()
      }
      i += 1
    }
    -1
  }

  def nested(): String =
    try {
      try throw new ValidationError("name", "must not be empty")
      catch { case e: IllegalStateException => "wrong handler" }
    } catch {
      case e: ValidationError => e.field + ": " + e.getMessage
    }

  def main(args: Array[String]): Unit = {
    println(attempt(5))
    println(attempt(-3))
    println(attempt(0))
    println(withFinally(4))
    println(withFinally(0))
    println(firstNegative(Array(3, 1, -4, 1)))
    println(nested())

    val s: String = null
    try println(s.length)
    catch { case e: NullPointerException => println("caught NPE") }

    val arr = Array(1, 2, 3)
    try println(arr(5))
    catch { case e: ArrayIndexOutOfBoundsException => println(e.getMessage) }

    try throw new RuntimeException("boom")
    catch { case e: Throwable => println(e) }

    val e = new IllegalStateException("state")
    println(s"${e.isInstanceOf[RuntimeException]} ${e.getClass.getName}")

    try {
      try throw new UnsupportedOperationException("inner")
      finally println("cleanup")
    } catch { case e: Exception => println("outer caught " + e.getMessage) }
  }
}
