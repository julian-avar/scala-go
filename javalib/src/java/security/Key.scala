package java.security

trait Key extends java.io.Serializable:
  def getAlgorithm(): String
  def getFormat(): String
  def getEncoded(): Array[Byte]
