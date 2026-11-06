object Main {
  def main(args: Array[String]): Unit = {
    val xs = List(5, 3, 8, 1)
    println(xs)
    println(xs.map(_ * 2).filter(_ > 5))
    println(xs.sum + " " + xs.max + " " + xs.length)
    println(xs.sorted.mkString("[", ", ", "]"))
    println(xs.foldLeft(0)(_ + _))
    println(xs.reverse.head + " " + xs.tail)
    println(xs.zip(List("a", "b", "c")))
    val opt = xs.find(_ > 4)
    println(s"$opt ${opt.map(_ + 1).getOrElse(0)} ${xs.find(_ > 100)}")
    val m = Map("one" -> 1, "two" -> 2)
    println(m("two") + " " + m.getOrElse("three", 3) + " " + m.contains("one"))
    val sb = new StringBuilder
    for (i <- 1 to 5) sb.append(i).append(',')
    println(sb.toString)
    val name = "Scala"
    val version = 3
    println(s"Hello $name $version! ${1 + 1}")
    println((1 until 4).map(i => i * i).toList)
    val arr = Array(3, 1, 2)
    println(s"${arr.sorted.toList} ${arr.map(_ + 1).mkString(",")}")
    println(Vector(1, 2, 3) :+ 4)
    println(xs.groupBy(_ % 2 == 0).toList.sortBy(_._1))
    println("a,b,,c".split(",").toList)
    println(Seq("b", "a", "c").sorted)
    val words = List("apple", "banana", "cherry")
    println(words.map(_.length).zip(words).maxBy(_._1)._2)
    println((1 to 10).filter(_ % 3 == 0).toList)
    println(s"${List(1, 2, 3).exists(_ == 2)} ${List(1, 2, 3).forall(_ > 0)}")
    println(Some(1).flatMap(x => if (x > 0) Some(x * 10) else None))
    println("hello".toUpperCase.reverse + " " + "abc".contains("b") + " " + "x".repeat(3))
  }
}
