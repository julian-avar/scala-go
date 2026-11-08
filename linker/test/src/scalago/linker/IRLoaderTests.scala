package scalago.linker

import java.nio.file.{Files, Path}

import scalago.ir.*

class IRLoaderTests extends munit.FunSuite:
  private def classDef(name: String) =
    ClassDef(name, ClassKind.Class, isAbstract = false, Some(ClassName.Object), Nil, Nil, Nil, Position.NoPosition)

  private def write(dir: Path, file: String, name: String): Unit =
    Files.write(dir.resolve(file), Serialization.serialize(classDef(name)))

  test("IR without its class file is stale where an entry has class files") {
    val dir = Files.createTempDirectory("scalago-irloader-")
    try
      write(dir, "Kept.goir", "Kept")
      Files.write(dir.resolve("Kept.class"), Array[Byte]())
      write(dir, "Removed.goir", "Removed") // a class that incremental compilation deleted
      // The IR of a renamed class sits next to its class file and keeps its own name.
      write(dir, "Renamed.goir", "java.lang.Renamed")
      Files.write(dir.resolve("Renamed.class"), Array[Byte]())
      assertEquals(IRLoader.load(Seq(dir)).map(_.name).toSet, Set("Kept", "java.lang.Renamed"))
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }

  test("entries with IR only are read whole") {
    val dir = Files.createTempDirectory("scalago-irloader-")
    try
      write(dir, "A.goir", "A")
      write(dir, "B.goir", "B")
      assertEquals(IRLoader.load(Seq(dir)).map(_.name).toSet, Set("A", "B"))
    finally Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
  }
