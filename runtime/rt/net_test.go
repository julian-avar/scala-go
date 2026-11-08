package rt

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

var loopback = ArrayOf[int8](127, 0, 0, 1)

func byteArray(s string) *Array[int8] { return ArrayOfSlice(Reinterpret[byte, int8]([]byte(s))) }

// readStream reads a connection or HTTP body to its end, or until a read fails.
func readStream(t *testing.T, handle any, timeoutMillis int32) (string, int32) {
	var sb strings.Builder
	buf := NewArray[int8](16)
	for {
		n := NetRead(handle, buf, 0, 16, timeoutMillis)
		if n < 0 {
			return sb.String(), n
		}
		sb.Write(Reinterpret[int8, byte](buf.Data[:n]))
	}
}

func listen(t *testing.T) (*netListener, int32) {
	l, ok := NetListen(loopback, 0, 50).(*netListener)
	if !ok {
		t.Fatal("listen failed")
	}
	t.Cleanup(func() { NetClose(l) })
	return l, NetPort(l, true)
}

func TestNetConnectAcceptReadWrite(t *testing.T) {
	l, port := listen(t)
	accepted := make(chan any)
	go func() { accepted <- NetAccept(l, 0) }()
	c := NetConnect(loopback, port, nil, 0, 1000)
	s := <-accepted
	if _, ok := c.(*netConn); !ok {
		t.Fatalf("connect: %v", c)
	}
	if NetPort(s, false) != NetPort(c, true) || NetPort(c, false) != port {
		t.Errorf("ports do not match")
	}
	if got := NetAddress(c, false).Data; len(got) != 4 || got[0] != 127 {
		t.Errorf("remote address = %v", got)
	}
	if err := NetWrite(c, byteArray("hello"), 0, 5); err != nil {
		t.Fatal(err)
	}
	if err := NetShutdown(c, true); err != nil {
		t.Fatal(err)
	}
	if got, n := readStream(t, s, 0); got != "hello" || n != -1 {
		t.Errorf("read %q, %d", got, n)
	}
	NetClose(s)
	NetClose(c)
	NetClose(c) // closing twice is harmless
}

func TestNetTimeoutsAndClosing(t *testing.T) {
	l, port := listen(t)
	if e := NetAccept(l, 20); NetErrorKind(e) != netTimeout {
		t.Errorf("accept: %v", e)
	}
	go func() { NetAccept(l, 0) }()
	c := NetConnect(loopback, port, nil, 0, 0)
	if _, n := readStream(t, c, 20); n != -2 || NetErrorKind(NetFailure(c)) != netTimeout {
		t.Errorf("read did not time out: %d %v", n, NetFailure(c))
	}
	// Closing from another goroutine ends a blocked read.
	go func() {
		time.Sleep(20 * time.Millisecond)
		NetClose(c)
	}()
	if _, n := readStream(t, c, 0); n != -2 || NetErrorKind(NetFailure(c)) != netClosed {
		t.Errorf("read after close: %d %v", n, NetFailure(c))
	}
	// And a blocked accept.
	go func() {
		time.Sleep(20 * time.Millisecond)
		NetClose(l)
	}()
	if e := NetAccept(l, 0); NetErrorKind(e) != netClosed {
		t.Errorf("accept after close: %v", e)
	}
}

func TestNetErrors(t *testing.T) {
	l, port := listen(t)
	if e := NetListen(loopback, port, 50); NetErrorKind(e) != netAddressInUse ||
		NetErrorMessage(e).GoString() != "Address already in use" {
		t.Errorf("listen twice: %v", e)
	}
	NetClose(l)
	if e := NetConnect(loopback, port, nil, 0, 1000); NetErrorKind(e) != netRefused ||
		NetErrorMessage(e).GoString() != "Connection refused" {
		t.Errorf("connect to a closed port: %v", e)
	}
	if addrs, ok := NetLookup(Str("localhost")).(*Array[any]); !ok || len(addrs.Data) == 0 ||
		len(addrs.Data[0].(*Array[int8]).Data) != 4 {
		t.Errorf("localhost: %v", addrs)
	}
}

func TestHttpExchange(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/redirect":
			http.Redirect(w, r, "/echo", http.StatusFound)
		case "/slow":
			time.Sleep(200 * time.Millisecond)
			fmt.Fprint(w, "late")
		case "/loop":
			http.Redirect(w, r, "/loop", http.StatusFound)
		default:
			w.Header().Add("X-Multi", "a")
			w.Header().Add("X-Multi", "b")
			w.WriteHeader(http.StatusTeapot)
			fmt.Fprintf(w, "%s %s %s ua=%s", r.Method, r.URL.Path, r.Header.Get("X-Test"), r.Header.Get("User-Agent"))
		}
	}))
	defer server.Close()
	headers := ArrayOf[any](Str("X-Test"), Str("yes"), Str("User-Agent"), Str("test"))
	send := func(method, path string, body *Array[int8], timeout, policy int32) any {
		return HttpSend(Str(method), Str(server.URL+path), headers, body, 1000, timeout, policy, 3)
	}

	x := send("POST", "/echo", byteArray("data"), 0, redirectNever)
	if _, ok := x.(*httpExchange); !ok {
		t.Fatalf("send: %v", x)
	}
	if HttpStatus(x) != 418 || HttpReason(x).GoString() != "I'm a teapot" || HttpVersion(x) != 11 {
		t.Errorf("status %d %q %d", HttpStatus(x), HttpReason(x).GoString(), HttpVersion(x))
	}
	var fields []string
	for _, f := range HttpHeaders(x).Data {
		fields = append(fields, f.(*String).GoString())
	}
	if got := strings.Join(fields, ","); !strings.Contains(got, "X-Multi,a,X-Multi,b") {
		t.Errorf("headers %s", got)
	}
	if body, n := readStream(t, x, 0); body != "POST /echo yes ua=test" || n != -1 {
		t.Errorf("body %q", body)
	}
	NetClose(x)

	if x := send("GET", "/redirect", nil, 0, redirectNever); HttpStatus(x) != 302 {
		t.Errorf("not followed: %d", HttpStatus(x))
	}
	if x := send("GET", "/redirect", nil, 0, redirectSameProtocol); HttpStatus(x) != 418 ||
		!strings.HasSuffix(HttpURL(x).GoString(), "/echo") {
		t.Errorf("followed: %d %s", HttpStatus(x), HttpURL(x).GoString())
	}
	if e := send("GET", "/loop", nil, 0, redirectAlways); NetErrorKind(e) != netTooManyRedirects {
		t.Errorf("loop: %v", e)
	}
	if e := send("GET", "/slow", nil, 50, redirectNever); NetErrorKind(e) != netTimeout {
		t.Errorf("timeout: %v", e)
	}
}
