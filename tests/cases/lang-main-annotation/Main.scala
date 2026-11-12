object Greeting {
  val prefix: String = "Hello"
}

@main def greet(): Unit =
  println(Greeting.prefix + " from @main")
  val total = (1 to 4).sum
  println(s"total = $total")
