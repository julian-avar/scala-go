import scala.collection.mutable

/** scala.collection.mutable, StringBuilder and Array operations. Hash-based collections are
  * sorted before printing: their iteration order is not part of the API. */
object Main:
  def section(name: String): Unit = println(s"-- $name")

  def main(args: Array[String]): Unit =
    arrayBuffers()
    listBuffers()
    maps()
    sets()
    deques()
    priorityQueues()
    stacksAndQueues()
    sortedMutable()
    stringBuilders()
    arrays()

  def arrayBuffers(): Unit =
    section("ArrayBuffer")
    val b = mutable.ArrayBuffer(1, 2, 3)
    b += 4
    b ++= List(5, 6)
    0 +=: b
    b.prepend(-1)
    println(b)
    b.insert(2, 100)
    b.insertAll(0, List(7, 8))
    println(b)
    println(s"${b.remove(0)} ${b.remove(0, 2)} $b")
    b -= 100
    b --= List(5, 6)
    println(b)
    b(0) = 42
    b.update(1, 43)
    println(s"$b ${b.length} ${b.indexOf(3)}")
    b.filterInPlace(_ % 2 == 1)
    println(b)
    b.mapInPlace(_ * 10)
    println(b)
    b.sortInPlace()(using Ordering.Int.reverse)
    println(b)
    b.patchInPlace(1, List(-5, -6), 1)
    println(b)
    b.dropInPlace(1).takeRightInPlace(2)
    println(b)
    b.clear()
    println(s"$b ${b.isEmpty}")
    val big = mutable.ArrayBuffer.tabulate(10000)(identity)
    println(s"${big.sum} ${big.last} ${big.reverseIterator.take(2).toList}")
    big.trimEnd(9990)
    big.trimStart(5)
    println(big)
    println(mutable.ArrayBuffer.fill(3)("a").mkString + " " + mutable.ArrayBuffer.from("xyz").reverse)
    val shared = mutable.ArrayBuffer(1, 2, 3)
    val copy = shared.clone()
    shared(0) = 99
    println(s"$shared $copy")
    println(try shared(10) catch case e: IndexOutOfBoundsException => e.getClass.getName)

  def listBuffers(): Unit =
    section("ListBuffer")
    val lb = mutable.ListBuffer.empty[String]
    lb += "b"
    "a" +=: lb
    lb ++= Seq("c", "d")
    lb.insert(1, "x")
    println(lb)
    println(s"${lb.toList} ${lb.result().getClass.getName}")
    lb -= "x"
    lb.remove(0)
    println(s"$lb ${lb.head} ${lb.last} ${lb.size}")
    lb.prependAll(List("p", "q"))
    lb.subtractAll(List("q", "c"))
    println(lb)
    val built = mutable.ListBuffer.from(1 to 5).map(_ * 2).filter(_ > 2)
    println(built)
    lb.mapInPlace(_.toUpperCase)
    lb.update(0, "first")
    println(lb.mkString("[", ";", "]"))

  def maps(): Unit =
    section("mutable.Map")
    val m = mutable.Map("a" -> 1, "b" -> 2)
    m("c") = 3
    m += "d" -> 4
    m ++= List("e" -> 5, "f" -> 6)
    m -= "a"
    m --= List("e", "zz")
    println(m.toList.sorted)
    println(s"${m.getOrElseUpdate("b", 100)} ${m.getOrElseUpdate("g", 7)} ${m.size}")
    println(s"${m.put("b", 20)} ${m.put("h", 8)} ${m.remove("h")} ${m.remove("h")}")
    m.update("b", 21)
    m.updateWith("c")(_.map(_ * 100))
    m.updateWith("new")(_ => Some(-1))
    m.updateWith("d")(_ => None)
    println(m.toList.sorted)
    m.mapValuesInPlace((k, v) => if k == "new" then 0 else v + 1)
    m.filterInPlace((_, v) => v > 0)
    println(m.toList.sorted)
    println(s"${m.contains("b")} ${m.get("zzz")} ${m.keySet.toList.sorted}")
    val counts = mutable.HashMap.empty[Char, Int].withDefaultValue(0)
    for c <- "mississippi" do counts(c) += 1
    println(counts.toList.sorted)
    val grow = mutable.HashMap.empty[Int, String]
    for i <- 1 to 5000 do grow(i) = i.toString
    for i <- 1 to 5000 if i % 2 == 0 do grow.remove(i)
    println(s"${grow.size} ${grow(4999)} ${grow.get(4998)} ${grow.keys.sum}")
    val im: Map[String, Int] = m.toMap
    m.clear()
    println(s"${im.toList.sorted} ${m.isEmpty}")

    val lhm = mutable.LinkedHashMap("z" -> 1, "a" -> 2)
    lhm("m") = 3
    lhm("z") = 10
    lhm.remove("a")
    lhm("a") = 4
    println(lhm)
    println(s"${lhm.keys.toList} ${lhm.head} ${lhm.last}")
    val seqMap = mutable.SeqMap("y" -> 1, "x" -> 2)
    println(seqMap)

  def sets(): Unit =
    section("mutable.Set")
    val s = mutable.Set(3, 1, 2)
    s += 4
    s ++= List(5, 6)
    s -= 1
    println(s.toList.sorted)
    println(s"${s.add(2)} ${s.add(7)} ${s.remove(7)} ${s.remove(7)} ${s(3)}")
    s.filterInPlace(_ % 2 == 0)
    println(s.toList.sorted)
    val words = mutable.HashSet.empty[String]
    "the quick brown fox jumps over the lazy dog the end".split(" ").foreach(words += _)
    println(s"${words.size} ${words.toList.sorted}")
    val lhs = mutable.LinkedHashSet("c", "a", "b")
    lhs += "a"
    lhs -= "c"
    lhs += "c"
    println(lhs)
    val bits = mutable.BitSet(1, 3)
    bits += 100
    bits(5) = true
    bits(1) = false
    println(s"$bits ${bits.toImmutable}")

  def deques(): Unit =
    section("ArrayDeque")
    val d = mutable.ArrayDeque(1, 2, 3)
    d.prepend(0)
    d.append(4)
    println(d)
    println(s"${d.removeHead()} ${d.removeLast()} $d")
    println(s"${d.removeHeadOption()} ${mutable.ArrayDeque.empty[Int].removeHeadOption()}")
    for i <- 1 to 100 do d.append(i)
    for _ <- 1 to 95 do d.removeHead()
    println(s"$d ${d(2)} ${d.length}")
    println(d.removeAll(_ > 98))
    println(d)
    d.clear()
    println(d.isEmpty)

  def priorityQueues(): Unit =
    section("PriorityQueue")
    val pq = mutable.PriorityQueue(5, 1, 8, 3, 9, 2)
    println(s"${pq.head} ${pq.size}")
    println(Iterator.continually(pq.dequeue()).take(3).toList)
    pq.enqueue(100, -1)
    println(pq.dequeueAll)
    val minFirst = mutable.PriorityQueue.empty[(Int, String)](using Ordering.by[(Int, String), Int](_._1).reverse)
    minFirst ++= List(3 -> "c", 1 -> "a", 2 -> "b")
    println(minFirst.dequeue())
    println(minFirst.toList.sorted)
    println(mutable.PriorityQueue("pear", "fig", "apple").clone().dequeueAll)

  def stacksAndQueues(): Unit =
    section("Stack, Queue")
    val st = mutable.Stack(1, 2)
    st.push(3)
    st.pushAll(List(4, 5))
    println(st)
    println(s"${st.pop()} ${st.top} ${st.size}")
    println(st.popAll())
    val q = mutable.Queue("a")
    q.enqueue("b", "c")
    q += "d"
    println(s"${q.dequeue()} ${q.front} $q")
    println(q.dequeueAll(_ != "c"))
    println(q)

  def sortedMutable(): Unit =
    section("mutable.TreeMap, TreeSet")
    val tm = mutable.TreeMap(3 -> "c", 1 -> "a")
    tm(2) = "b"
    tm += 0 -> "zero"
    tm -= 3
    println(tm)
    println(s"${tm.firstKey} ${tm.lastKey} ${tm.rangeFrom(1)} ${tm.minAfter(2)}")
    val ts = mutable.TreeSet(5, 1, 3)
    ts += 2
    ts -= 5
    println(s"$ts ${ts.head} ${ts.rangeTo(2)}")

  def stringBuilders(): Unit =
    section("StringBuilder")
    val sb = new StringBuilder("abc")
    sb += 'd'
    sb ++= "ef"
    sb.append(1).append(2.5).append(true).append('!')
    println(sb)
    sb.insert(0, ">>")
    sb.setCharAt(2, 'A')
    println(s"$sb ${sb.length} ${sb.charAt(3)} ${sb.indexOf("d")}")
    sb.deleteCharAt(0)
    sb.delete(0, 1)
    sb.replace(0, 3, "XYZ")
    println(sb.result())
    println(sb.reverse)
    println(new StringBuilder().appendAll(List('x', 'y')).append("z" * 3).toString)
    println(mutable.StringBuilder.newBuilder.addAll("built").result())
    val joined = new StringBuilder
    List("a", "b", "c").addString(joined, "(", "-", ")")
    println(joined)
    sb.setLength(2)
    println(s"[$sb] ${sb.isEmpty} ${sb.nonEmpty} ${sb.map(_.toLower)}")
    sb.clear()
    println(s"[$sb] ${sb.isEmpty}")

  def arrays(): Unit =
    section("Array")
    val a = Array(5, 3, 8, 1)
    println(a.mkString(",") + " " + a.length + " " + a.sum)
    println(a.sorted.mkString(",") + " " + a.reverse.mkString(","))
    println((a :+ 9).mkString(",") + " " + (0 +: a).mkString(",") + " " + (a ++ Array(7)).mkString(","))
    println(a.indexOf(8) + " " + a.contains(1) + " " + a.max)
    scala.util.Sorting.quickSort(a)
    println(a.mkString(","))
    val words = Array("pear", "fig", "apple")
    scala.util.Sorting.stableSort(words, (x: String, y: String) => x.length < y.length)
    println(words.mkString(","))
    println(Array.fill(3)(7).mkString + " " + Array.tabulate(3)(_ * 2).mkString + " " + Array.range(0, 10, 4).mkString)
    val grid = Array.ofDim[Int](2, 3)
    grid(1)(2) = 5
    println(grid.map(_.mkString(" ")).mkString(" / "))
    println(Array.concat(Array(1), Array(2, 3)).mkString + " " + Array.empty[String].length)
    val copy = new Array[Int](6)
    Array.copy(a, 1, copy, 2, 3)
    println(copy.mkString(","))
    println(s"${a.toList} ${a.toVector} ${a.toSeq} ${a.to(List)}")
    println(s"${a.sameElements(Array(1, 3, 5, 8))} ${a sameElements a.clone()} ${a == a.clone()}")
    println(a.zip(a.tail).map((x, y) => y - x).mkString(",") + " " + a.slice(1, 3).mkString(","))
    println(s"${a.grouped(3).map(_.mkString).toList} ${a.sliding(2).length}")
    println(Array("b", "a").sorted.mkString + " " + Array(1.5, -2.0).map(math.abs).mkString(","))
    println(a.filter(_ > 2).map(_ * 10).mkString(",") + " " + a.exists(_ > 7) + " " + a.count(_ % 2 == 1))
    println(a.partition(_ > 3).toList.map(_.mkString))
    println(a.foldLeft(0)(_ * 10 + _) + " " + a.distinct.length + " " + a.find(_ > 4))
    println(s"${a.iterator.drop(2).toList} ${a.indices} ${a.lastOption}")
    println(Array(Array(1, 2), Array(3)).flatten.mkString + " " + Array(1, 2, 3).transform(_ * 3).mkString)
    val chars = "hello".toCharArray
    java.util.Arrays.sort(chars)
    println(chars.mkString + " " + new String(chars.reverse))
    println(Array((1, 'a'), (2, 'b')).unzip match { case (xs, ys) => xs.mkString + ys.mkString })
    println(mutable.ArraySeq.make(Array(1, 2, 3)).update(0, 9))
    val wrapped = mutable.ArraySeq(1, 2, 3)
    wrapped(0) = 9
    println(wrapped)
    println(a.toBuffer += 99)
    println(try a(10) catch case e: ArrayIndexOutOfBoundsException => e.getMessage)
