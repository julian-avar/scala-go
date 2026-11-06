trait Transformer {
  def transform(x: Int): Int
}

object Main {
  def applyTwice(f: Int => Int, x: Int): Int = f(f(x))

  def compose[A, B, C](f: B => C, g: A => B): A => C = a => f(g(a))

  def makeCounter(): () => Int = {
    var count = 0
    () => { count += 1; count }
  }

  def run(t: Transformer, x: Int): Int = t.transform(x)

  def main(args: Array[String]): Unit = {
    val add = (a: Int, b: Int) => a + b
    println(add(2, 3))
    println(applyTwice(_ * 3, 2))
    val greet: String => String = name => "hi " + name
    println(compose(greet, (n: Int) => "#" + n)(7))
    val counter = makeCounter()
    counter(); counter()
    println(counter())
    println(run(x => x * x, 9))
    val r: Runnable = () => println("running")
    r.run()
    var total = 0L
    val addTo = (x: Int) => total += x
    addTo(5); addTo(10)
    println(total)
    val inc = (x: Int) => x + 1
    val double = (x: Int) => x * 2
    println((inc andThen double)(3) + " " + (inc compose double)(3))
    val curried = (a: Int) => (b: Int) => a - b
    println(curried(10)(4))
    val unit: () => Unit = () => print("side effect\n")
    unit()
    def factorial(n: Int): Long = if (n <= 1) 1 else n * factorial(n - 1)
    val f: Int => String = factorial(_).toString
    println(f(5))
    // Unit-returning lambdas implement JProcedure1, whose Function1 bridge the linker adds.
    List("a", "b").foreach(println)
    List(1, 2).foreach(x => print(x + ";"))
    println()
  }
}
