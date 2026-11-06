package scalago.linker

import java.nio.file.{Files, Path}
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*
import scala.util.Using

import scalago.ir.{ClassDef, Serialization}

/** Reads every `.goir` file found on a classpath (directories and jars). */
object IRLoader:
  def load(classpath: Seq[Path]): Seq[ClassDef] =
    classpath.flatMap { entry =>
      if Files.isDirectory(entry) then loadDirectory(entry)
      else if Files.isRegularFile(entry) && entry.toString.endsWith(".jar") then loadJar(entry)
      else Nil
    }

  private def isIRFile(name: String): Boolean = name.endsWith(Serialization.FileExtension)

  private def loadDirectory(dir: Path): Seq[ClassDef] =
    Using.resource(Files.walk(dir)) { files =>
      files.iterator.asScala
        .filter(p => Files.isRegularFile(p) && isIRFile(p.getFileName.toString))
        .map(p => Serialization.deserialize(Files.readAllBytes(p)))
        .toList
    }

  private def loadJar(jar: Path): Seq[ClassDef] =
    Using.resource(ZipFile(jar.toFile)) { zip =>
      zip.entries.asScala
        .filter(e => !e.isDirectory && isIRFile(e.getName))
        .map(e => Using.resource(zip.getInputStream(e))(in => Serialization.deserialize(in.readAllBytes())))
        .toList
    }
