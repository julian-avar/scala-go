package java.util.concurrent

import scala.collection.mutable

class CancellationException(message: String) extends java.lang.IllegalStateException(message):
  def this() = this(null)

class TimeoutException(message: String) extends java.lang.Exception(message):
  def this() = this(null)

class ExecutionException(message: String, cause: Throwable) extends java.lang.Exception(message, cause):
  def this(cause: Throwable) = this(if cause == null then null else cause.toString(), cause)
  def this(message: String) = this(message, null)
  def this() = this(null, null)

trait ThreadFactory:
  def newThread(r: java.lang.Runnable): java.lang.Thread

trait Executor:
  def execute(command: java.lang.Runnable): Unit

/** A thread-safe FIFO queue (a lock, not lock-free as on the JVM). Elements are compared with
 *  `equals` by `contains` and `remove`, as in the JDK.
 */
class ConcurrentLinkedQueue[E] extends Object with java.io.Serializable:
  private val items = mutable.ArrayDeque.empty[E]

  def add(e: E): Boolean = offer(e)
  def offer(e: E): Boolean =
    if e == null then throw new java.lang.NullPointerException()
    synchronized(items.append(e))
    true
  def poll(): E = synchronized(if items.isEmpty then null.asInstanceOf[E] else items.removeHead())
  def peek(): E = synchronized(if items.isEmpty then null.asInstanceOf[E] else items.head)
  def isEmpty(): Boolean = synchronized(items.isEmpty)
  def size(): Int = synchronized(items.size)
  def contains(o: Object): Boolean = synchronized(items.exists(_ == o))
  def remove(o: Object): Boolean = synchronized {
    val i = items.indexWhere(_ == o)
    if i >= 0 then
      items.remove(i)
      true
    else false
  }
  def clear(): Unit = synchronized(items.clear())
  override def toString(): String = synchronized(items.mkString("[", ", ", "]"))
