import scala.util.boundary

// Known bug: code the linker accepts but Go rejects. A `throw` typed as a primitive (`(throw e):
// Int`, or a boundary whose body only throws) that is boxed to `Any` becomes `any()` in Go: "missing
// argument in conversion to any". Found with `println(try boundary { ... } catch ...)`. The JVM
// prints f, g, h, i, j. When fixed, this case fails: move the checks to a case that runs them.
object Main:
  def f(): Unit = println(try boundary { (throw RuntimeException("f")): Int } catch case e: RuntimeException => e.getMessage)
  def g(): Any = boundary { (throw RuntimeException("g")): Int }
  def h(): Any = (throw RuntimeException("h")): Int
  def i(): Any = try ((throw RuntimeException("i")): Int) catch case e: IllegalStateException => "i"
  def j(): Any = boundary[Int] { throw RuntimeException("j") }

  def main(args: Array[String]): Unit =
    f()
    for t <- List(() => g(), () => h(), () => i(), () => j()) do
      println(try t() catch case e: RuntimeException => e.getMessage)
