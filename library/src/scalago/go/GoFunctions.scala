package scalago.go

import scalago.go.internal.GoNatives

// Go funcs returned to Scala by facades (`def Adder(): Long => Long = native`). The linker wraps
// the Go func into a func taking and returning Scala values, converted according to the facade's
// types, and these classes call it.

final class GoFunction0[+R](f: AnyRef) extends (() => R):
  def apply(): R = GoNatives.call0(f).asInstanceOf[R]
  override def toString: String = "<Go func>"

final class GoFunction1[-A, +R](f: AnyRef) extends (A => R):
  def apply(a: A): R = GoNatives.call1(f, a).asInstanceOf[R]
  override def toString: String = "<Go func>"

final class GoFunction2[-A, -B, +R](f: AnyRef) extends ((A, B) => R):
  def apply(a: A, b: B): R = GoNatives.call2(f, a, b).asInstanceOf[R]
  override def toString: String = "<Go func>"

final class GoFunction3[-A, -B, -C, +R](f: AnyRef) extends ((A, B, C) => R):
  def apply(a: A, b: B, c: C): R = GoNatives.call3(f, a, b, c).asInstanceOf[R]
  override def toString: String = "<Go func>"
