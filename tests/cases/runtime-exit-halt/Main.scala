import java.util.concurrent.CountDownLatch

/** Runtime.halt: exits at once with the status given (exit-status.txt), without running the
 *  shutdown hooks or pending `finally` blocks, while a non-daemon thread still runs. Standard
 *  output written before is not lost.
 */
object Main:
  def main(args: Array[String]): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() => println("the hook ran")))
    val never = new CountDownLatch(1)
    new Thread(() =>
      never.await()
      println("the blocked thread ended")
    ).start()
    try
      println("halting")
      print("without a newline")
      Runtime.getRuntime.halt(7)
    finally println("finally ran")
