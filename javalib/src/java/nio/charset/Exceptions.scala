package java.nio.charset

class CharacterCodingException() extends java.io.IOException()

class MalformedInputException(inputLength: Int) extends CharacterCodingException():
  def getInputLength(): Int = inputLength
  override def getMessage(): String = "Input length = " + inputLength

class UnmappableCharacterException(inputLength: Int) extends CharacterCodingException():
  def getInputLength(): Int = inputLength
  override def getMessage(): String = "Input length = " + inputLength

class UnsupportedCharsetException(charsetName: String) extends IllegalArgumentException(charsetName):
  def getCharsetName(): String = charsetName

class IllegalCharsetNameException(charsetName: String) extends IllegalArgumentException(charsetName):
  def getCharsetName(): String = charsetName

class CoderMalfunctionError(cause: Exception) extends Error(cause)
