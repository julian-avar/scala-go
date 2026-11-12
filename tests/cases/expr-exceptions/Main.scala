import scala.util.{Failure, Success, Try, Using, boundary}
import scala.util.control.{Breaks, ControlThrowable, NonFatal}

// Exceptions (docs/_docs/spec/language.md, "Exceptions"): try/catch/finally ordering, nesting,
// rethrow, finally after return and break, exceptions replacing outcomes, guards, throws in
// operands, NonFatal, custom exceptions, getMessage/getCause/addSuppressed, the run-time errors Go
// detects. Not here: negative array indices and NonFatal of StackOverflowError (known-bugs-misc),
// NullPointerException messages (limits-runtime).
class AppException(val code: Int, msg: String, cause: Throwable = null) extends Exception(msg, cause)
class ValidationException(field: String) extends AppException(400, s"invalid $field")
case class Problem(what: String) extends RuntimeException(what)
class NoMessage extends RuntimeException
class Resource(name: String, log: StringBuilder, failClose: Boolean = false) extends AutoCloseable:
  log ++= s"open $name;"
  def use(fail: Boolean): Int = if fail then throw IllegalStateException(s"use $name") else name.length
  override def close(): Unit =
    log ++= s"close $name;"
    if failClose then throw IllegalArgumentException(s"close $name")

