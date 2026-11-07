package java.util

/** A last-in, first-out stack on top of `Vector` (whose methods are synchronized). */
class Stack[E <: AnyRef] extends Vector[E]:
  def empty(): Boolean = isEmpty()

  def peek(): E = synchronized {
    if elementCount == 0 then throw new EmptyStackException()
    elementData(elementCount - 1)
  }

  def pop(): E = synchronized {
    val top = peek()
    removeElementAt(elementCount - 1)
    top
  }

  def push(item: E): E =
    addElement(item)
    item

  /** The 1-based distance of `o` from the top of the stack, or -1. */
  def search(o: Any): Int = synchronized {
    val i = lastIndexOf(o)
    if i >= 0 then elementCount - i else -1
  }
