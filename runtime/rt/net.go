package rt

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"sort"
	"strconv"
	"sync/atomic"
	"syscall"
	"time"
	"unicode"
	"unicode/utf8"
)

// The native side of java.net (javalib/src/scalago/runtime/Net.scala): name resolution, TCP
// connections and listeners on Go's net package, and HTTP exchanges on net/http (http.go). The
// layer is byte-oriented and knows nothing of Java's classes: addresses are byte arrays (4 or 16
// bytes), and failures are Go errors returned as Objects, which Scala classifies (NetErrorKind)
// and turns into the JDK's exceptions.
//
// Connections, listeners and HTTP responses are handles held by Scala as Objects. Closing a
// handle from another thread makes a blocked read or accept return a netClosed error, as closing
// a socket does on the JDK.

// Error kinds (Net.scala).
const (
	netOtherError = iota
	netTimeout
	netConnectTimeout
	netClosed
	netRefused
	netReset
	netHostUnreachable
	netNetworkUnreachable
	netUnknownHost
	netAddressInUse
	netAddressNotAvailable
	netBrokenPipe
	netTLS
	netTooManyRedirects
	netMalformedResponse
)

// netStream is a handle that NetRead reads.
type netStream interface {
	read(b []byte, timeout time.Duration) (int, error)
	// record keeps the error of a failed read, which failure returns.
	record(err error)
	failure() error
}

// failureRecord keeps the error of a stream's last failed read, for NetFailure.
type failureRecord struct{ err atomic.Pointer[error] }

func (f *failureRecord) record(err error) { f.err.Store(&err) }

func (f *failureRecord) failure() error {
	if p := f.err.Load(); p != nil {
		return *p
	}
	return nil
}

// netConn is a TCP connection.
type netConn struct {
	conn        *net.TCPConn
	hasDeadline atomic.Bool // whether a read deadline is set
	failureRecord
}

// netListener is a listening TCP socket.
type netListener struct {
	ln          *net.TCPListener
	hasDeadline atomic.Bool
}

func millis(ms int32) time.Duration { return time.Duration(ms) * time.Millisecond }

// ipOf converts a 4- or 16-byte address; nil stays nil (the wildcard address).
func ipOf(addr *Array[int8]) net.IP {
	if addr == nil {
		return nil
	}
	return net.IP(append([]byte(nil), Reinterpret[int8, byte](addr.Data)...))
}

// addressBytes is an address as Java sees it: 4 bytes for IPv4 (also IPv4-mapped IPv6), 16
// otherwise. The unspecified IPv6 address of a dual-stack socket is IPv4's 0.0.0.0, as the JDK
// shows it.
func addressBytes(ip net.IP) *Array[int8] {
	if ip4 := ip.To4(); ip4 != nil {
		ip = ip4
	} else if ip.IsUnspecified() {
		ip = net.IPv4zero.To4()
	}
	return ArrayOfSlice(Reinterpret[byte, int8](append([]byte(nil), ip...)))
}

func tcpAddr(addr *Array[int8], port int32) *net.TCPAddr {
	return &net.TCPAddr{IP: ipOf(addr), Port: int(port)}
}

// ---- Names ----------------------------------------------------------------------------------

// NetLookup resolves a host name to its addresses (byte arrays), IPv4 addresses first as on the
// JDK (java.net.preferIPv6Addresses=false), or returns an error.
func NetLookup(host *String) any {
	addrs, err := net.DefaultResolver.LookupIPAddr(context.Background(), host.GoString())
	if err != nil {
		return err
	}
	sort.SliceStable(addrs, func(i, j int) bool { return addrs[i].IP.To4() != nil && addrs[j].IP.To4() == nil })
	out := make([]any, len(addrs))
	for i, a := range addrs {
		out[i] = addressBytes(a.IP)
	}
	return &Array[any]{Data: out}
}

// NetReverseLookup is the host name of an address, or null if it has none.
func NetReverseLookup(addr *Array[int8]) *String {
	names, err := net.DefaultResolver.LookupAddr(context.Background(), ipOf(addr).String())
	if err != nil || len(names) == 0 {
		return nil
	}
	name := names[0]
	if len(name) > 1 && name[len(name)-1] == '.' {
		name = name[:len(name)-1]
	}
	return Str(name)
}