object Main:
  val log = new StringBuilder
  def step(s: String): Unit = log ++= s + ";"
  def show(what: String)(body: => Any): Unit =
    log.clear()
    val r =
      try String.valueOf(body)
      catch case e: Throwable => s"throws ${e.getClass.getName}(${e.getMessage})"
    println(s"$what: $r [$log]")

  def withFinally(n: Int): Int =
    try
      step("try")
      if n == 0 then return -1
      if n < 0 then throw IllegalArgumentException("negative")
      n * 2
    finally step("finally")

  def finallyOverridesReturn(): Int =
    var r = 0
    try
      r = 1
      return r
    finally
      step(s"finally sees $r")
      r = 99 // does not change the returned value

  def returnInFinally(flag: Boolean): Int =
    try
      step("try")
      10
    finally
      if flag then return 20

  def throwInFinally(flag: Boolean): Int =
    try throw IllegalStateException("from try")
    finally if flag then throw UnsupportedOperationException("from finally")

  def throwInCatch(): Int =
    try throw IllegalStateException("first")
    catch case e: IllegalStateException => throw RuntimeException("second: " + e.getMessage)
    finally step("finally after catch threw")

  def nestedFinally(): Unit =
    try
      try
        try throw Problem("deep")
        finally step("inner finally")
      finally step("middle finally")
    catch case Problem(w) => step(s"caught $w")
    finally step("outer finally")

  def rethrow(): Unit =
    try throw AppException(1, "original")
    catch case e: AppException =>
      step(s"handling ${e.code}")
      throw e

  def wrap(): Unit =
    try Integer.parseInt("x")
    catch case e: NumberFormatException => throw AppException(2, "wrapped", e)

  def loopWithBreaks(): Int =
    val b = new Breaks
    var i = 0
    b.breakable {
      while true do
        try
          i += 1
          if i == 3 then b.break()
        finally step(s"finally $i")
    }
    i

  def loopWithBoundary(): Int =
    scala.util.boundary:
      for i <- 1 to 10 do
        try if i == 4 then scala.util.boundary.break(i * 10)
        finally step(s"f$i")
      -1

  def firstMatch(xs: List[Int]): Int =
    xs.foreach { x =>
      try if x > 2 then return x
      finally step(s"seen $x")
    }
    -1

  // Code that a `throw` makes unreachable
  def returnInFinallyAfterThrow(): Int =
    try throw IllegalStateException("discarded")
    finally return 1

  def throwInFinallyAfterThrow(): Int =
    try throw IllegalStateException("from try")
    finally throw UnsupportedOperationException("from finally")

  def pair(a: String, b: Int): String = a + b
  inline def failing(n: Int): Int =
    if n < 0 then throw IllegalArgumentException("negative")
    n
  def boxedThrow(): Any = (throw Problem("boxed")): Int
  def boxedBoundaryThrow(): Any = boundary[Int] { throw Problem("boundary") }
  def boxedThrowInTry(): Any = try ((throw Problem("in try")): Int) catch case _: IllegalStateException => "caught"
  def throwingIf(flag: Boolean): Int = if flag then throw Problem("then") else throw Problem("else")

  def main(args: Array[String]): Unit =
    show("finally on normal completion")(withFinally(2))
    show("finally on return")(withFinally(0))
    show("finally on exception")(withFinally(-1))
    show("finally does not change returned value")(finallyOverridesReturn())
    show("return in finally (no exception)")(returnInFinally(true))
    show("no return in finally")(returnInFinally(false))
    show("exception in finally replaces")(throwInFinally(true))
    show("finally completes normally")(throwInFinally(false))
    show("exception in catch, then finally")(throwInCatch())
    show("nested finally order")(nestedFinally())
    show("rethrow keeps identity")({ val e = Try(rethrow()).failed.get.asInstanceOf[AppException]; s"${e.code} ${e.getMessage}" })
    show("wrapped cause")({ val e = Try(wrap()).failed.get; s"${e.getMessage} <- ${e.getCause.getClass.getName}: ${e.getCause.getMessage}" })
    show("break out of try")(loopWithBreaks())
    show("boundary break out of try")(loopWithBoundary())
    show("non-local return out of try in a lambda")(firstMatch(List(1, 2, 3, 4)))

    // Catch clauses: order, guards, alternatives, subclass matching, catch-all
    def classify(t: Throwable): String =
      try throw t
      catch
        case e: ValidationException                      => s"validation ${e.code}"
        case e: AppException if e.code >= 500           => s"server ${e.code}"
        case e: AppException                             => s"app ${e.code}"
        case _: IllegalArgumentException | _: IllegalStateException => "illegal"
        case Problem(w) if w.startsWith("x")             => s"x-problem $w"
        case e: RuntimeException                         => s"runtime ${e.getClass.getSimpleName}"
        case e: Exception                                => s"exception ${e.getMessage}"
        case other                                       => s"throwable ${other.getClass.getName}"
    for t <- List(ValidationException("name"), AppException(503, "down"), AppException(404, "missing"), NumberFormatException("n"),
        IllegalStateException("s"), Problem("xyz"), Problem("abc"), java.io.IOException("io"), AssertionError("assert"),
        StackOverflowError(), InterruptedException("i")) do
      println(s"classify ${t.getClass.getSimpleName}: ${classify(t)}")

    // try as an expression; throw as an expression of type Nothing
    val parsed = try "42".toInt catch case _: NumberFormatException => -1
    val failed = try "x".toInt catch case _: NumberFormatException => -1
    println(s"try expression: $parsed $failed")
    def checked(n: Int): Int = if n >= 0 then n else throw IllegalArgumentException(s"n=$n")
    println(s"throw expression: ${Try(checked(1))} ${Try(checked(-1))}")
    show("throw in getOrElse")(Option.empty[String].getOrElse(throw java.util.NoSuchElementException("none")))
    show("throw null")(Try(throw null).failed.get.getClass.getName)
    show("throw a null variable")(Try({ val e: RuntimeException = null; throw e }).failed.get.getClass.getName)

    // A throw ends the code around it: later operands are not evaluated
    show("return in finally after a throw")(returnInFinallyAfterThrow())
    show("throw in finally after a throw")(throwInFinallyAfterThrow())
    show("throw as an argument")(pair({ step("first"); throw Problem("argument") }, { step("second"); 1 }))
    show("call returning Nothing as an argument")(pair(sys.error("nothing"), { step("second"); 1 }))
    show("throwing if as an argument")(pair(String.valueOf(throwingIf(log.isEmpty)), 1))
    show("inline throw in a try, by name")(try failing(-1) catch case _: IllegalArgumentException => 0)
    show("boxed throw")(boxedThrow())
    show("boxed boundary that throws")(boxedBoundaryThrow())
    show("boxed throw in a try")(boxedThrowInTry())
    show("boundary that throws, in a try")(try boundary { (throw Problem("f")): Int } catch case e: Problem => e.getMessage)
    show("throw in a condition")({ while (throw Problem("condition")) do step("body"); 0 })
    show("throw after &&")(log.isEmpty && (throw Problem("rhs")))
    show("throw in a concatenation")("a" + (throw Problem("concat")) + { step("after"); "b" })
    show("val of a throwing if")({ val x: Int = if log.isEmpty then throw Problem("a") else throw Problem("b"); x + 1 })
    show("throwing match as an operand")(1 + (log.length match { case 0 => throw Problem("zero"); case _ => throw Problem("other") }: Int))

    // NonFatal
    val nonFatal = List[Throwable](RuntimeException(), Exception(), AssertionError(), NotImplementedError(),
      InterruptedException(), LinkageError(), new ControlThrowable {}, Problem("p"), Error("e"))
    println("NonFatal: " + nonFatal.map(t => s"${t.getClass.getSimpleName}=${NonFatal(t)}").mkString(" "))
    val recovered = try throw Problem("q") catch case NonFatal(e) => s"recovered ${e.getMessage}"
    println(recovered)

    // Messages, causes, toString, suppressed
    val plain = RuntimeException()
    println(s"no message: ${plain.getMessage} | ${plain.getLocalizedMessage} | $plain")
    println(s"custom no message: ${NoMessage().getMessage} | ${NoMessage()}")
    val cause = IllegalStateException("root")
    val withCause = RuntimeException(cause)
    println(s"cause-only constructor: ${withCause.getMessage} | ${withCause.getCause eq cause} | $withCause")
    val both = RuntimeException("top", cause)
    println(s"message and cause: ${both.getMessage} | ${both.getCause.getMessage} | ${cause.getCause}")
    val later = Exception("later")
    later.initCause(cause)
    println(s"initCause: ${later.getCause eq cause}")
    println(s"self cause: ${Try(plain.initCause(plain)).failed.get.getMessage}")
    val primary = Exception("primary")
    primary.addSuppressed(IllegalStateException("s1"))
    primary.addSuppressed(UnsupportedOperationException("s2"))
    println(s"suppressed: ${primary.getSuppressed.map(_.getMessage).mkString(",")} (${primary.getSuppressed.length})")
    println(s"no suppressed: ${plain.getSuppressed.length}")
    println(s"self-suppression: ${Try(primary.addSuppressed(primary)).failed.get.getMessage}")
    println(s"custom fields: ${ValidationException("email").code} ${ValidationException("email").getMessage} ${Problem("p") == Problem("p")}")
    println(s"exception toString: ${AppException(1, "m")} | ${Problem("p")} | ${java.io.IOException("io")} | ${ArithmeticException()}")
    println(s"is-a: ${AppException(1, "m").isInstanceOf[Exception]} ${Problem("p").isInstanceOf[RuntimeException]} ${(Error(): Throwable).isInstanceOf[Exception]}")

    // Using: resources closed in reverse order; close failures are suppressed
    val ul = new StringBuilder
    val ok = Using.Manager { use =>
      val a = use(Resource("a", ul))
      val b = use(Resource("bb", ul))
      a.use(false) + b.use(false)
    }
    println(s"Using ok: $ok [$ul]")
    ul.clear()
    val bad = Using(Resource("r", ul, failClose = true))(_.use(true))
    println(s"Using failing: ${bad.failed.get.getMessage} suppressed ${bad.failed.get.getSuppressed.map(_.getMessage).toList} [$ul]")
    ul.clear()
    val closeOnly = Using(Resource("c", ul, failClose = true))(_.use(false))
    println(s"Using close fails: $closeOnly [$ul]")

    // Try
    println(s"Try: ${Try(1 / args.length)} ${Try("7".toInt).map(_ + 1)} ${Try(1 / args.length).recover { case _: ArithmeticException => 0 }}")
    println(s"Try toEither: ${Try(throw Problem("e")).toEither} ${Try(5).toOption}")

    // Errors the Go runtime detects become the JVM's exceptions
    val arr = Array(1, 2, 3)
    val nullArr: Array[Int] = null
    val nullStr: String = null
    val zero = args.length
    show("array index past the end")(arr(arr.length + zero))
    show("array index Int.MaxValue")(arr(Int.MaxValue))
    show("string charAt past the end")(Try("abc".charAt(3 + zero)).failed.get.getClass.getName)
    show("division by zero")(1 / zero)
    show("long remainder by zero")(1L % zero)
    show("null array length")(Try(nullArr.length).failed.get.getClass.getName)
    show("null string method")(Try(nullStr.trim).failed.get.getClass.getName)
    show("null array store")(Try(nullArr(0) = 1).failed.get.getClass.getName)
    show("negative array size")(Try(new Array[Int](zero - 1)).failed.get.getClass.getName)
    show("failed cast")(Try(("s": Any).asInstanceOf[Integer].intValue).failed.get.getClass.getName)
    show("caught as RuntimeException")(try arr(10) catch case e: RuntimeException => e.getClass.getSimpleName)
    show("caught as Throwable in finally")({ var r = ""; try { try 1 / zero finally step("cleanup") } catch case t: Throwable => r = t.getMessage; r })
    show("exception in lambda")(Try(List(1, 0).map(10 / _)).failed.get.getMessage)
    show("exception in a constructor argument")(Try(new Resource("x", log, failClose = Integer.parseInt("?") > 0)).failed.get.getMessage)
    show("stack unwinds through frames")({ def deep(n: Int): Int = if n == 0 then throw Problem("bottom") else deep(n - 1) + 1; Try(deep(100)).failed.get.getMessage })
