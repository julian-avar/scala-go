import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch

/** TCP sockets on the loopback interface: echo, timeouts, closing from another thread, errors. */
object Main:
  def attempt(label: String)(body: => Any): Unit =
    try println(s"$label: ${body}")
    catch case e: Exception => println(s"$label: ${e.getClass.getName}: ${e.getMessage}")

  def startThread(body: => Unit): Thread =
    val t = new Thread(() => body)
    t.start()
    t

  def main(args: Array[String]): Unit =
    echo()
    timeouts()
    closing()
    errors()
    bulk()

  def echo(): Unit =
    println("-- echo")
    val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val port = server.getLocalPort
    println(s"bound ${server.isBound} ${server.getInetAddress} ${port > 0} ${server.getLocalSocketAddress == new InetSocketAddress(InetAddress.getLoopbackAddress, port)}")
    println(server.toString.replace(port.toString, "PORT"))
    val serverLog = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val t = startThread {
      val s = server.accept()
      serverLog.add(s"accepted from ${s.getInetAddress} ${s.getLocalPort == port}")
      val in = new BufferedReader(new InputStreamReader(s.getInputStream, UTF_8))
      val out = new PrintWriter(new OutputStreamWriter(s.getOutputStream, UTF_8), true)
      var line = in.readLine()
      while line != null do
        out.println("echo: " + line)
        line = in.readLine()
      serverLog.add("server: end of input")
      s.close()
    }
    val c = new Socket("localhost", port)
    val local = c.getLocalPort
    println(s"connected ${c.isConnected} ${c.isBound} ${c.isClosed} ${c.getInetAddress} ${c.getPort == port}")
    println(c.toString.replace(s"port=$port", "port=PORT").replace(s"localport=$local", "localport=LOCAL"))
    println(s"remote ${c.getRemoteSocketAddress.toString.replace(port.toString, "PORT")} local ${c.getLocalAddress}")
    println(s"options ${c.getTcpNoDelay} ${c.getKeepAlive} ${c.getSoTimeout} ${c.getSoLinger}")
    c.setTcpNoDelay(true)
    c.setKeepAlive(true)
    c.setSoLinger(true, 5)
    println(s"options ${c.getTcpNoDelay} ${c.getKeepAlive} ${c.getSoLinger} ${c.getOption(StandardSocketOptions.TCP_NODELAY)}")
    val in = new BufferedReader(new InputStreamReader(c.getInputStream, UTF_8))
    val out = new PrintWriter(new OutputStreamWriter(c.getOutputStream, UTF_8), true)
    for msg <- List("hello", "Grüße, 世界 🌍", "") do
      out.println(msg)
      println(in.readLine())
    c.shutdownOutput()
    println(s"shutdown ${c.isOutputShutdown} ${c.isInputShutdown}")
    attempt("write after shutdown")(c.getOutputStream.write(1))
    println(s"end ${in.readLine()} ${c.getInputStream.read()}")
    t.join()
    serverLog.forEach(println)
    c.close()
    println(s"closed ${c.isClosed} ${c.isConnected}")
    attempt("stream of closed socket")(c.getInputStream)
    attempt("read of closed socket")(in.read())
    server.close()
    println(s"server closed ${server.isClosed}")

  def timeouts(): Unit =
    println("-- timeouts")
    val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    server.setSoTimeout(50)
    attempt("accept")(server.accept())
    server.setSoTimeout(0)
    val ready = new CountDownLatch(1)
    val t = startThread {
      val s = server.accept()
      ready.await()
      s.getOutputStream.write("x".getBytes)
      s.getOutputStream.flush()
      Thread.sleep(50)
      s.close()
    }
    val c = new Socket(InetAddress.getLoopbackAddress, server.getLocalPort)
    c.setSoTimeout(100)
    println(s"soTimeout ${c.getSoTimeout}")
    attempt("read")(c.getInputStream.read())
    // The socket is still usable after a timeout.
    ready.countDown()
    attempt("read again")(c.getInputStream.read().toChar)
    attempt("read to the end")(c.getInputStream.read())
    t.join()
    c.close()
    server.close()

  def closing(): Unit =
    println("-- closing from another thread")
    val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val accepting = startThread(attempt("blocked accept")(server.accept()))
    Thread.sleep(100)
    server.close()
    accepting.join()
    attempt("accept after close")(server.accept())

    val server2 = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val serverSide = startThread {
      val s = server2.accept()
      Thread.sleep(300)
      s.close()
    }
    val c = new Socket(InetAddress.getLoopbackAddress, server2.getLocalPort)
    val reading = startThread(attempt("blocked read")(c.getInputStream.read()))
    Thread.sleep(100)
    c.close()
    reading.join()
    serverSide.join()
    server2.close()

  def errors(): Unit =
    println("-- errors")
    val s = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val port = s.getLocalPort
    attempt("bind in use")(new ServerSocket(port, 50, InetAddress.getLoopbackAddress))
    s.close()
    attempt("refused")(new Socket(InetAddress.getLoopbackAddress, port))
    val unconnected = new Socket()
    println(s"unconnected ${unconnected} ${unconnected.isConnected} ${unconnected.getPort} ${unconnected.getLocalPort} ${unconnected.getInetAddress}")
    attempt("unconnected stream")(unconnected.getInputStream)
    attempt("negative timeout")(unconnected.connect(new InetSocketAddress(InetAddress.getLoopbackAddress, 1), -1))
    attempt("unresolved")(unconnected.connect(InetSocketAddress.createUnresolved("nowhere.invalid", 80)))
    unconnected.close()
    attempt("connect closed")(unconnected.connect(new InetSocketAddress(InetAddress.getLoopbackAddress, 1)))
    attempt("bad port")(new ServerSocket(70000))
    attempt("bad timeout")(new ServerSocket().setSoTimeout(-1))
    println(new ServerSocket())

  def bulk(): Unit =
    println("-- bulk")
    val server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val size = 3 * 1024 * 1024 + 17
    val t = startThread {
      val s = server.accept()
      val out = new BufferedOutputStream(s.getOutputStream)
      for i <- 0 until size do out.write(i * 31 + (i >> 8))
      out.close()
    }
    val c = new Socket(InetAddress.getLoopbackAddress, server.getLocalPort)
    val in = new DataInputStream(new BufferedInputStream(c.getInputStream))
    val data = in.readAllBytes()
    var ok = data.length == size
    for i <- 0 until data.length.min(size) do if data(i) != (i * 31 + (i >> 8)).toByte then ok = false
    println(s"received ${data.length} bytes, intact: $ok")
    t.join()
    c.close()
    server.close()
