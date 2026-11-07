import java.util.concurrent.*
import java.util.concurrent.atomic.*
import java.util.function.{BiFunction, LongBinaryOperator}

import scala.jdk.CollectionConverters.*

/** java.util.concurrent collections and atomics, used from many threads at once. Results are
 *  printed after the threads are joined.
 */
object Main:
  def runAll(n: Int)(body: Int => Unit): Unit =
    val threads = (0 until n).map(i => new Thread(() => body(i)))
    threads.foreach(_.start())
    threads.foreach(_.join())

  def main(args: Array[String]): Unit =
    // ConcurrentHashMap: iteration order (as the JDK's), atomic updates from many threads.
    val chm = new ConcurrentHashMap[String, Integer]()
    for w <- "the quick brown fox jumps over the lazy dog and the cat".split(" ") do chm.merge(w, 1, (a, b) => a + b)
    println(chm)
    println(s"keys: ${chm.keySet}")
    println(s"values sum: ${chm.values.asScala.map(_.intValue).sum}")
    println(s"get/containsKey: ${chm.get("the")} ${chm.containsKey("fox")} ${chm.containsValue(3)}")
    println(s"putIfAbsent: ${chm.putIfAbsent("the", 9)} ${chm.putIfAbsent("emu", 1)}")
    println(s"compute: ${chm.compute("emu", (k, v) => if v == null then 0 else v + 10)}")
    println(s"computeIfAbsent: ${chm.computeIfAbsent("gnu", k => k.length)} ${chm.computeIfPresent("gnu", (k, v) => null)}")
    println(s"replace: ${chm.replace("fox", 1, 5)} ${chm.replace("fox", 5)} ${chm.remove("fox", 6)} ${chm.remove("fox", 5)}")
    println(s"getOrDefault: ${chm.getOrDefault("fox", -1)}")
    println(s"size: ${chm.size} ${chm.mappingCount}")

    val counts = new ConcurrentHashMap[Integer, java.lang.Long]()
    runAll(16)(t => for i <- 0 until 10000 do counts.merge(i % 100, 1L, (a, b) => a + b))
    println(s"merge from 16 threads: ${counts.size} ${counts.values.asScala.map(_.longValue).sum} ${counts.get(42)}")
    val big = new ConcurrentHashMap[Integer, Integer]()
    runAll(8)(t => for i <- t * 5000 until (t + 1) * 5000 do big.put(i, i))
    println(s"concurrent puts: ${big.size} ${(0 until 40000).forall(i => big.get(i) == i)}")
    runAll(8)(t => for i <- t * 5000 until (t + 1) * 5000 by 2 do big.remove(i))
    println(s"concurrent removes: ${big.size}")
    var iterated = 0L
    big.forEach((k, v) => iterated += v.intValue)
    println(s"forEach: $iterated")
    println(s"reduce: ${big.reduceValuesToLong(1, v => v.longValue, 0L, (a, b) => a + b)}")
    println(s"search: ${big.search[Integer](1, (k, v) => if k == 39999 then v else null)}")
    val set = ConcurrentHashMap.newKeySet[String]()
    runAll(4)(t => for i <- 0 until 100 do set.add(s"k${i % 50}"))
    println(s"newKeySet: ${set.size}")
    val sized = new ConcurrentHashMap[Integer, String](64)
    for i <- 30 to 0 by -3 do sized.put(i * 7, s"v$i")
    println(sized.keySet)

    // Atomics and adders.
    val adder = new LongAdder
    val acc = new LongAccumulator((a, b) => math.max(a, b), Long.MinValue)
    val dadder = new DoubleAdder
    val ints = new AtomicIntegerArray(10)
    val longs = new AtomicLongArray(4)
    val refs = new AtomicReferenceArray[String](3)
    runAll(8) { t =>
      for i <- 0 until 10000 do
        adder.increment()
        acc.accumulate(t * 10000 + i)
        dadder.add(0.5)
        ints.incrementAndGet(i % 10)
        longs.addAndGet(i % 4, 2)
      refs.compareAndSet(t % 3, null, s"t$t")
    }
    println(s"LongAdder: ${adder.sum} ${adder}")
    println(s"LongAccumulator: ${acc.get}")
    println(s"DoubleAdder: ${dadder.sum}")
    println(s"AtomicIntegerArray: $ints")
    println(s"AtomicLongArray: $longs")
    println(s"AtomicReferenceArray: ${(0 until 3).map(i => refs.get(i) != null)}")
    val ai = new AtomicInteger(5)
    println(s"AtomicInteger: ${ai.getAndUpdate(_ * 2)} ${ai.updateAndGet(_ + 1)} ${ai.accumulateAndGet(3, _ * _)} ${ai.compareAndExchange(33, 1)} $ai")
    val al = new AtomicLong(1L << 40)
    println(s"AtomicLong: ${al.getAndAdd(1)} ${al.incrementAndGet} ${al.getAndSet(7)} ${al.get}")
    val ar = new AtomicReference[List[Int]](Nil)
    runAll(4)(t => for i <- 0 until 250 do ar.updateAndGet(i :: _))
    println(s"AtomicReference: ${ar.get.size}")
    val stamped = new AtomicStampedReference[String]("a", 0)
    println(s"AtomicStampedReference: ${stamped.compareAndSet("a", "b", 0, 1)} ${stamped.compareAndSet("a", "c", 1, 2)} ${stamped.getReference} ${stamped.getStamp}")
    val marked = new AtomicMarkableReference[String]("x", false)
    println(s"AtomicMarkableReference: ${marked.attemptMark("x", true)} ${marked.isMarked}")

    // Queues: producers and consumers.
    val lbq = new LinkedBlockingQueue[Integer](100)
    val consumed = new AtomicLong
    val consumers = (1 to 3).map(_ => new Thread(() =>
      var v = lbq.take()
      while v >= 0 do
        consumed.addAndGet(v.longValue)
        v = lbq.take()
    ))
    consumers.foreach(_.start())
    runAll(4)(t => for i <- 1 to 1000 do lbq.put(i))
    for _ <- 1 to 3 do lbq.put(-1)
    consumers.foreach(_.join())
    println(s"LinkedBlockingQueue: ${consumed.get}")

    val abq = new ArrayBlockingQueue[Integer](4)
    println(s"ArrayBlockingQueue: ${abq.offer(1)} ${abq.offer(2)} ${abq.offer(3)} ${abq.offer(4)} ${abq.offer(5)} ${abq.remainingCapacity}")
    println(s"poll with timeout: ${abq.poll()} ${abq.offer(9, 10, TimeUnit.MILLISECONDS)} ${abq} ${new ArrayBlockingQueue[Integer](1).poll(10, TimeUnit.MILLISECONDS)}")
    val drained = new java.util.ArrayList[Integer]()
    abq.drainTo(drained)
    println(s"drainTo: $drained ${abq.isEmpty}")

    val pbq = new PriorityBlockingQueue[Integer]()
    runAll(4)(t => for i <- 0 until 25 do pbq.put((i * 37 + t * 11) % 100))
    val ordered = Iterator.continually(pbq.poll()).takeWhile(_ != null).map(_.intValue).toList
    println(s"PriorityBlockingQueue: ${ordered.size} sorted=${ordered == ordered.sorted} first=${ordered.take(5)}")

    val clq = new ConcurrentLinkedQueue[Integer]()
    runAll(8)(t => for i <- 0 until 1000 do clq.offer(i))
    val polled = new AtomicInteger
    runAll(4)(_ => while clq.poll() != null do polled.incrementAndGet())
    println(s"ConcurrentLinkedQueue: ${polled.get} ${clq.isEmpty} ${clq.size}")
    val clq2 = new ConcurrentLinkedQueue[String](java.util.List.of("a", "b", "c"))
    clq2.remove("b")
    println(s"ConcurrentLinkedQueue ops: $clq2 ${clq2.peek} ${clq2.contains("c")}")

    val cld = new ConcurrentLinkedDeque[Integer]()
    runAll(4)(t => for i <- 0 until 500 do if t % 2 == 0 then cld.addFirst(i) else cld.addLast(i))
    var fromBothEnds = 0
    while !cld.isEmpty do
      if cld.pollFirst() != null then fromBothEnds += 1
      if cld.pollLast() != null then fromBothEnds += 1
    println(s"ConcurrentLinkedDeque: $fromBothEnds")
    val lbd = new LinkedBlockingDeque[String]()
    lbd.putFirst("b"); lbd.putFirst("a"); lbd.putLast("c")
    println(s"LinkedBlockingDeque: $lbd ${lbd.takeLast()} ${lbd.pollFirst(1, TimeUnit.MILLISECONDS)}")

    val sync = new SynchronousQueue[String]()
    val handoff = new Thread(() => sync.put("handed off"))
    handoff.start()
    println(s"SynchronousQueue: ${sync.take()} ${sync.offer("nobody")}")
    handoff.join()
    val ltq = new LinkedTransferQueue[String]()
    val receiver = new Thread(() => println(s"transferred: ${ltq.take()}"))
    receiver.start()
    ltq.transfer("transfer")
    receiver.join()

    class Delayed(val name: String, delayMillis: Long) extends java.util.concurrent.Delayed:
      private val at = System.nanoTime() + delayMillis * 1000000L
      def getDelay(unit: TimeUnit): Long = unit.convert(at - System.nanoTime(), TimeUnit.NANOSECONDS)
      def compareTo(o: java.util.concurrent.Delayed): Int = java.lang.Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS))
    val dq = new DelayQueue[Delayed]()
    dq.put(new Delayed("later", 60)); dq.put(new Delayed("sooner", 20))
    println(s"DelayQueue: ${dq.poll()} ${dq.take().name} ${dq.take().name}")

    // Copy-on-write collections: iteration over a snapshot while writers run.
    val cow = new CopyOnWriteArrayList[Integer]()
    runAll(4)(t => for i <- 0 until 250 do cow.add(t * 1000 + i))
    var seen = 0
    val it = cow.iterator()
    cow.clear()
    while it.hasNext do { it.next(); seen += 1 }
    println(s"CopyOnWriteArrayList: snapshot=$seen now=${cow.size}")
    val listeners = new CopyOnWriteArrayList[String](java.util.List.of("a", "b", "c"))
    println(s"addIfAbsent: ${listeners.addIfAbsent("a")} ${listeners.addIfAbsent("d")} $listeners ${listeners.indexOf("c")} ${listeners.subList(1, 3)}")
    for s <- listeners.asScala do if s == "b" then listeners.remove(s)
    println(s"remove while iterating: $listeners")
    val cows = new CopyOnWriteArraySet[String]()
    runAll(4)(t => for i <- 0 until 20 do cows.add(s"e${i % 5}"))
    println(s"CopyOnWriteArraySet: ${cows.size} ${cows.contains("e3")}")

    // Skip-list map and set: sorted, navigable, safe to share.
    val sl = new ConcurrentSkipListMap[Integer, String]()
    runAll(4)(t => for i <- t until 100 by 4 do sl.put(i, s"v$i"))
    println(s"ConcurrentSkipListMap: ${sl.size} ${sl.firstKey} ${sl.lastKey} ${sl.ceilingKey(50)} ${sl.floorEntry(-1)} ${sl.higherEntry(97)}")
    println(s"headMap: ${sl.headMap(5)} tailMap: ${sl.tailMap(97)} desc: ${sl.descendingMap().headMap(97)}")
    println(s"subMap keys: ${sl.subMap(10, true, 15, false).keySet}")
    for k <- sl.keySet.asScala do if k % 2 == 0 then sl.remove(k) // no ConcurrentModificationException
    println(s"removed while iterating: ${sl.size} ${sl.pollFirstEntry()} ${sl.firstKey}")
    val sls = new ConcurrentSkipListSet[String]()
    runAll(4)(t => for i <- 0 until 26 do sls.add(('a' + (i * 7 + t) % 26).toChar.toString))
    println(s"ConcurrentSkipListSet: ${sls.size} ${sls.first} ${sls.last} ${sls.headSet("d")} ${sls.descendingSet().first}")

    // ThreadLocalRandom.
    val rnd = ThreadLocalRandom.current()
    println(s"ThreadLocalRandom: ${(1 to 1000).map(_ => rnd.nextInt(10)).forall(x => x >= 0 && x < 10)} ${rnd.nextLong(5, 6)}")

    // The concurrent collectors of java.util.stream collect into ConcurrentHashMaps.
    import java.util.stream.{Collectors, Stream}
    val byLength = Stream.of("a", "bb", "cc", "ddd").collect(Collectors.groupingByConcurrent[String, Integer](s => s.length))
    println(s"groupingByConcurrent: $byLength ${byLength.isInstanceOf[ConcurrentHashMap[?, ?]]}")
    val concurrentMap = Stream.of("x", "yy", "zzz").collect(Collectors.toConcurrentMap[String, String, Integer](s => s, s => s.length))
    println(s"toConcurrentMap: $concurrentMap")
