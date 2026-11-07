import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import upickle.default.*

case class Order(id: Int, items: List[String]) derives ReadWriter

object Main:
  def main(args: Array[String]): Unit =
    val orders = (1 to 3).map(i => Order(i, List.fill(i)("x")))
    val json = write(orders)
    println(json)
    println(read[Seq[Order]](json).map(_.items.size).sum)
    val total = Await.result(Future.sequence(orders.map(o => Future(o.items.size * 10))), 5.seconds).sum
    println(s"total $total")
    println(java.time.LocalDate.of(2024, 2, 28).plusDays(2))
    println(scalago.gostd.strings.Repeat("go", 3))
