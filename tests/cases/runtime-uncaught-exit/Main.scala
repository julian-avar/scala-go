/** An uncaught exception in main: the stack trace is printed when the main thread ends, then the
 *  program waits for the other non-daemon threads, runs the shutdown hooks and exits with status 1
 *  (exit-status.txt). A daemon thread does not hold the program up.
 */
object Main:
  @volatile var workerDone = false

  def main(args: Array[String]): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() => println(s"shutdown hook; the worker finished: $workerDone")))
    val worker = new Thread(() =>
      Thread.sleep(200)
      workerDone = true
    )
    worker.start()
    val daemon = new Thread(() =>
      Thread.sleep(60000)
      println("the daemon thread woke up")
    )
    daemon.setDaemon(true)
    daemon.start()
    println("main throws")
    throw new IllegalStateException("main failed")
