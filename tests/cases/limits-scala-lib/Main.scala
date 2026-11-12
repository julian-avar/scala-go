// Differences of the Scala library (docs/_docs/spec/language.md, §12 "The standard library"):
// each line prints what scala-go does today; the comment says what the JVM does. When one
// changes, this case fails: move the line to a case that runs on the JVM too (if it now
// matches), and update the docs.
object Main:
  def attempt(what: String)(body: => Any): Unit =
    val result =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}${Option(e.getMessage).fold("")(": " + _)}"
    println(s"$what: $result")

  def main(args: Array[String]): Unit =
    // scala.util.Properties values read from library.properties are empty: a Go binary has no
    // class-path resources. JVM: "3.9.0", "version 3.9.0", true
    attempt("versionNumberString")(scala.util.Properties.versionNumberString)
    attempt("versionString")(scala.util.Properties.versionString)
    attempt("releaseVersion defined")(scala.util.Properties.releaseVersion.isDefined)
