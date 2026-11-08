package rt

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// HTTP exchanges for java.net.HttpURLConnection and java.net.http.HttpClient
// (javalib/src/scalago/runtime/Net.scala), on net/http. An exchange sends a whole request and
// returns once the response's headers have arrived; its body is a stream that NetRead reads.
// TLS is Go's crypto/tls, with the system's root certificates.

// Redirect policies (Net.scala).
const (
	redirectNever        = iota
	redirectSameProtocol // HttpURLConnection's: within http or within https
	redirectNormal       // HttpClient's NORMAL: all but from https to http
	redirectAlways
)

type dialTimeoutKey struct{}

// httpTransport is shared by all exchanges, so connections are kept alive and reused, as the
// JDK does. It speaks HTTP/1.1, uses no proxy and does not ask for compressed responses, as
// the JDK's HttpURLConnection.
var httpTransport = newHTTPTransport()

func newHTTPTransport() *http.Transport {
	t := http.DefaultTransport.(*http.Transport).Clone()
	t.Proxy = nil
	t.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
		d := net.Dialer{}
		if timeout, ok := ctx.Value(dialTimeoutKey{}).(time.Duration); ok {
			d.Timeout = timeout
		}
		return d.DialContext(ctx, network, addr)
	}
	t.ForceAttemptHTTP2 = false
	t.TLSNextProto = map[string]func(string, *tls.Conn) http.RoundTripper{}
	t.DisableCompression = true
	t.MaxIdleConnsPerHost = 5
	return t
}

type httpExchange struct {
	resp     *http.Response
	cancel   context.CancelFunc
	timedOut atomic.Bool
	failureRecord
}

// timeoutError is the error of an exchange that took too long.
type timeoutError struct{}

func (timeoutError) Error() string   { return "timed out" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }

func (x *httpExchange) expire() {
	x.timedOut.Store(true)
	x.cancel()
}

// HttpSend sends a request: headers alternate names and values, body is null for none. It
// waits at most connectTimeoutMillis for the connection and timeoutMillis for the response's
// headers (if positive), and follows redirects as the policy says, at most maxRedirects times.
// It returns the exchange, or an error.
func HttpSend(method, url *String, headers *Array[any], body *Array[int8], connectTimeoutMillis, timeoutMillis,
	redirectPolicy, maxRedirects int32) any {
	ctx := context.WithValue(context.Background(), dialTimeoutKey{}, millis(connectTimeoutMillis))
	ctx, cancel := context.WithCancel(ctx)
	var content io.Reader
	if body != nil {
		content = bytes.NewReader(Reinterpret[int8, byte](body.Data))
	}
	req, err := http.NewRequestWithContext(ctx, method.GoString(), url.GoString(), content)
	if err != nil {
		cancel()
		return err
	}
	for i := 0; i+1 < len(headers.Data); i += 2 {
		name, value := headers.Data[i].(*String).GoString(), headers.Data[i+1].(*String).GoString()
		if strings.EqualFold(name, "Host") {
			req.Host = value
		} else {
			req.Header.Add(name, value)
		}
	}
	x := &httpExchange{cancel: cancel}
	client := http.Client{Transport: httpTransport, CheckRedirect: checkRedirect(redirectPolicy, int(maxRedirects))}
	var timer *time.Timer
	if timeoutMillis > 0 {
		timer = time.AfterFunc(millis(timeoutMillis), x.expire)
	}
	resp, err := client.Do(req)
	if timer != nil {
		timer.Stop()
	}
	if err != nil {
		cancel()
		if x.timedOut.Load() {
			return timeoutError{}
		}
		return err
	}
	x.resp = resp
	return x
}

func checkRedirect(policy int32, max int) func(*http.Request, []*http.Request) error {
	return func(req *http.Request, via []*http.Request) error {
		from := via[len(via)-1].URL.Scheme
		switch {
		case policy == redirectNever,
			policy == redirectSameProtocol && req.URL.Scheme != from,
			policy == redirectNormal && from == "https" && req.URL.Scheme == "http":
			return http.ErrUseLastResponse
		case len(via) > max:
			return &tooManyRedirects{max}
		}
		return nil
	}
}

func (x *httpExchange) read(b []byte, timeout time.Duration) (int, error) {
	var timer *time.Timer
	if timeout > 0 {
		timer = time.AfterFunc(timeout, x.expire)
	}
	n, err := x.resp.Body.Read(b)
	if timer != nil {
		timer.Stop()
	}
	if err != nil && err != io.EOF && x.timedOut.Load() {
		err = timeoutError{}
	}
	return n, err
}

func (x *httpExchange) close() {
	x.resp.Body.Close()
	x.cancel()
}

func exchangeOf(x any) *http.Response { return x.(*httpExchange).resp }

// HttpStatus is the response's status code.
func HttpStatus(x any) int32 { return int32(exchangeOf(x).StatusCode) }

// HttpReason is the response's reason phrase ("OK").
func HttpReason(x any) *String {
	r := exchangeOf(x)
	return Str(strings.TrimPrefix(r.Status, strconv.Itoa(r.StatusCode)+" "))
}

// HttpVersion is the response's protocol version: 10, 11 or 20.
func HttpVersion(x any) int32 {
	r := exchangeOf(x)
	return int32(r.ProtoMajor*10 + r.ProtoMinor)
}

// HttpHeaders are the response's header fields, alternating names and values, by name (Go
// gives them in no order, with canonical names).
func HttpHeaders(x any) *Array[any] {
	r := exchangeOf(x)
	h := r.Header
	if len(r.TransferEncoding) > 0 && h.Get("Transfer-Encoding") == "" {
		h = h.Clone()
		h["Transfer-Encoding"] = []string{strings.Join(r.TransferEncoding, ", ")}
	}
	names := make([]string, 0, len(h))
	for name := range h {
		names = append(names, name)
	}
	sort.Strings(names)
	out := []any{}
	for _, name := range names {
		for _, v := range h[name] {
			out = append(out, Str(name), Str(v))
		}
	}
	return &Array[any]{Data: out}
}

// HttpURL is the URL of the response: the request's, or the last redirect's.
func HttpURL(x any) *String { return Str(exchangeOf(x).Request.URL.String()) }

func isTLSError(err error) bool {
	var unknownAuthority x509.UnknownAuthorityError
	var hostname x509.HostnameError
	var invalid x509.CertificateInvalidError
	var verification *tls.CertificateVerificationError
	var record tls.RecordHeaderError
	return errors.As(err, &unknownAuthority) || errors.As(err, &hostname) || errors.As(err, &invalid) ||
		errors.As(err, &verification) || errors.As(err, &record)
}

// isMalformedResponse tells errors of responses that are not HTTP (net/http's are not typed).
func isMalformedResponse(err error) bool {
	return strings.Contains(err.Error(), "malformed HTTP")
}
