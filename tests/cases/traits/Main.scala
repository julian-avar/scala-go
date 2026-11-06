trait Greeter {
  def name: String
  val greeting: String = "Hello"
  def greet(): String = greeting + ", " + name
}

trait Loud extends Greeter {
  override def greet(): String = super.greet().toUpperCase + "!"
}

trait Counter {
  private var count = 0
  def next(): Int = { count += 1; count }
}

class Person(val name: String) extends Greeter

class LoudPerson(name0: String) extends Person(name0) with Loud with Counter

abstract class Animal(val kind: String) {
  def sound: String
  def describe: String = kind + " says " + sound
}

trait Pet { self: Animal =>
  def owner: String
  def tag: String = owner + "'s " + kind
}

class Dog extends Animal("dog") with Pet {
  def sound = "woof"
  def owner = "Ann"
}

object Main {
  def describeAll(greeters: Array[Greeter]): Unit = {
    var i = 0
    while (i < greeters.length) {
      println(greeters(i).greet())
      i += 1
    }
  }

  def main(args: Array[String]): Unit = {
    describeAll(Array(new Person("Ada"), new LoudPerson("Bob")))
    val lp = new LoudPerson("Cy")
    lp.next(); lp.next()
    println(lp.next())
    val d = new Dog
    println(d.describe)
    println(d.tag)
    val pet: Pet = d
    println(pet.owner)
    val g: Greeter = lp
    println(g.isInstanceOf[Loud])
    println(g.isInstanceOf[Counter])
    println((new Person("x"): Greeter).isInstanceOf[Loud])
  }
}
