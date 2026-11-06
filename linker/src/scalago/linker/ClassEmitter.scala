package scalago.linker

import scalago.ir.*

/** Emits the Go declarations for one class (see [[Mangling]] for the naming scheme).
 *
 *  Object model: a class is a struct embedding its superclass's struct, and references to it
 *  have the type of an interface listing its virtual methods, embedding the interfaces of its
 *  superclass and traits. Method bodies are free functions taking `this` as that interface, so
 *  calls on `this` stay virtual even in inherited code. Each instantiated class gets a
 *  forwarding Go method for every called virtual method in its vtable (including trait default
 *  methods), which is what makes the struct satisfy its interfaces.
 *
 *  Only what the [[Analyzer]] found reachable is emitted.
 */
object ClassEmitter:
  /** java.lang.Object's own implementations (not the dynamically dispatching `rt.ToString` etc.). */
  val objectDefaults: Map[MethodName, String] = Map(
    Natives.parseMethod("toString()Ljava.lang.String;")  -> "rt.ObjectDefaultToString",
    Natives.parseMethod("equals(Ljava.lang.Object;)Z")   -> "rt.ObjectDefaultEquals",
    Natives.parseMethod("hashCode()I")                   -> "rt.IdentityHashCode"
  )

final class ClassEmitter(ctx: LinkContext, cls: ClassDef):
  private val program = ctx.program
  private val analyzer = ctx.analyzer
  private val sb = StringBuilder()
  private def line(s: String = ""): Unit = sb ++= s ++= "\n"

  private val name = cls.name
  private val struct = Mangling.struct(name)
  private val iface = Mangling.iface(name)
  private val isInterface = cls.kind == ClassKind.Interface
  private val isInstantiated = analyzer.instantiated.contains(name)

  private def calledVtable: List[(MethodName, ClassName)] =
    program.vtable(name).toList.filter((m, _) => analyzer.calledNames.contains(m)).sortBy(_._1.descriptor)

  /** The Go code for this class; empty if nothing of it is reachable. */
  def emit(): String =
    if ctx.declared(name) then emitTypes()
    if isInstantiated && program.isInstantiable(cls) then emitInstanceMembers()
    if cls.kind == ClassKind.ModuleClass && isInstantiated then emitModuleAccessor()
    for f <- cls.fields if f.isStatic && analyzer.usedStaticFields.contains((name, f.name)) do
      line(s"var ${Mangling.staticField(name, f.name)} ${ctx.goType(f.tpe)}")
    if analyzer.staticInit.contains(name) then emitStaticInitializer()
    for m <- cls.methods if analyzer.reachedMethods.contains((name, m.name)) do emitImplementation(m)
    if sb.isEmpty then "" else s"// ${kindString} $name\n\n$sb"

  private def kindString = cls.kind match
    case ClassKind.Class       => if cls.isAbstract then "abstract class" else "class"
    case ClassKind.ModuleClass => "module class"
    case ClassKind.Interface   => "trait"

  private def irParents: List[ClassName] = (cls.superClass.toList ++ cls.interfaces).filter(program.contains)

  private def emitTypes(): Unit =
    if !isInterface then
      line(s"type $struct struct {")
      cls.superClass.filter(program.contains).foreach(s => line(s"\t${Mangling.struct(s)}"))
      for f <- cls.fields if !f.isStatic do line(s"\t${Mangling.field(f.name)} ${ctx.goType(f.tpe)}")
      line("}")
      line()
    line(s"type $iface interface {")
    irParents.foreach(p => line(s"\t${Mangling.iface(p)}"))
    if isInterface then line(s"\t${Mangling.marker(name)}()")
    else line(s"\t${Mangling.accessor(name)}() *$struct")
    for (m, _) <- calledVtable do line(s"\t${Mangling.virtualMethod(m)}${signature(m)}")
    line("}")
    line()
    if !isInterface then
      line(s"func (this *$struct) ${Mangling.accessor(name)}() *$struct { return this }")
      line()

  private def emitInstanceMembers(): Unit =
    for (m, implClass) <- calledVtable do
      val args = m.params.indices.map(i => s"a$i").toList
      val body = program.findMethod(implClass, m).filterNot(_.isAbstract) match
        case Some(_) =>
          val call = s"${Mangling.impl(implClass, m)}(${("this" :: args).mkString(", ")})"
          if ctx.goType(m.result).isEmpty then call else s"return $call"
        case None =>
          // java.lang.Object's methods implement interface methods with the same signature.
          ClassEmitter.objectDefaults.get(m) match
            case Some(goFn) => s"return $goFn(${("this" :: args).mkString(", ")})"
            case None =>
              s"""rt.ThrowKind(rt.RuntimeError, "AbstractMethodError: $name.${m.simple}"); panic("unreachable")"""
      line(s"func (this *$struct) ${Mangling.virtualMethod(m)}${signature(m, args)} { $body }")
    for i <- program.allInterfaces(name) do line(s"func (this *$struct) ${Mangling.marker(i)}() {}")
    line(s"func (this *$struct) ScalaClassName() string { return ${FunctionEmitter.goStringLiteral(name)} }")
    line()
    for ctor <- cls.methods if ctor.name.isConstructor && analyzer.reachedMethods.contains((name, ctor.name)) do
      val args = ctor.params.map(p => Mangling.local(p.name))
      line(s"func ${Mangling.newInstance(name, ctor.name)}(${paramDecls(ctor).mkString(", ")}) *$struct {")
      if analyzer.staticInit.contains(name) then line(s"\t${Mangling.clinit(name)}()")
      line(s"\tthis := &$struct{}")
      line(s"\t${Mangling.impl(name, ctor.name)}(${("this" :: args).mkString(", ")})")
      line("\treturn this")
      line("}")
      line()

  private def emitModuleAccessor(): Unit =
    val v = Mangling.moduleVar(name)
    val ctor = MethodName(MethodName.ConstructorSimpleName, Nil, Type.Void)
    // The instance is published before its constructor runs (as Scala.js does), so a module that
    // refers to itself during initialization sees the instance. Not yet safe for concurrent first access.
    line(s"var $v *$struct")
    line()
    line(s"func ${Mangling.module(name)}() ${ctx.goType(Type.Class(name))} {")
    line(s"\tif $v == nil {")
    if analyzer.staticInit.contains(name) then line(s"\t\t${Mangling.clinit(name)}()")
    line(s"\t\t$v = &$struct{}")
    line(s"\t\t${Mangling.impl(name, ctor)}($v)")
    line("\t}")
    line(s"\treturn $v")
    line("}")
    line()

  private def emitStaticInitializer(): Unit =
    val done = Mangling.clinitDone(name)
    val init = program.clinit(name).get
    line(s"var $done bool")
    line()
    line(s"func ${Mangling.clinit(name)}() {")
    line(s"\tif !$done {")
    line(s"\t\t$done = true")
    line(s"\t\t${Mangling.impl(name, init.name)}()")
    line("\t}")
    line("}")
    line()

  private def emitImplementation(m: MethodDef): Unit =
    val thisParam = if m.isStatic then Nil else List(s"this ${ctx.goType(Type.Class(name))}")
    val allParams = (thisParam ++ paramDecls(m)).mkString(", ")
    line(s"func ${Mangling.impl(name, m.name)}($allParams)${resultDecl(m.name)} {")
    val body = analyzer.body(name, m.name)
    FunctionEmitter(ctx, cls, m).emitBody(body).foreach(l => line("\t" + l))
    line("}")
    line()

  private def paramDecls(m: MethodDef): List[String] =
    m.params.map(p => s"${Mangling.local(p.name)} ${ctx.goType(p.tpe)}")

  private def resultDecl(m: MethodName): String =
    val t = ctx.goType(m.result)
    if t.isEmpty then "" else s" $t"

  private def signature(m: MethodName, paramNames: List[String] = Nil): String =
    val params = m.params.zipWithIndex.map { (t, i) =>
      if paramNames.isEmpty then ctx.goType(t) else s"${paramNames(i)} ${ctx.goType(t)}"
    }
    s"(${params.mkString(", ")})${resultDecl(m)}"
