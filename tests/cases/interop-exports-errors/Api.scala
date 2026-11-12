import scalago.go.*

// Errors and panics of exported Scala code (docs/_docs/spec/interop.md, "Errors and panics"):
// with @throws, exceptions are the trailing error (a GoException gives back the Go error it
// wraps); without, they are Go panics whose value is an error describing the exception.
@GoImport("io")
object io:
  def EOF: GoError = native

object Api:
  @GoExport @throws[Exception] def read(n: Int): String =
    if n < 0 then throw IllegalArgumentException(s"negative: $n")
    if n == 0 then throw GoException(io.EOF)
    "x" * n

  @GoExport @throws[Exception] def validate(s: String): Unit =
    if s.isEmpty then throw IllegalStateException("empty")

  /** A Go func's error, thrown as a GoException, goes back to Go as the same error. */
  @GoExport @throws[Exception] def call(f: () => GoError): String =
    val err = f()
    if err != null then throw GoException(err)
    "called"

  @GoExport def explode(msg: String): Int = throw UnsupportedOperationException(msg)

  @GoExport def explodeWithFinally(msg: String): Unit =
    try throw IllegalStateException(msg)
    finally println("[scala] finally runs")

@GoExport class Parser(radix: Int):
  @GoExport @throws[NumberFormatException] def parse(s: String): Long = java.lang.Long.parseLong(s, radix)
