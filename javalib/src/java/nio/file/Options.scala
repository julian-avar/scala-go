package java.nio.file

import scala.annotation.static

trait OpenOption
trait CopyOption

final class StandardOpenOption private (name: String, ordinal: Int)
    extends scalago.javalib.lang.Enum[StandardOpenOption](name, ordinal)
    with OpenOption

object StandardOpenOption:
  @static val READ: StandardOpenOption = new StandardOpenOption("READ", 0)
  @static val WRITE: StandardOpenOption = new StandardOpenOption("WRITE", 1)
  @static val APPEND: StandardOpenOption = new StandardOpenOption("APPEND", 2)
  @static val TRUNCATE_EXISTING: StandardOpenOption = new StandardOpenOption("TRUNCATE_EXISTING", 3)
  @static val CREATE: StandardOpenOption = new StandardOpenOption("CREATE", 4)
  @static val CREATE_NEW: StandardOpenOption = new StandardOpenOption("CREATE_NEW", 5)
  @static val DELETE_ON_CLOSE: StandardOpenOption = new StandardOpenOption("DELETE_ON_CLOSE", 6)
  @static val SPARSE: StandardOpenOption = new StandardOpenOption("SPARSE", 7)
  @static val SYNC: StandardOpenOption = new StandardOpenOption("SYNC", 8)
  @static val DSYNC: StandardOpenOption = new StandardOpenOption("DSYNC", 9)

  @static def values(): Array[StandardOpenOption] =
    Array(READ, WRITE, APPEND, TRUNCATE_EXISTING, CREATE, CREATE_NEW, DELETE_ON_CLOSE, SPARSE, SYNC, DSYNC)

  @static def valueOf(name: String): StandardOpenOption =
    values().find(_.name() == name).getOrElse(
      throw new IllegalArgumentException("No enum constant java.nio.file.StandardOpenOption." + name))

final class StandardCopyOption private (name: String, ordinal: Int)
    extends scalago.javalib.lang.Enum[StandardCopyOption](name, ordinal)
    with CopyOption

object StandardCopyOption:
  @static val REPLACE_EXISTING: StandardCopyOption = new StandardCopyOption("REPLACE_EXISTING", 0)
  @static val COPY_ATTRIBUTES: StandardCopyOption = new StandardCopyOption("COPY_ATTRIBUTES", 1)
  @static val ATOMIC_MOVE: StandardCopyOption = new StandardCopyOption("ATOMIC_MOVE", 2)

  @static def values(): Array[StandardCopyOption] = Array(REPLACE_EXISTING, COPY_ATTRIBUTES, ATOMIC_MOVE)

  @static def valueOf(name: String): StandardCopyOption =
    values().find(_.name() == name).getOrElse(
      throw new IllegalArgumentException("No enum constant java.nio.file.StandardCopyOption." + name))

final class LinkOption private (name: String, ordinal: Int)
    extends scalago.javalib.lang.Enum[LinkOption](name, ordinal)
    with OpenOption
    with CopyOption

object LinkOption:
  @static val NOFOLLOW_LINKS: LinkOption = new LinkOption("NOFOLLOW_LINKS", 0)

  @static def values(): Array[LinkOption] = Array(NOFOLLOW_LINKS)

  @static def valueOf(name: String): LinkOption =
    if name == "NOFOLLOW_LINKS" then NOFOLLOW_LINKS
    else throw new IllegalArgumentException("No enum constant java.nio.file.LinkOption." + name)

final class FileVisitOption private (name: String, ordinal: Int)
    extends scalago.javalib.lang.Enum[FileVisitOption](name, ordinal)

object FileVisitOption:
  @static val FOLLOW_LINKS: FileVisitOption = new FileVisitOption("FOLLOW_LINKS", 0)

  @static def values(): Array[FileVisitOption] = Array(FOLLOW_LINKS)

  @static def valueOf(name: String): FileVisitOption =
    if name == "FOLLOW_LINKS" then FOLLOW_LINKS
    else throw new IllegalArgumentException("No enum constant java.nio.file.FileVisitOption." + name)

final class FileVisitResult private (name: String, ordinal: Int)
    extends scalago.javalib.lang.Enum[FileVisitResult](name, ordinal)

object FileVisitResult:
  @static val CONTINUE: FileVisitResult = new FileVisitResult("CONTINUE", 0)
  @static val TERMINATE: FileVisitResult = new FileVisitResult("TERMINATE", 1)
  @static val SKIP_SUBTREE: FileVisitResult = new FileVisitResult("SKIP_SUBTREE", 2)
  @static val SKIP_SIBLINGS: FileVisitResult = new FileVisitResult("SKIP_SIBLINGS", 3)

  @static def values(): Array[FileVisitResult] = Array(CONTINUE, TERMINATE, SKIP_SUBTREE, SKIP_SIBLINGS)

  @static def valueOf(name: String): FileVisitResult =
    values().find(_.name() == name).getOrElse(
      throw new IllegalArgumentException("No enum constant java.nio.file.FileVisitResult." + name))
