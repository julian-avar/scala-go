package scalago.scalalib.runtime

import scala.annotation.static

// Mutable cells for local variables captured by closures (Java classes in the Scala library).

final class BooleanRef(var elem: Boolean) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object BooleanRef:
  @static def create(e: Boolean): BooleanRef = new BooleanRef(e)
  @static def zero(): BooleanRef = new BooleanRef(false)

final class ByteRef(var elem: Byte) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object ByteRef:
  @static def create(e: Byte): ByteRef = new ByteRef(e)
  @static def zero(): ByteRef = new ByteRef(0)

final class CharRef(var elem: Char) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object CharRef:
  @static def create(e: Char): CharRef = new CharRef(e)
  @static def zero(): CharRef = new CharRef(0)

final class ShortRef(var elem: Short) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object ShortRef:
  @static def create(e: Short): ShortRef = new ShortRef(e)
  @static def zero(): ShortRef = new ShortRef(0)

final class IntRef(var elem: Int) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object IntRef:
  @static def create(e: Int): IntRef = new IntRef(e)
  @static def zero(): IntRef = new IntRef(0)

final class LongRef(var elem: Long) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object LongRef:
  @static def create(e: Long): LongRef = new LongRef(e)
  @static def zero(): LongRef = new LongRef(0)

final class FloatRef(var elem: Float) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object FloatRef:
  @static def create(e: Float): FloatRef = new FloatRef(e)
  @static def zero(): FloatRef = new FloatRef(0)

final class DoubleRef(var elem: Double) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object DoubleRef:
  @static def create(e: Double): DoubleRef = new DoubleRef(e)
  @static def zero(): DoubleRef = new DoubleRef(0)

final class ObjectRef[T](var elem: T) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object ObjectRef:
  @static def create[U](e: U): ObjectRef[U] = new ObjectRef(e)
  @static def zero(): ObjectRef[Object] = new ObjectRef(null)

final class VolatileIntRef(var elem: Int) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object VolatileIntRef:
  @static def create(e: Int): VolatileIntRef = new VolatileIntRef(e)
  @static def zero(): VolatileIntRef = new VolatileIntRef(0)

final class VolatileBooleanRef(var elem: Boolean) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object VolatileBooleanRef:
  @static def create(e: Boolean): VolatileBooleanRef = new VolatileBooleanRef(e)
  @static def zero(): VolatileBooleanRef = new VolatileBooleanRef(false)

final class VolatileObjectRef[T](var elem: T) extends java.io.Serializable:
  override def toString(): String = String.valueOf(elem)
object VolatileObjectRef:
  @static def create[U](e: U): VolatileObjectRef[U] = new VolatileObjectRef(e)
  @static def zero(): VolatileObjectRef[Object] = new VolatileObjectRef(null)
