/** Deep recursion: a thread given a 1 GB stack (the JVM honours `stackSize`; on Go every
 *  goroutine stack grows up to 1 GB) recurses a million calls deep, unwinds an exception through
 *  them, and runs `finally` blocks on the way.
 */
class Deep(message: String) extends RuntimeException(message, null, false, false)

object Main:
  def sum(n: Int): Long = if n == 0 then 0L else n + sum(n - 1)

  def throwAt(n: Int): Int = if n == 0 then throw new Deep("bottom") else throwAt(n - 1) + 1

  var finallies = 0
  def unwind(n: Int): Int =
    try if n == 0 then throw new Deep("bottom") else unwind(n - 1) + 1
    finally finallies += 1

  def inBigStack(name: String)(body: => Unit): Unit =
    val t = new Thread(null, () => body, name, 1L << 30)
    t.start()
    t.join()

  def main(args: Array[String]): Unit =
    println(s"main thread, 10000 deep: ${sum(10000)}")
    inBigStack("deep") {
      println(s"1000000 deep: ${sum(1000000)}")
      val caught =
        try s"returned ${throwAt(1000000)}"
        catch case e: Deep => s"caught ${e.getMessage}"
      println(s"exception through 1000000 frames: $caught")
      val unwound =
        try s"returned ${unwind(100000)}"
        catch case e: Deep => s"caught ${e.getMessage}"
      println(s"exception through 100000 finally blocks: $unwound, $finallies finally blocks ran")
      println(s"again, after the stack shrank: ${sum(1000000)}")
    }
    val built = Thread.ofPlatform().stackSize(1L << 30).start(() => println(s"builder's stackSize: ${sum(1000000)}"))
    built.join()