// NetHostname is the name of this machine, or "localhost".
func NetHostname() *String {
	name, err := os.Hostname()
	if err != nil || name == "" {
		name = "localhost"
	}
	return Str(name)
}

// ---- Connections and listeners --------------------------------------------------------------

// NetConnect connects to addr:port, from localAddr:localPort if either is set, waiting at most
// timeoutMillis if positive. It returns the connection or an error.
func NetConnect(addr *Array[int8], port int32, localAddr *Array[int8], localPort int32, timeoutMillis int32) any {
	d := net.Dialer{Timeout: millis(timeoutMillis)}
	if localAddr != nil || localPort != 0 {
		d.LocalAddr = tcpAddr(localAddr, localPort)
	}
	c, err := d.Dial("tcp", tcpAddr(addr, port).String())
	if err != nil {
		return err
	}
	return newNetConn(c.(*net.TCPConn))
}

func newNetConn(c *net.TCPConn) *netConn {
	// The JDK's sockets start without TCP_NODELAY; Go's with it.
	c.SetNoDelay(false)
	return &netConn{conn: c}
}

// NetListen listens on addr:port (all addresses if addr is null, an ephemeral port if port is
// 0), returning the listener or an error. The backlog is the system's.
func NetListen(addr *Array[int8], port int32, backlog int32) any {
	ln, err := net.ListenTCP("tcp", tcpAddr(addr, port))
	if err != nil {
		return err
	}
	return &netListener{ln: ln}
}

// NetAccept waits for a connection (at most timeoutMillis if positive) and returns it, or an
// error.
func NetAccept(listener any, timeoutMillis int32) any {
	l := listener.(*netListener)
	if timeoutMillis > 0 {
		l.ln.SetDeadline(time.Now().Add(millis(timeoutMillis)))
		l.hasDeadline.Store(true)
	} else if l.hasDeadline.Swap(false) {
		l.ln.SetDeadline(time.Time{})
	}
	c, err := l.ln.AcceptTCP()
	if err != nil {
		return err
	}
	return newNetConn(c)
}

func (c *netConn) read(b []byte, timeout time.Duration) (int, error) {
	if timeout > 0 {
		c.conn.SetReadDeadline(time.Now().Add(timeout))
		c.hasDeadline.Store(true)
	} else if c.hasDeadline.Swap(false) {
		c.conn.SetReadDeadline(time.Time{})
	}
	return c.conn.Read(b)
}

// NetRead reads up to n bytes into b[off:], waiting at most timeoutMillis if positive. It returns
// the number of bytes read, -1 at the end of the stream, or -2 if the read failed (NetFailure
// gives the error).
func NetRead(handle any, b *Array[int8], off, n, timeoutMillis int32) int32 {
	s := handle.(netStream)
	if n == 0 {
		return 0
	}
	buf := Reinterpret[int8, byte](b.Data[off : off+n])
	for {
		count, err := s.read(buf, millis(timeoutMillis))
		if count > 0 {
			return int32(count) // an error comes again with the next read
		}
		if err == io.EOF {
			return -1
		}
		if err != nil {
			s.record(err)
			return -2
		}
	}
}

// NetFailure is the error of the stream's last failed read.
func NetFailure(handle any) any { return handle.(netStream).failure() }

// NetWrite writes b[off:off+n] to a connection: null, or an error.
func NetWrite(handle any, b *Array[int8], off, n int32) any {
	_, err := handle.(*netConn).conn.Write(Reinterpret[int8, byte](b.Data[off : off+n]))
	return nilOrError(err)
}

// NetShutdown shuts down the input or output side of a connection: null, or an error.
func NetShutdown(handle any, output bool) any {
	c := handle.(*netConn).conn
	if output {
		return nilOrError(c.CloseWrite())
	}
	return nilOrError(c.CloseRead())
}

