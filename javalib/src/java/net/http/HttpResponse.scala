package java.net.http

import java.io.{ByteArrayOutputStream, InputStream, IOException}
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.{OpenOption, Path, StandardOpenOption}
import java.util.{List as JList, Optional}
import java.util.concurrent.{CompletableFuture, CompletionStage, Flow, LinkedBlockingQueue}
import java.util.function.{Consumer, Function}
import java.util.stream.Stream

trait HttpResponse[T <: AnyRef]:
  def statusCode(): Int
  def request(): HttpRequest
  def previousResponse(): Optional[HttpResponse[T]]
  def headers(): HttpHeaders
  def body(): T
  def uri(): URI
  def version(): HttpClient.Version

object HttpResponse:
  trait ResponseInfo:
    def statusCode(): Int
    def headers(): HttpHeaders
    def version(): HttpClient.Version

  /** Makes the subscriber that receives a response's body, once the status and headers are in. */
  trait BodyHandler[T <: AnyRef]:
    def apply(responseInfo: ResponseInfo): BodySubscriber[T]

  /** Receives a response's body as lists of byte buffers, and makes its value. */
  trait BodySubscriber[T <: AnyRef] extends Flow.Subscriber[JList[ByteBuffer]]:
    def getBody(): CompletionStage[T]

  /** Push promises are an HTTP/2 feature, and requests are HTTP/1.1: none ever arrive. */
  trait PushPromiseHandler[T <: AnyRef]:
    def applyPushPromise(initiatingRequest: HttpRequest, pushPromiseRequest: HttpRequest,
        acceptor: Function[BodyHandler[T], CompletableFuture[HttpResponse[T]]]): Unit

  object BodyHandlers:
    def ofString(): BodyHandler[String] = info => BodySubscribers.ofString(charsetFrom(info.headers()))
    def ofString(charset: Charset): BodyHandler[String] =
      java.util.Objects.requireNonNull(charset)
      _ => BodySubscribers.ofString(charset)
    def ofByteArray(): BodyHandler[Array[Byte]] = _ => BodySubscribers.ofByteArray()
    def ofInputStream(): BodyHandler[InputStream] = _ => BodySubscribers.ofInputStream()
    def ofLines(): BodyHandler[Stream[String]] = info => BodySubscribers.ofLines(charsetFrom(info.headers()))
    def discarding(): BodyHandler[Void] = _ => BodySubscribers.discarding()
    def replacing[U <: AnyRef](value: U): BodyHandler[U] = _ => BodySubscribers.replacing(value)
    def ofFile(file: Path): BodyHandler[Path] = _ => BodySubscribers.ofFile(file)
    def ofFile(file: Path, openOptions: Array[OpenOption]): BodyHandler[Path] =
      _ => BodySubscribers.ofFile(file, openOptions)
    def ofByteArrayConsumer(consumer: Consumer[Optional[Array[Byte]]]): BodyHandler[Void] =
      _ => BodySubscribers.ofByteArrayConsumer(consumer)
    def fromSubscriber(subscriber: Flow.Subscriber[? >: JList[ByteBuffer]]): BodyHandler[Void] =
      _ => BodySubscribers.fromSubscriber(subscriber)
    def fromSubscriber[S <: Flow.Subscriber[? >: JList[ByteBuffer]], T <: AnyRef](subscriber: S, finisher: Function[? >: S, ? <: T])
        : BodyHandler[T] = _ => BodySubscribers.fromSubscriber(subscriber, finisher)
    def buffering[T <: AnyRef](downstreamHandler: BodyHandler[T], bufferSize: Int): BodyHandler[T] =
      if bufferSize <= 0 then throw new IllegalArgumentException("must be greater than 0")
      downstreamHandler

    /** The charset of the Content-Type header, or UTF-8. */
    private def charsetFrom(headers: HttpHeaders): Charset =
      val contentType = headers.firstValue("Content-Type").orElse("")
      val at = contentType.toLowerCase(java.util.Locale.ROOT).indexOf("charset=")
      if at < 0 then StandardCharsets.UTF_8
      else
        var name = contentType.substring(at + 8).trim()
        val end = name.indexOf(';')
        if end >= 0 then name = name.substring(0, end).trim()
        name = name.stripPrefix("\"").stripSuffix("\"")
        try Charset.forName(name)
        catch case _: Exception => StandardCharsets.UTF_8

  object BodySubscribers:
    def ofString(charset: Charset): BodySubscriber[String] = new BytesSubscriber(b => new String(b, charset))
    def ofByteArray(): BodySubscriber[Array[Byte]] = new BytesSubscriber(b => b)
    def ofInputStream(): BodySubscriber[InputStream] = new StreamSubscriber()

    def ofLines(charset: Charset): BodySubscriber[Stream[String]] =
      mapping(ofInputStream(), in => new java.io.BufferedReader(new java.io.InputStreamReader(in, charset)).lines())

    def discarding(): BodySubscriber[Void] = replacing(null.asInstanceOf[Void])

    def replacing[U <: AnyRef](value: U): BodySubscriber[U] = new ChunkSubscriber[U](_ => (), () => value)

    def ofFile(file: Path): BodySubscriber[Path] =
      ofFile(file, Array(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))

    def ofFile(file: Path, openOptions: Array[OpenOption]): BodySubscriber[Path] =
      val out = java.nio.file.Files.newOutputStream(file, openOptions)
      new ChunkSubscriber[Path](b => out.write(b), () => { out.close(); file }, () => out.close())

    def ofByteArrayConsumer(consumer: Consumer[Optional[Array[Byte]]]): BodySubscriber[Void] =
      new ChunkSubscriber[Void](b => consumer.accept(Optional.of(b)), () => {
        consumer.accept(Optional.empty())
        null.asInstanceOf[Void]
      })

    def fromSubscriber(subscriber: Flow.Subscriber[? >: JList[ByteBuffer]]): BodySubscriber[Void] =
      fromSubscriber(subscriber, (_: Flow.Subscriber[? >: JList[ByteBuffer]]) => null.asInstanceOf[Void])

    def fromSubscriber[S <: Flow.Subscriber[? >: JList[ByteBuffer]], T <: AnyRef](subscriber: S, finisher: Function[? >: S, ? <: T])
        : BodySubscriber[T] =
      val s = subscriber.asInstanceOf[Flow.Subscriber[JList[ByteBuffer]]]
      val f = finisher.asInstanceOf[Function[S, T]]
      new BodySubscriber[T]:
        private val body = new CompletableFuture[T]()
        def getBody(): CompletionStage[T] = body
        def onSubscribe(subscription: Flow.Subscription): Unit = s.onSubscribe(subscription)
        def onNext(item: JList[ByteBuffer]): Unit = s.onNext(item)
        def onError(throwable: Throwable): Unit =
          s.onError(throwable)
          body.completeExceptionally(throwable)
        def onComplete(): Unit =
          s.onComplete()
          try body.complete(f.apply(subscriber))
          catch case e: Throwable => body.completeExceptionally(e)

    def mapping[T <: AnyRef, U <: AnyRef](upstream: BodySubscriber[T], mapper: Function[? >: T, ? <: U]): BodySubscriber[U] =
      new BodySubscriber[U]:
        def getBody(): CompletionStage[U] =
          upstream.getBody().thenApply[AnyRef](t => mapper.apply(t).asInstanceOf[AnyRef]).asInstanceOf[CompletionStage[U]]
        def onSubscribe(subscription: Flow.Subscription): Unit = upstream.onSubscribe(subscription)
        def onNext(item: JList[ByteBuffer]): Unit = upstream.onNext(item)
        def onError(throwable: Throwable): Unit = upstream.onError(throwable)
        def onComplete(): Unit = upstream.onComplete()

    def buffering[T <: AnyRef](downstream: BodySubscriber[T], bufferSize: Int): BodySubscriber[T] =
      if bufferSize <= 0 then throw new IllegalArgumentException("must be greater than 0")
      downstream

    private def bytesOf(item: JList[ByteBuffer]): Array[Byte] =
      val out = new ByteArrayOutputStream()
      item.forEach { buf =>
        val b = new Array[Byte](buf.remaining())
        buf.get(b)
        out.write(b)
      }
      out.toByteArray()

    /** Hands each chunk to `chunk`, and completes with `finish()`. */
    private class ChunkSubscriber[T <: AnyRef](chunk: Array[Byte] => Unit, finish: () => T, abort: () => Unit = () => ())
        extends BodySubscriber[T]:
      private val body = new CompletableFuture[T]()
      def getBody(): CompletionStage[T] = body
      def onSubscribe(subscription: Flow.Subscription): Unit = subscription.request(Long.MaxValue)
      def onNext(item: JList[ByteBuffer]): Unit =
        try chunk(bytesOf(item))
        catch case e: Throwable => body.completeExceptionally(e)
      def onError(throwable: Throwable): Unit =
        try abort()
        finally body.completeExceptionally(throwable)
      def onComplete(): Unit =
        if !body.isDone() then
          try body.complete(finish())
          catch case e: Throwable => body.completeExceptionally(e)

    /** Collects the whole body, then completes with `finish(bytes)`. */
    private final class BytesSubscriber[T <: AnyRef](finish: Array[Byte] => T) extends BodySubscriber[T]:
      private val out = new ByteArrayOutputStream()
      private val inner = new ChunkSubscriber[T](b => out.write(b), () => finish(out.toByteArray()))
      def getBody(): CompletionStage[T] = inner.getBody()
      def onSubscribe(subscription: Flow.Subscription): Unit = inner.onSubscribe(subscription)
      def onNext(item: JList[ByteBuffer]): Unit = inner.onNext(item)
      def onError(throwable: Throwable): Unit = inner.onError(throwable)
      def onComplete(): Unit = inner.onComplete()

    /** An InputStream over the body, completed as soon as subscribed: reading requests the
     *  body's chunks one at a time, and closing cancels the subscription.
     */
    private final class StreamSubscriber extends BodySubscriber[InputStream]:
      private val End = new Array[Byte](0)
      private val chunks = new LinkedBlockingQueue[Object]()
      @volatile private var subscription: Flow.Subscription = null
      private val body = new CompletableFuture[InputStream]()
      def getBody(): CompletionStage[InputStream] = body

      def onSubscribe(s: Flow.Subscription): Unit =
        subscription = s
        body.complete(stream)
      def onNext(item: JList[ByteBuffer]): Unit = chunks.add(bytesOf(item))
      def onError(throwable: Throwable): Unit = chunks.add(throwable)
      def onComplete(): Unit = chunks.add(End)

      private object stream extends InputStream:
        private var current: Array[Byte] = null
        private var pos = 0
        private var outstanding = false // a chunk has been requested and not received
        private var finished: Object = null // End, or the failure
        @volatile private var closed = false

        /** Whether there is a chunk to read from (false at the end). */
        private def fill(): Boolean =
          while (current == null || pos == current.length) && finished == null do
            if closed then throw new IOException("closed")
            if !outstanding && chunks.isEmpty() then
              outstanding = true
              subscription.request(1)
            chunks.take() match
              case b: Array[Byte] if b eq End => finished = End
              case b: Array[Byte] =>
                outstanding = false
                current = b
                pos = 0
              case e: Throwable => finished = e
          finished match
            case e: IOException => throw e
            case e: Throwable   => throw new IOException(e)
            case _              => current != null && pos < current.length

        override def read(): Int = synchronized {
          if !fill() then -1
          else
            pos += 1
            current(pos - 1) & 0xff
        }

        override def read(b: Array[Byte], off: Int, len: Int): Int = synchronized {
          java.util.Objects.checkFromIndexSize(off, len, b.length)
          if len == 0 then 0
          else if !fill() then -1
          else
            val n = len.min(current.length - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            n
        }

        override def available(): Int = synchronized {
          if current == null then 0 else current.length - pos
        }

        override def close(): Unit =
          if !closed then
            closed = true
            if subscription != null then subscription.cancel()
            chunks.add(End)
