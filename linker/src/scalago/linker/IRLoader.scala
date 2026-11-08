package scalago.linker

import java.nio.file.{Files, Path}
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scalago.ir.{ClassDef, Serialization}

/** Reads the `.goir` files found on a classpath (directories and jars).
 *
 *  The plugin writes each class's IR next to its class file. Incremental compilation deletes the
 *  class files of classes that no longer exist, but not their IR, so where an entry has class
 *  files, IR without its class file is stale and ignored. Entries with IR only (the Scala
 *  library's, dependencies compiled from TASTy) are read whole.
 */
object IRLoader:
  def load(classpath: Seq[Path]): Seq[ClassDef] =
    classpath.flatMap { entry =>
      if Files.isDirectory(entry) then loadDirectory(entry)
      else if Files.isRegularFile(entry) && entry.toString.endsWith(".jar") then loadJar(entry)
      else Nil
    }

  private def isIRFile(name: String): Boolean = name.endsWith(Serialization.FileExtension)

  /** The IR files among `files` (relative names) that are not stale. */
  private def current(files: Seq[String]): Seq[String] =
    val classFiles = files.filter(_.endsWith(".class")).toSet
    files.filter(f => isIRFile(f) && (classFiles.isEmpty || classFiles(f.stripSuffix(Serialization.FileExtension) + ".class")))

  private def loadDirectory(dir: Path): Seq[ClassDef] =
    val files = Using.resource(Files.walk(dir)) { paths =>
      paths.iterator.asScala.filter(Files.isRegularFile(_)).map(p => dir.relativize(p).toString).toList
    }
    current(files).map(f => Serialization.deserialize(Files.readAllBytes(dir.resolve(f))))

  private def loadJar(jar: Path): Seq[ClassDef] =
    Using.resource(ZipFile(jar.toFile)) { zip =>
      val names = zip.entries.asScala.filter(!_.isDirectory).map(_.getName).toList
      current(names).map(n => Using.resource(zip.getInputStream(zip.getEntry(n)))(in => Serialization.deserialize(in.readAllBytes())))
    }
