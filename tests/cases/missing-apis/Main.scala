// Linked with --missing-apis throw: uses of APIs scala-go does not implement are warnings at link
// time and throw the JVM's linkage errors when they run, as on a JVM without the class.
object Main:
  def neverCalled(): String = java.net.InetAddress.getLocalHost.getHostName

  def main(args: Array[String]): Unit =
    if args.length > 100 then println(neverCalled())
    println("linked and running")

    def attempt(what: String)(body: => Any): Unit =
      try println(s"$what: ${body}")
      catch case e: LinkageError => println(s"$what: ${e.getClass.getName}, ${e.getMessage.startsWith("scala-go: ")}")

    attempt("static method")(java.lang.management.ManagementFactory.getRuntimeMXBean)
    attempt("class")(new java.net.ServerSocket(0))
    // Linkage errors are fatal for NonFatal, as on the JVM.
    println(scala.util.control.NonFatal(new NoSuchMethodError("x")))
    println("done")