// NetClose closes a connection, listener or HTTP response. Closing twice is harmless.
func NetClose(handle any) {
	switch h := handle.(type) {
	case *netConn:
		h.conn.Close()
	case *netListener:
		h.ln.Close()
	case *httpExchange:
		h.close()
	}
}

func addrOf(handle any, local bool) *net.TCPAddr {
	switch h := handle.(type) {
	case *netConn:
		if local {
			return h.conn.LocalAddr().(*net.TCPAddr)
		}
		return h.conn.RemoteAddr().(*net.TCPAddr)
	case *netListener:
		return h.ln.Addr().(*net.TCPAddr)
	}
	panic("not a socket")
}

// NetAddress is the local or remote address of a connection or listener.
func NetAddress(handle any, local bool) *Array[int8] { return addressBytes(addrOf(handle, local).IP) }

// NetPort is the local or remote port of a connection or listener.
func NetPort(handle any, local bool) int32 { return int32(addrOf(handle, local).Port) }

// Socket options (Net.scala).
const (
	optNoDelay = iota
	optKeepAlive
	optLinger
	optReceiveBuffer
	optSendBuffer
)

// NetSetOption sets a connection's option: null, or an error. For SO_LINGER, a negative value
// turns lingering off.
func NetSetOption(handle any, option, value int32) any {
	c := handle.(*netConn).conn
	var err error
	switch option {
	case optNoDelay:
		err = c.SetNoDelay(value != 0)
	case optKeepAlive:
		err = c.SetKeepAlive(value != 0)
	case optLinger:
		err = c.SetLinger(int(value))
	case optReceiveBuffer:
		err = c.SetReadBuffer(int(value))
	case optSendBuffer:
		err = c.SetWriteBuffer(int(value))
	}
	return nilOrError(err)
}

// NetBufferSize is the size of a connection's or listener's receive or send buffer, as the
// system reports it (0 if it cannot).
func NetBufferSize(handle any, send bool) int32 {
	var raw syscall.RawConn
	var err error
	switch h := handle.(type) {
	case *netConn:
		raw, err = h.conn.SyscallConn()
	case *netListener:
		raw, err = h.ln.SyscallConn()
	}
	if err != nil {
		return 0
	}
	return socketBufferSize(raw, send)
}

// ---- Errors ---------------------------------------------------------------------------------

// NetErrorKind classifies an error (the constants above), which selects the Java exception.
func NetErrorKind(e any) int32 {
	err := e.(error)
	var dnsErr *net.DNSError
	var redirects *tooManyRedirects
	switch {
	case errors.Is(err, net.ErrClosed):
		return netClosed
	case errors.As(err, &redirects):
		return netTooManyRedirects
	case errors.As(err, &dnsErr):
		return netUnknownHost
	case isTLSError(err):
		return netTLS
	case isMalformedResponse(err):
		return netMalformedResponse
	}
	var timeout interface{ Timeout() bool }
	if errors.As(err, &timeout) && timeout.Timeout() {
		var opErr *net.OpError
		if errors.As(err, &opErr) && opErr.Op == "dial" {
			return netConnectTimeout
		}
		return netTimeout
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		return errnoKind(errno)
	}
	return netOtherError
}

// NetErrorMessage describes an error as the JDK's messages do: the C library's description of a
// system error ("Connection refused"), or Go's message.
func NetErrorMessage(e any) *String {
	err := e.(error)
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		if dnsErr.IsNotFound {
			return Str(dnsErr.Name + ": Name or service not known")
		}
		return Str(dnsErr.Name + ": Temporary failure in name resolution")
	}
	var errno syscall.Errno
	if errors.As(err, &errno) {
		msg := errno.Error()
		r, size := utf8.DecodeRuneInString(msg)
		return Str(string(unicode.ToUpper(r)) + msg[size:])
	}
	return Str(err.Error())
}

// tooManyRedirects is returned when an HTTP exchange is redirected more often than allowed.
type tooManyRedirects struct{ max int }

func (e *tooManyRedirects) Error() string {
	return "redirected more than " + strconv.Itoa(e.max) + " times"
}
