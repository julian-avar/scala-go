package scalago.linker

import scalago.ir.*

/** Injective mapping from IR names to Go identifiers.
 *
 *  All generated code lives in one Go package (Scala packages may depend on each other
 *  cyclically; Go packages may not), so class names are encoded into identifiers:
 *  `[A-Za-z0-9]` is kept, `_` becomes `_u`, `.` becomes `_d`, `$` becomes `_S`, and anything
 *  else becomes `_x` followed by four hex digits.
 *
 *  An escaped name never contains `__` and never ends with `_`, so `__` separates a class from
 *  one of its members, and the runtime can demangle `Impl_` names back to Scala class and method
 *  names for stack traces (`rt/stacktrace.go`).
 *
 *  Package-level declarations use these prefixes:
 *  {{{
 *  S_<class>                 struct holding the fields of <class> (embeds the superclass struct)
 *  C_<class>                 interface: the Go type of references to <class>
 *  Impl_<class>__<method>    implementation of a method, taking `this` as a C_<class>
 *  Fwd_<class>__<method>     static forwarder to a module's method (the entry point's `main`)
 *  New_<class>__<ctor>       allocates an instance and runs a constructor
 *  Mod_<class>()             accessor for the instance of a module class
 *  SF_<class>__<field>       static field
 *  Clinit_<class>()          runs the static initializer once
 *  Disp_<method>             dynamic dispatch on receivers that may be strings or boxed values
 *  InstanceOf_<class>        type test for classes that strings or boxed values may belong to
 *  SL_<n>                    interned string literal
 *  CL_<n>                    `classOf` constant (a *rt.Class)
 *  As_<class>, Is_<class>    cast and type test to C_<class>, from `any`
 *  }}}
 *  Methods on structs: `M_<method>` (virtual methods, exported so the runtime can call
 *  `toString` and friends), `A_<class>` (returns the struct of an ancestor class) and
 *  `I_<interface>` (marks implemented interfaces, so Go type assertions are exact).
 */
object Mangling:
  def escape(s: String): String =
    val sb = StringBuilder()
    s.foreach {
      case c if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') => sb += c
      case '_' => sb ++= "_u"
      case '.' => sb ++= "_d"
      case '$' => sb ++= "_S"
      case c   => sb ++= f"_x${c.toInt}%04x"
    }
    sb.toString

  /** Type code used inside mangled method names (similar to JVM descriptors). */
  def typeCode(t: Type): String = t match
    case Type.Void        => "V"
    case Type.Boolean     => "Z"
    case Type.Char        => "C"
    case Type.Byte        => "B"
    case Type.Short       => "S"
    case Type.Int         => "I"
    case Type.Long        => "J"
    case Type.Float       => "F"
    case Type.Double      => "D"
    case Type.Nothing     => "N"
    case Type.Null        => "0"
    // The two most common class types get one-letter codes (unused by other codes, so the
    // encoding stays prefix-free): they are a tenth of the generated code otherwise.
    case Type.Class(ClassName.Object)   => "O"
    case Type.Class("java.lang.String") => "T"
    case Type.Class(name)               => s"L${escape(name)}_e"
    case Type.Array(elem) => s"A${typeCode(elem)}"

  /** Constructors are named `_init` (escaped names never start with `_i`). The simple name ends at
   *  the first `_p`, which escaped names never contain.
   */
  def method(m: MethodName): String =
    val simple = if m.isConstructor then "_init" else escape(m.simple)
    s"${simple}_p${m.params.map(typeCode).mkString}_r${typeCode(m.result)}"

  def struct(cls: ClassName): String = s"S_${escape(cls)}"
  def iface(cls: ClassName): String = s"C_${escape(cls)}"
  def accessor(cls: ClassName): String = s"A_${escape(cls)}"
  def impl(cls: ClassName, m: MethodName): String = s"Impl_${escape(cls)}__${method(m)}"
  def staticForwarder(cls: ClassName, m: MethodName): String = s"Fwd_${escape(cls)}__${method(m)}"
  def newInstance(cls: ClassName, ctor: MethodName): String = s"New_${escape(cls)}__${method(ctor)}"
  def module(cls: ClassName): String = s"Mod_${escape(cls)}"
  def moduleVar(cls: ClassName): String = s"mod_${escape(cls)}"
  def staticField(cls: ClassName, field: String): String = s"SF_${escape(cls)}__${escape(field)}"
  def virtualMethod(m: MethodName): String = s"M_${method(m)}"
  def field(name: String): String = s"f_${escape(name)}"
  def local(name: LocalName): String = s"l_${escape(name)}"
  /** A method body's local holding the struct of `cls` of `this` (see FunctionEmitter). */
  def thisStruct(cls: ClassName): String = s"self_${escape(cls)}"
  def label(name: String): String = s"L_${escape(name)}"
  def fileName(cls: ClassName): String = s"${escape(cls)}.go"
  def marker(iface: ClassName): String = s"I_${escape(iface)}"
  def clinit(cls: ClassName): String = s"Clinit_${escape(cls)}"
  def clinitLock(cls: ClassName): String = s"clinitLock_${escape(cls)}"
  def moduleInitLock(cls: ClassName): String = s"modLock_${escape(cls)}"
  def dispatcher(m: MethodName): String = s"Disp_${method(m)}"
  def instanceTest(cls: ClassName): String = s"InstanceOf_${escape(cls)}"
  def ifaceCast(cls: ClassName): String = s"As_${escape(cls)}"
  def ifaceTest(cls: ClassName): String = s"Is_${escape(cls)}"
