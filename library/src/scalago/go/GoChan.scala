package scalago.go

import scalago.go.internal.GoNatives

/** A Go channel, `chan T`, shared with Go code. Unbuffered unless made with a capacity.
 *
 *  {{{
 *  import scalago.go.*
 *
 *  val ch = GoChan.make[Int](capacity = 10)   // chan int32
 *  ch.send(1)
 *  ch.close()
 *  for v <- ch do println(v)                  // Go's for v := range ch
 *  }}}
 *
 *  Blocking operations block the calling thread (a goroutine), and are interrupted by
 *  `Thread.interrupt`. In gears code, `scalago.gears` has cancellable versions
 *  (`awaitSend`, `awaitReceive`).
 */
opaque type GoChan[T] = AnyRef

object GoChan:
  /** Go's `make(chan T)`: an unbuffered channel. */
  def make[T](using t: GoType[T]): GoChan[T] = GoNatives.makeChan(t.underlying, 0)
  /** Go's `make(chan T, capacity)`. */
  def make[T](capacity: Int)(using t: GoType[T]): GoChan[T] = GoNatives.makeChan(t.underlying, capacity)

  private object Closed
  private object NotReady

  extension [T](c: GoChan[T])
    /** Blocks until the value is sent. Sending on a closed channel throws. */
    def send(value: T): Unit = GoNatives.chanSend(c, value)
    /** Blocks for the next value; `None` once the channel is closed and drained. */
    def receive(): Option[T] =
      val r = GoNatives.chanReceive(c, Closed)
      if r.asInstanceOf[AnyRef] eq Closed then None else Some(r.asInstanceOf[T])
    /** Sends if that does not block. */
    def trySend(value: T): Boolean = GoNatives.chanTrySend(c, value)
    /** A value if one is ready (`None` if none is, or if the channel is closed). */
    def tryReceive(): Option[T] =
      val r = GoNatives.chanTryReceive(c, NotReady, Closed)
      if (r.asInstanceOf[AnyRef] eq Closed) || (r.asInstanceOf[AnyRef] eq NotReady) then None else Some(r.asInstanceOf[T])
    /** Go's `close(c)`. */
    def close(): Unit = GoNatives.chanClose(c)
    /** Go's `len(c)`: the number of buffered values. */
    def length: Int = GoNatives.chanLength(c)
    /** Go's `cap(c)`. */
    def capacity: Int = GoNatives.chanCapacity(c)
    def isNil: Boolean = GoNatives.isNil(c)
    /** The values received until the channel is closed (Go's `for v := range c`). */
    def iterator: Iterator[T] = Iterator.continually(receive()).takeWhile(_.isDefined).map(_.get)
    def foreach[U](f: T => U): Unit = iterator.foreach(f)
