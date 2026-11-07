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
  private val directives = LineDirectives(ctx.lineDirectives)

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
      line(s"var ${Mangling.staticField(name, f.name)} ${ctx.fieldGoType(f)}")
    if analyzer.staticInit.contains(name) then emitStaticInitializer()
    // Last: the code with Scala positions (see LineDirectives).
    for m <- cls.methods if analyzer.reachedMethods.contains((name, m.name)) do emitImplementation(m)
    directives.end().foreach(line(_))
    if sb.isEmpty then "" else s"// ${kindString} $name\n\n$sb"

  private def kindString = cls.kind match
    case ClassKind.Class       => if cls.isAbstract then "abstract class" else "class"
    case ClassKind.ModuleClass => "module class"
    case ClassKind.Interface   => "trait"

  private def irParents: List[ClassName] = (cls.superClass.toList ++ cls.interfaces).filter(program.contains)

  private def emitTypes(): Unit =
    if !isInterface then
      line(s"type $struct struct {")
      // Root classes embed the object header (the monitor); subclasses inherit it.
      cls.superClass.filter(program.contains) match
        case Some(s) => line(s"\t${Mangling.struct(s)}")
        case None    => line("\trt.ObjectHeader")
      for f <- cls.fields if !f.isStatic do line(s"\t${Mangling.field(f.name)} ${ctx.fieldGoType(f)}")
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
    line(s"func (this *$struct) ScalaClass() *rt.Class { return ${ctx.classLiteral(name)} }")
    ctx.interop.exportedMethods(cls, struct).foreach(line(_))
    if ctx.libraryMode then ctx.exports.structMethods(cls, struct).foreach(line(_))
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

  /** `Mod_C()`: the module instance, constructed on first use under an init lock (`rt.InitLock`),
   *  with the JVM's class initialization semantics: other goroutines wait for the construction,
   *  and code that refers to the module during its own construction (in the same goroutine) gets
   *  the instance, which is published before its constructor runs, as Scala.js does.
   */
  private def emitModuleAccessor(): Unit =
    val v = Mangling.moduleVar(name)
    val lock = Mangling.moduleInitLock(name)
    val ctor = MethodName(MethodName.ConstructorSimpleName, Nil, Type.Void)
    line(s"var $v *$struct")
    line(s"var $lock rt.InitLock")
    line()
    line(s"func ${Mangling.module(name)}() ${ctx.goType(Type.Class(name))} {")
    line(s"\tif !$lock.Done() {")
    line(s"\t\t$lock.Run(${FunctionEmitter.goStringLiteral(name)}, func() {")
    if analyzer.staticInit.contains(name) then line(s"\t\t\t${Mangling.clinit(name)}()")
    line(s"\t\t\t$v = &$struct{}")
    line(s"\t\t\t${Mangling.impl(name, ctor)}($v)")
    line("\t\t})")
    line("\t}")
    line(s"\treturn $v")
    line("}")
    line()

  /** `Clinit_C()`: runs the static initializer once, under an init lock (see the module accessor). */
  private def emitStaticInitializer(): Unit =
    val lock = Mangling.clinitLock(name)
    val init = program.clinit(name).get
    line(s"var $lock rt.InitLock")
    line()
    line(s"func ${Mangling.clinit(name)}() {")
    line(s"\tif !$lock.Done() {")
    line(s"\t\t$lock.Run(${FunctionEmitter.goStringLiteral(name)}, ${Mangling.impl(name, init.name)})")
    line("\t}")
    line("}")
    line()

  private def emitImplementation(m: MethodDef): Unit =
    val thisParam = if m.isStatic then Nil else List(s"this ${ctx.goType(Type.Class(name))}")
    val allParams = (thisParam ++ paramDecls(m)).mkString(", ")
    val header = s"func ${Mangling.impl(name, m.name)}($allParams)${resultDecl(m.name)} {"
    val body = FunctionEmitter(ctx, cls, m).emitBody(analyzer.body(name, m.name))
    directives.function(header, m.pos, body).foreach(line(_))
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
