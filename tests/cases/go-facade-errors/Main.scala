import scalago.go.*

// Facades that do not match the Go declarations: linking must fail with these errors.
@GoImport("strings")
object strings:
  def NoSuchFunction(s: String): String = native
  def Repeat(s: String, count: String): String = native
  def Split(s: String, sep: String): String = native
  def Count(s: String): Int = native
  class Builder:
    def Missing(): Int = native
  class Nope

object Main:
  def main(args: Array[String]): Unit =
    println(strings.NoSuchFunction("a"))
    println(strings.Repeat("a", "3"))
    println(strings.Split("a,b", ","))
    println(strings.Count("a"))
    println(new strings.Builder().Missing())
    println(new strings.Nope)
