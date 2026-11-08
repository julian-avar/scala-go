import java.net.*
import java.nio.charset.StandardCharsets

/** URIs, URLs, URL encoding, and IP address literals: no network needed. */
object Main:
  def attempt(label: String)(body: => Any): Unit =
    try println(s"$label: ${body}")
    catch case e: Exception => println(s"$label: ${e.getClass.getName}: ${e.getMessage}")

  def describe(u: URI): String =
    List(u.getScheme, u.getRawSchemeSpecificPart, u.getAuthority, u.getUserInfo, u.getHost, u.getPort, u.getRawPath,
      u.getPath, u.getRawQuery, u.getQuery, u.getFragment, u.isAbsolute, u.isOpaque).mkString("|")

  def main(args: Array[String]): Unit =
    uris()
    urls()
    encoding()
    addresses()

  def uris(): Unit =
    println("-- URI")
    for s <- List("http://user:pw@Example.COM:8080/a/b%20c/../d?q=1&r=%C3%A9#frag", "mailto:someone@example.com",
        "urn:isbn:096139210x", "file:///tmp/x y".replace(" ", "%20"), "/a/./b/../c", "../x", "a:b/c", "",
        "http://[::1]:80/", "http://[fe80::1%25eth0]/", "http://a_b.example/", "//host/p", "http://h/ü?é#ö",
        "s://reg%41name/p", "http://h:/p")
    do attempt(s"<$s>")(describe(new URI(s)))
    for s <- List("http://a b/", "ht tp://x", ":x", "http:", "http://[::1/", "http://x/%zz", "a b", "http://h/#a#b",
        "http://[1:2:3]/")
    do attempt(s"<$s>")(new URI(s))
    attempt("parseServerAuthority")(new URI("http://host:port/").parseServerAuthority())
    attempt("create")(URI.create("bad uri"))
    val e = try { new URI("x y"); null } catch case e: URISyntaxException => e
    println(s"${e.getInput} | ${e.getReason} | ${e.getIndex}")

    val base = new URI("http://a/b/c/d;p?q")
    for r <- List("g", "./g", "g/", "/g", "//g", "?y", "g?y", "#s", "g#s", ";x", "", ".", "..", "../g", "../..",
        "../../../g", "g/./h", "g/../h", "http:g", "https://other/x")
    do println(s"resolve $r -> ${base.resolve(r)}")
    val parent = new URI("http://example.com/docs/")
    for c <- List("http://example.com/docs/a/b.html?x#y", "http://example.com/other", "http://EXAMPLE.com/docs/z",
        "https://example.com/docs/a")
    do println(s"relativize $c -> ${parent.relativize(new URI(c))}")
    for s <- List("/a/b/../../../c", "a/./b/.", "a/b/..", "x:y/../z", "//a//b//c", "/a//b/") do
      println(s"normalize $s -> ${new URI(s).normalize()}")

    val u1 = new URI("HTTP://Example.com/%7e")
    val u2 = new URI("http://example.COM/%7E")
    println(s"equals ${u1 == u2} ${u1.hashCode == u2.hashCode} ${u1.compareTo(u2)} ${u1.compareTo(new URI("http://example.com/z")) < 0}")
    println(List("b:x", "a://h/", "http://h/b", "http://h/a", "#f", "a:x").map(new URI(_)).sorted.mkString(" "))

    println(new URI("http", "user info", "host", 8080, "/a path/ü", "q=a b&c=%", "frag ment"))
    println(new URI("http", "host", "/p q", "f"))
    println(new URI("http", "[::1]:80", "/x", "a=b", null))
    println(new URI("mailto", "a b@example.com", null))
    println(new URI("http", null, "::1", -1, "/", null, null))
    attempt("relative path")(new URI("http", "host", "rel", null))
    println(new URI("http://h/ü€?q=ä").toASCIIString)
    println(new URI("http://example.com/a").toURL)
    attempt("toURL of a relative URI")(new URI("a/b").toURL)

  def urls(): Unit =
    println("-- URL")
    def show(u: URL) =
      List(u.getProtocol, u.getAuthority, u.getUserInfo, u.getHost, u.getPort, u.getDefaultPort, u.getPath, u.getQuery,
        u.getFile, u.getRef, u.toExternalForm).mkString("|")
    for s <- List("http://user@www.example.com:8080/a/b.html?x=1#top", "HTTPS://Example.com", "file:/tmp/x.txt",
        "file:///tmp/x.txt", "http://[::1]:9/p", "jar:file:/a.jar!/b/c.class", "url:http://x/y", "  http://x/  ",
        "mailto:a@b.c", "http://h?q", "http://h#r")
    do attempt(s"<$s>")(show(new URL(s)))
    for s <- List("nope", "foo://x", "http://h:port/", "http://[x]/", "http://[::1]x/", "/relative") do attempt(s"<$s>")(new URL(s))
    val ctx = new URL("http://example.com/a/b/c.html?q#r")
    for spec <- List("d.html", "../d.html", "/root", "//other/x", "?y", "#s", "", "https://secure/x", "http:rel", "./x/../y")
    do println(s"$spec -> ${new URL(ctx, spec)}")
    println(new URL("http", "example.com", 8080, "/p?q#r"))
    println(new URL("http", "::1", "/x"))
    // Host names would be resolved by the JDK: these are addresses.
    val a = new URL("http://127.0.0.1:80/x")
    val b = new URL("http://127.0.0.1/x")
    println(s"${a.sameFile(b)} ${a == b} ${a == new URL("http://127.0.0.1/x#f")} ${a == new URL("https://127.0.0.1/x")}")
    println(new URL("http://example.com/a%20b?c#d").toURI)

  def encoding(): Unit =
    println("-- URLEncoder/URLDecoder")
    for s <- List("hello world", "a+b=c&d", "Grüße 世界 🌍", "*-._~!'()", "") do
      val enc = URLEncoder.encode(s, StandardCharsets.UTF_8)
      println(s"$enc | ${URLEncoder.encode(s, "ISO-8859-1")} | ${URLDecoder.decode(enc, StandardCharsets.UTF_8)}")
    println(URLDecoder.decode("a%2Bb+c%3D%e2%82%ac", "UTF-8"))
    for s <- List("%", "%4", "%zz", "%-1", "%+1") do attempt(s"decode $s")(URLDecoder.decode(s, "UTF-8"))
    attempt("encoding")(URLEncoder.encode("x", "no-such-charset"))

  def addresses(): Unit =
    println("-- InetAddress")
    for s <- List("127.0.0.1", "127.1", "10.1", "4294967295", "::1", "[::1]", "::ffff:192.0.2.1", "2001:db8::1",
        "1:2:3:4:5:6:7:8", "fe80::1%2", "::", "0.0.0.0", "::1.2.3.4", "", "localhost")
    do
      attempt(s"<$s>") {
        val a = InetAddress.getByName(s)
        List(a.getClass.getSimpleName, a, a.getHostAddress, a.getAddress.mkString(","), a.hashCode, a.isLoopbackAddress,
          a.isAnyLocalAddress, a.isLinkLocalAddress, a.isSiteLocalAddress, a.isMulticastAddress).mkString(" ")
      }
    for s <- List("[::1", "[1.2.3.4]", "1::2::3", "12345::", "::ffff:1.2.3.4%1", "fe80::1%nosuchif") do
      attempt(s"<$s>")(InetAddress.getByName(s))
    for a <- List("10.0.0.1", "172.16.5.4", "192.168.1.1", "169.254.0.1", "224.0.0.1", "239.255.0.1", "8.8.8.8", "ff02::1",
        "fec0::1", "ff0e::1")
    do
      val x = InetAddress.getByName(a)
      println(s"$a site=${x.isSiteLocalAddress} link=${x.isLinkLocalAddress} mc=${x.isMulticastAddress} global=${x.isMCGlobal} mcLink=${x.isMCLinkLocal}")
    println(InetAddress.getByAddress("example", Array[Byte](192.toByte, 0, 2, 1)))
    println(InetAddress.getByAddress(Array.fill[Byte](16)(0).updated(15, 1)))
    attempt("bad length")(InetAddress.getByAddress(Array[Byte](1, 2, 3)))
    println(InetAddress.getLoopbackAddress)
    println(s"${InetAddress.getByName("127.0.0.1") == InetAddress.getByAddress(Array[Byte](127, 0, 0, 1))}")
    println(InetAddress.getAllByName("1.2.3.4").toList)

    println("-- InetSocketAddress")
    val isa = new InetSocketAddress(InetAddress.getByName("192.0.2.1"), 80)
    println(s"$isa ${isa.getPort} ${isa.getHostString} ${isa.isUnresolved}")
    println(new InetSocketAddress(InetAddress.getByName("::1"), 443))
    println(new InetSocketAddress(8080))
    val unresolved = InetSocketAddress.createUnresolved("example.invalid", 1)
    println(s"$unresolved ${unresolved.isUnresolved} ${unresolved.getAddress} ${unresolved.getHostName}")
    println(new InetSocketAddress("localhost", 99))
    println(s"${isa == new InetSocketAddress("192.0.2.1", 80)} ${isa.hashCode == new InetSocketAddress("192.0.2.1", 80).hashCode}")
    attempt("bad port")(new InetSocketAddress(70000))
    attempt("null host")(new InetSocketAddress(null: String, 1))
