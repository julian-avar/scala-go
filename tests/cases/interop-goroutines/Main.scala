import scala.collection.mutable

import scalago.go.*

// Scala code called from goroutines that Go code started, and Go channels and mutexes shared
// between Scala threads and goroutines (docs/_docs/spec/interop.md, "Goroutines and threads").
@GoImport("example.com/spawn")
object spawn:
  def Parallel(n: Int, f: Int => Unit): Unit = native
  def OnGoroutine(f: () => String): (String, String) = native
  def Fire(f: () => Unit): Unit = native
  def Produce(c: GoChan[Long], n: Int): Unit = native
  def Sum(c: GoChan[Long]): Long = native
  def Locked(mu: scalago.gostd.sync.Mutex, n: Int, f: Int => Unit): Unit = native
  def Sleep(ms: Int): Unit = native

object Main:
  def main(args: Array[String]): Unit =
    val main = Thread.currentThread

    // Callbacks on 100 goroutines: each is a thread for Scala code; monitors synchronize them.
    val threads = mutable.Set.empty[Thread]
    var sum = 0
    spawn.Parallel(100, i => threads.synchronized { threads += Thread.currentThread; sum += i })
    println(s"parallel: sum $sum, ${threads.size} threads, main among them: ${threads.contains(main)}")

    // A goroutine keeps its Thread object and its ThreadLocal values while it runs.
    val local = ThreadLocal.withInitial[String](() => "initial")
    local.set("main's")
    var seen = List.empty[Thread]
    val (first, second) = spawn.OnGoroutine { () =>
      seen = Thread.currentThread :: seen
      val before = local.get
      local.set("goroutine's")
      before
    }
    println(s"thread local: $first then $second; main still has ${local.get}")
    println(s"same thread twice: ${seen.size == 2 && (seen.head eq seen(1))}, not main: ${!(seen.head eq main)}")

    // Go channels between a Go goroutine and Scala, and between a Scala thread and Go.
    val fromGo = GoChan.make[Long]
    spawn.Produce(fromGo, 5)
    println(s"from a goroutine: ${fromGo.iterator.toList}")
    val toGo = GoChan.make[Long](1)
    val sender = Thread(() =>
      for i <- 1L to 10L do toGo.send(i)
      toGo.close()
    )
    sender.start()
    println(s"to Go: ${spawn.Sum(toGo)}")
    sender.join()

    // A Go mutex (scalago-gostd's facade) orders Scala code on goroutines.
    val mu = new scalago.gostd.sync.Mutex
    var unsynchronized = 0
    spawn.Locked(mu, 50, i => unsynchronized += i)
    mu.Lock()
    println(s"Go mutex: $unsynchronized")
    mu.Unlock()

    // Scala code that Go started with a go statement does not keep the program running.
    spawn.Fire { () =>
      spawn.Sleep(10000)
      println("not printed: the program ended")
    }
    println("main ends")
