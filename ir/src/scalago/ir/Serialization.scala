package scalago.ir

import java.io.*
import scala.collection.mutable

/** Binary format of a `.goir` file: one `ClassDef` per file.
 *
 *  Layout: magic, format version, string table, then the class definition with all strings
 *  referenced by index into the table.
 */
object Serialization:
  val Magic: Int = 0x53474f49 // "SGOI"
  val FormatVersion: Int = 7
  val FileExtension: String = ".goir"

  final class IRFormatException(msg: String) extends IOException(msg)

  def serialize(cls: ClassDef): Array[Byte] =
    val strings = mutable.LinkedHashMap.empty[String, Int]
    val body = ByteArrayOutputStream()
    Writer(DataOutputStream(body), s => strings.getOrElseUpdate(s, strings.size)).writeClassDef(cls)

    val result = ByteArrayOutputStream()
    val out = DataOutputStream(result)
    out.writeInt(Magic)
    out.writeInt(FormatVersion)
    out.writeInt(strings.size)
    for s <- strings.keys do
      val bytes = encodeString(s)
      out.writeInt(bytes.length)
      out.write(bytes)
    body.writeTo(out)
    out.flush()
    result.toByteArray

  /** Strings are encoded as each UTF-16 unit on its own (like Java's modified UTF-8, without its
   *  64 KB limit): unlike UTF-8, this keeps unpaired surrogates, which string literals may hold.
   */
  private def encodeString(s: String): Array[Byte] =
    val out = ByteArrayOutputStream(s.length)
    for c <- s do
      if c >= 0x01 && c <= 0x7f then out.write(c)
      else if c <= 0x7ff then
        out.write(0xc0 | (c >> 6))
        out.write(0x80 | (c & 0x3f))
      else
        out.write(0xe0 | (c >> 12))
        out.write(0x80 | ((c >> 6) & 0x3f))
        out.write(0x80 | (c & 0x3f))
    out.toByteArray

  private def decodeString(bytes: Array[Byte]): String =
    val sb = java.lang.StringBuilder(bytes.length)
    var i = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      if b < 0x80 then
        sb.append(b.toChar)
        i += 1
      else if b < 0xe0 then
        sb.append((((b & 0x1f) << 6) | (bytes(i + 1) & 0x3f)).toChar)
        i += 2
      else
        sb.append((((b & 0x0f) << 12) | ((bytes(i + 1) & 0x3f) << 6) | (bytes(i + 2) & 0x3f)).toChar)
        i += 3
    sb.toString

  def deserialize(bytes: Array[Byte]): ClassDef =
    val in = DataInputStream(ByteArrayInputStream(bytes))
    if in.readInt() != Magic then throw IRFormatException("not a .goir file")
    val version = in.readInt()
    if version != FormatVersion then
      throw IRFormatException(s"unsupported .goir format version $version (expected $FormatVersion)")
    val strings = Array.fill(in.readInt()) {
      val bytes = new Array[Byte](in.readInt())
      in.readFully(bytes)
      decodeString(bytes)
    }
    Reader(in, strings).readClassDef()

  // Tree tags
  private object Tags:
    final val VarDef = 1
    final val Block = 2
    final val Skip = 3
    final val Labeled = 4
    final val Return = 5
    final val If = 6
    final val While = 7
    final val Assign = 8
    final val BooleanLiteral = 10
    final val CharLiteral = 11
    final val ByteLiteral = 12
    final val ShortLiteral = 13
    final val IntLiteral = 14
    final val LongLiteral = 15
    final val FloatLiteral = 16
    final val DoubleLiteral = 17
    final val StringLiteral = 18
    final val Null = 19
    final val VarRef = 20
    final val This = 21
    final val LoadModule = 22
    final val Select = 23
    final val SelectStatic = 24
    final val CompareAndSet = 25
    final val GetAndSet = 26
    final val GetAndAdd = 27
    final val New = 30
    final val Apply = 31
    final val ApplyStatically = 32
    final val ApplyStatic = 33
    final val Unary = 40
    final val Binary = 41
    final val Convert = 42
    final val Box = 43
    final val Unbox = 44
    final val AsInstanceOf = 45
    final val NewArray = 50
    final val ArrayValue = 51
    final val ArrayLength = 52
    final val ArraySelect = 53
    final val IsInstanceOf = 60
    final val ClassOf = 61
    final val Throw = 70
    final val TryCatch = 71
    final val TryFinally = 72
    final val Match = 80
    final val Closure = 90
    final val Unsupported = 99

  // Type tags
  private object TypeTags:
    final val Class = 100
    final val Array = 101

  /** Parameterless `Type` cases, indexed by ordinal (`Type.fromOrdinal` is unavailable on non-singleton enums). */
  private val SimpleTypes: IndexedSeq[Type] =
    import Type.*
    Vector(Void, Boolean, Char, Byte, Short, Int, Long, Float, Double, Nothing, Null)

  private final class Writer(out: DataOutputStream, intern: String => Int):
    import Tree.*

    def str(s: String): Unit = out.writeInt(intern(s))
    def bool(b: Boolean): Unit = out.writeBoolean(b)
    def int(i: Int): Unit = out.writeInt(i)
    def list[A](xs: List[A])(f: A => Unit): Unit = { int(xs.length); xs.foreach(f) }
    def option[A](x: Option[A])(f: A => Unit): Unit = { bool(x.isDefined); x.foreach(f) }

    def pos(p: Position): Unit = { str(p.source); int(p.line); int(p.column) }

    def tpe(t: Type): Unit = t match
      case Type.Class(name) => { out.writeByte(TypeTags.Class); str(name) }
      case Type.Array(elem) => { out.writeByte(TypeTags.Array); tpe(elem) }
      case other            => out.writeByte(other.ordinal)

    def method(m: MethodName): Unit = { str(m.simple); list(m.params)(tpe); tpe(m.result) }

    def writeClassDef(c: ClassDef): Unit =
      str(c.name)
      int(c.kind.ordinal)
      bool(c.isAbstract)
      option(c.superClass)(str)
      list(c.interfaces)(str)
      list(c.fields) { f => str(f.name); tpe(f.tpe); bool(f.isStatic); pos(f.pos); bool(f.isVolatile) }
      list(c.methods) { m =>
        method(m.name)
        list(m.params) { p => str(p.name); tpe(p.tpe) }
        option(m.body)(tree)
        bool(m.isStatic)
        bool(m.isPrivate)
        pos(m.pos)
        option(m.goMember) { g => str(g.goName); list(g.params)(scalaType); scalaType(g.result); bool(g.isProperty) }
        option(m.goExport) { g => str(g.goName); list(g.params)(scalaType); scalaType(g.result); bool(g.returnsError) }
      }
      pos(c.pos)
      option(c.goFacade) { f => str(f.pkg); str(f.name) }
      option(c.goExport)(str)

    def scalaType(t: ScalaType): Unit = t match
      case ScalaType.Prim(p)         => { out.writeByte(0); tpe(p) }
      case ScalaType.Ref(name, args) => { out.writeByte(1); str(name); list(args)(scalaType) }
      case ScalaType.Arr(elem)       => { out.writeByte(2); scalaType(elem) }

    def trees(ts: List[Tree]): Unit = list(ts)(tree)

    def tree(t: Tree): Unit =
      def tag(n: Int): Unit = { out.writeByte(n); pos(t.pos) }
      t match
        case VarDef(name, vtpe, mutable, rhs) => { tag(Tags.VarDef); str(name); tpe(vtpe); bool(mutable); tree(rhs) }
        case Block(stats, expr)               => { tag(Tags.Block); trees(stats); tree(expr) }
        case Skip()                           => tag(Tags.Skip)
        case Labeled(label, ty, body)         => { tag(Tags.Labeled); str(label); tpe(ty); tree(body) }
        case Return(label, expr)              => { tag(Tags.Return); option(label)(str); tree(expr) }
        case If(c, th, el, ty)                => { tag(Tags.If); tree(c); tree(th); tree(el); tpe(ty) }
        case While(c, body)                   => { tag(Tags.While); tree(c); tree(body) }
        case Assign(lhs, rhs)                 => { tag(Tags.Assign); tree(lhs); tree(rhs) }
        case BooleanLiteral(v)                => { tag(Tags.BooleanLiteral); bool(v) }
        case CharLiteral(v)                   => { tag(Tags.CharLiteral); out.writeChar(v) }
        case ByteLiteral(v)                   => { tag(Tags.ByteLiteral); out.writeByte(v) }
        case ShortLiteral(v)                  => { tag(Tags.ShortLiteral); out.writeShort(v) }
        case IntLiteral(v)                    => { tag(Tags.IntLiteral); int(v) }
        case LongLiteral(v)                   => { tag(Tags.LongLiteral); out.writeLong(v) }
        case FloatLiteral(v)                  => { tag(Tags.FloatLiteral); out.writeFloat(v) }
        case DoubleLiteral(v)                 => { tag(Tags.DoubleLiteral); out.writeDouble(v) }
        case StringLiteral(v)                 => { tag(Tags.StringLiteral); str(v) }
        case Null()                           => tag(Tags.Null)
        case VarRef(name, ty)                 => { tag(Tags.VarRef); str(name); tpe(ty) }
        case This(ty)                         => { tag(Tags.This); tpe(ty) }
        case LoadModule(cls)                  => { tag(Tags.LoadModule); str(cls) }
        case Select(q, cls, f, ty)            => { tag(Tags.Select); tree(q); str(cls); str(f); tpe(ty) }
        case SelectStatic(cls, f, ty)         => { tag(Tags.SelectStatic); str(cls); str(f); tpe(ty) }
        case CompareAndSet(f, e, u)           => { tag(Tags.CompareAndSet); tree(f); tree(e); tree(u) }
        case GetAndSet(f, v)                  => { tag(Tags.GetAndSet); tree(f); tree(v) }
        case GetAndAdd(f, d)                  => { tag(Tags.GetAndAdd); tree(f); tree(d) }
        case New(cls, ctor, args)             => { tag(Tags.New); str(cls); method(ctor); trees(args) }
        case Apply(r, rc, m, args, ty) =>
          tag(Tags.Apply); tree(r); str(rc); method(m); trees(args); tpe(ty)
        case ApplyStatically(r, cls, m, args, ty) =>
          tag(Tags.ApplyStatically); tree(r); str(cls); method(m); trees(args); tpe(ty)
        case ApplyStatic(cls, m, args, ty) =>
          tag(Tags.ApplyStatic); str(cls); method(m); trees(args); tpe(ty)
        case Unary(op, arg)              => { tag(Tags.Unary); int(op.ordinal); tree(arg) }
        case Binary(op, opType, l, r)    => { tag(Tags.Binary); int(op.ordinal); tpe(opType); tree(l); tree(r) }
        case Convert(arg, ty)            => { tag(Tags.Convert); tree(arg); tpe(ty) }
        case Box(arg)                    => { tag(Tags.Box); tree(arg) }
        case Unbox(arg, ty)              => { tag(Tags.Unbox); tree(arg); tpe(ty) }
        case AsInstanceOf(arg, ty)       => { tag(Tags.AsInstanceOf); tree(arg); tpe(ty) }
        case NewArray(elem, len)         => { tag(Tags.NewArray); tpe(elem); tree(len) }
        case ArrayValue(elem, elems)     => { tag(Tags.ArrayValue); tpe(elem); trees(elems) }
        case ArrayLength(arr)            => { tag(Tags.ArrayLength); tree(arr) }
        case ArraySelect(arr, idx, ty)   => { tag(Tags.ArraySelect); tree(arr); tree(idx); tpe(ty) }
        case IsInstanceOf(e, ty)         => { tag(Tags.IsInstanceOf); tree(e); tpe(ty) }
        case ClassOf(ty)                 => { tag(Tags.ClassOf); tpe(ty) }
        case Throw(e)                    => { tag(Tags.Throw); tree(e) }
        case TryCatch(b, cases, ty) =>
          tag(Tags.TryCatch); tree(b)
          list(cases) { c => option(c.binder)(str); tpe(c.tpe); tree(c.body) }
          tpe(ty)
        case TryFinally(b, f, ty) => { tag(Tags.TryFinally); tree(b); tree(f); tpe(ty) }
        case Match(sel, cases, default, ty) =>
          tag(Tags.Match); tree(sel)
          list(cases) { (alts, body) => trees(alts); tree(body) }
          tree(default); tpe(ty)
        case Closure(iface, sam, tc, tm, isStatic, caps) =>
          tag(Tags.Closure); str(iface); method(sam); str(tc); method(tm); bool(isStatic); trees(caps)
        case Unsupported(msg, ty) => { tag(Tags.Unsupported); str(msg); tpe(ty) }
  end Writer

  private final class Reader(in: DataInputStream, strings: Array[String]):
    import Tree.*

    def str(): String = strings(in.readInt())
    def bool(): Boolean = in.readBoolean()
    def int(): Int = in.readInt()
    def list[A](f: => A): List[A] = List.fill(int())(f)
    def option[A](f: => A): Option[A] = if bool() then Some(f) else None

    def pos(): Position = Position(str(), int(), int())

    def tpe(): Type = in.readUnsignedByte() match
      case TypeTags.Class => Type.Class(str())
      case TypeTags.Array => Type.Array(tpe())
      case ordinal        => SimpleTypes(ordinal)

    def method(): MethodName = MethodName(str(), list(tpe()), tpe())

    def readClassDef(): ClassDef =
      val name = str()
      val kind = ClassKind.fromOrdinal(int())
      val isAbstract = bool()
      val superClass = option(str())
      val interfaces = list(str())
      val fields = list(FieldDef(str(), tpe(), bool(), pos(), bool()))
      val methods = list {
        val name = method()
        val params = list(ParamDef(str(), tpe()))
        MethodDef(name, params, option(tree()), bool(), bool(), pos(),
          option(GoMember(str(), list(scalaType()), scalaType(), bool())),
          option(GoExport(str(), list(scalaType()), scalaType(), bool())))
      }
      ClassDef(name, kind, isAbstract, superClass, interfaces, fields, methods, pos(), option(GoFacade(str(), str())),
        option(str()))

    def scalaType(): ScalaType = in.readUnsignedByte() match
      case 0     => ScalaType.Prim(tpe())
      case 1     => ScalaType.Ref(str(), list(scalaType()))
      case 2     => ScalaType.Arr(scalaType())
      case other => throw IRFormatException(s"unknown Scala type tag $other")

    def trees(): List[Tree] = list(tree())

    def tree(): Tree =
      val tag = in.readUnsignedByte()
      val p = pos()
      tag match
        case Tags.VarDef          => VarDef(str(), tpe(), bool(), tree())(p)
        case Tags.Block           => Block(trees(), tree())(p)
        case Tags.Skip            => Skip()(p)
        case Tags.Labeled         => Labeled(str(), tpe(), tree())(p)
        case Tags.Return          => Return(option(str()), tree())(p)
        case Tags.If              => If(tree(), tree(), tree(), tpe())(p)
        case Tags.While           => While(tree(), tree())(p)
        case Tags.Assign          => Assign(tree(), tree())(p)
        case Tags.BooleanLiteral  => BooleanLiteral(bool())(p)
        case Tags.CharLiteral     => CharLiteral(in.readChar())(p)
        case Tags.ByteLiteral     => ByteLiteral(in.readByte())(p)
        case Tags.ShortLiteral    => ShortLiteral(in.readShort())(p)
        case Tags.IntLiteral      => IntLiteral(int())(p)
        case Tags.LongLiteral     => LongLiteral(in.readLong())(p)
        case Tags.FloatLiteral    => FloatLiteral(in.readFloat())(p)
        case Tags.DoubleLiteral   => DoubleLiteral(in.readDouble())(p)
        case Tags.StringLiteral   => StringLiteral(str())(p)
        case Tags.Null            => Null()(p)
        case Tags.VarRef          => VarRef(str(), tpe())(p)
        case Tags.This            => This(tpe())(p)
        case Tags.LoadModule      => LoadModule(str())(p)
        case Tags.Select          => Select(tree(), str(), str(), tpe())(p)
        case Tags.SelectStatic    => SelectStatic(str(), str(), tpe())(p)
        case Tags.CompareAndSet   => CompareAndSet(tree(), tree(), tree())(p)
        case Tags.GetAndSet       => GetAndSet(tree(), tree())(p)
        case Tags.GetAndAdd       => GetAndAdd(tree(), tree())(p)
        case Tags.New             => New(str(), method(), trees())(p)
        case Tags.Apply           => Apply(tree(), str(), method(), trees(), tpe())(p)
        case Tags.ApplyStatically => ApplyStatically(tree(), str(), method(), trees(), tpe())(p)
        case Tags.ApplyStatic     => ApplyStatic(str(), method(), trees(), tpe())(p)
        case Tags.Unary           => Unary(UnaryOp.fromOrdinal(int()), tree())(p)
        case Tags.Binary          => Binary(BinaryOp.fromOrdinal(int()), tpe(), tree(), tree())(p)
        case Tags.Convert         => Convert(tree(), tpe())(p)
        case Tags.Box             => Box(tree())(p)
        case Tags.Unbox           => Unbox(tree(), tpe())(p)
        case Tags.AsInstanceOf    => AsInstanceOf(tree(), tpe())(p)
        case Tags.NewArray        => NewArray(tpe(), tree())(p)
        case Tags.ArrayValue      => ArrayValue(tpe(), trees())(p)
        case Tags.ArrayLength     => ArrayLength(tree())(p)
        case Tags.ArraySelect     => ArraySelect(tree(), tree(), tpe())(p)
        case Tags.IsInstanceOf    => IsInstanceOf(tree(), tpe())(p)
        case Tags.ClassOf         => ClassOf(tpe())(p)
        case Tags.Throw           => Throw(tree())(p)
        case Tags.TryCatch        => TryCatch(tree(), list(CatchCase(option(str()), tpe(), tree())), tpe())(p)
        case Tags.TryFinally      => TryFinally(tree(), tree(), tpe())(p)
        case Tags.Match           => Match(tree(), list((trees(), tree())), tree(), tpe())(p)
        case Tags.Closure         => Closure(str(), method(), str(), method(), bool(), trees())(p)
        case Tags.Unsupported     => Unsupported(str(), tpe())(p)
        case other                => throw IRFormatException(s"unknown tree tag $other")
  end Reader
end Serialization
