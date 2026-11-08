// Package web is gin's API in miniature, for the go-web test case: an engine embedding a router
// group (promoted methods), handlers of a variadic named func type, a context with pointer
// receivers, named map and slice types (H, HandlersChain, Params), JSON through encoding/json,
// handlers running on goroutines, and callbacks with error results.
package web

import (
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
)

// H is a JSON object, a named map type with a method.
type H map[string]any

// Keys returns the keys in order.
func (h H) Keys() []string {
	keys := make([]string, 0, len(h))
	for k := range h {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// HandlerFunc is a named func type.
type HandlerFunc func(*Context)

// HandlersChain is a named slice of funcs.
type HandlersChain []HandlerFunc

// Last returns the last handler.
func (c HandlersChain) Last() HandlerFunc {
	if len(c) == 0 {
		return nil
	}
	return c[len(c)-1]
}

// Param is a path parameter.
type Param struct {
	Key   string
	Value string
}

// Params is a named slice of structs.
type Params []Param

// ByName returns the value of the parameter, or "".
func (ps Params) ByName(name string) string {
	for _, p := range ps {
		if p.Key == name {
			return p.Value
		}
	}
	return ""
}

// Context is a request in flight; its methods have pointer receivers.
type Context struct {
	Method   string
	Path     string
	Params   Params
	Keys     map[string]any
	body     string
	handlers HandlersChain
	index    int
	status   int
	out      strings.Builder
}

// Param returns a path parameter.
func (c *Context) Param(key string) string { return c.Params.ByName(key) }

// Next runs the remaining handlers.
func (c *Context) Next() {
	for c.index++; c.index < len(c.handlers); c.index++ {
		c.handlers[c.index](c)
	}
}

// Abort stops the chain.
func (c *Context) Abort() { c.index = len(c.handlers) }

// Set stores a value for later handlers.
func (c *Context) Set(key string, value any) {
	if c.Keys == nil {
		c.Keys = map[string]any{}
	}
	c.Keys[key] = value
}

// GetString returns a stored string.
func (c *Context) GetString(key string) string {
	s, _ := c.Keys[key].(string)
	return s
}

// JSON writes obj as JSON.
func (c *Context) JSON(code int, obj any) {
	data, err := json.Marshal(obj)
	if err != nil {
		c.status = 500
		c.out.WriteString(err.Error())
		return
	}
	c.status = code
	c.out.Write(data)
}

// AbortWithStatusJSON writes obj and stops the chain.
func (c *Context) AbortWithStatusJSON(code int, obj any) {
	c.Abort()
	c.JSON(code, obj)
}

// String writes formatted text.
func (c *Context) String(code int, format string, values ...any) {
	c.status = code
	fmt.Fprintf(&c.out, format, values...)
}

// BindJSON decodes the body into obj, a pointer.
func (c *Context) BindJSON(obj any) error { return json.Unmarshal([]byte(c.body), obj) }

// RouterGroup registers routes under a prefix.
type RouterGroup struct {
	Handlers HandlersChain
	prefix   string
	engine   *Engine
}

// Use adds middleware.
func (g *RouterGroup) Use(middleware ...HandlerFunc) *RouterGroup {
	g.Handlers = append(g.Handlers, middleware...)
	return g
}

// Group returns a group under a longer prefix, with more handlers.
func (g *RouterGroup) Group(path string, handlers ...HandlerFunc) *RouterGroup {
	chain := append(append(HandlersChain{}, g.Handlers...), handlers...)
	return &RouterGroup{Handlers: chain, prefix: g.prefix + path, engine: g.engine}
}

// Handle registers a route.
func (g *RouterGroup) Handle(method, path string, handlers ...HandlerFunc) {
	chain := append(append(HandlersChain{}, g.Handlers...), handlers...)
	g.engine.routes = append(g.engine.routes, route{method, strings.Split(strings.Trim(g.prefix+path, "/"), "/"), chain})
}

// GET registers a GET route.
func (g *RouterGroup) GET(path string, handlers ...HandlerFunc) { g.Handle("GET", path, handlers...) }

// POST registers a POST route.
func (g *RouterGroup) POST(path string, handlers ...HandlerFunc) { g.Handle("POST", path, handlers...) }

type route struct {
	method   string
	segments []string
	chain    HandlersChain
}

// Engine embeds a RouterGroup: its methods are promoted.
type Engine struct {
	RouterGroup
	routes []route
}

// New returns an engine without routes.
func New() *Engine {
	e := &Engine{}
	e.RouterGroup.engine = e
	return e
}

// Serve handles one request: "status body".
func (e *Engine) Serve(method, path, body string) string {
	segments := strings.Split(strings.Trim(path, "/"), "/")
	for _, r := range e.routes {
		if params, ok := match(r, method, segments); ok {
			c := &Context{Method: method, Path: path, Params: params, body: body, handlers: r.chain, index: -1}
			c.Next()
			return fmt.Sprintf("%d %s", c.status, c.out.String())
		}
	}
	return "404 not found"
}

func match(r route, method string, segments []string) (Params, bool) {
	if r.method != method || len(r.segments) != len(segments) {
		return nil, false
	}
	var params Params
	for i, s := range r.segments {
		if strings.HasPrefix(s, ":") {
			params = append(params, Param{s[1:], segments[i]})
		} else if s != segments[i] {
			return nil, false
		}
	}
	return params, true
}

// ServeAll handles "METHOD path" requests concurrently, each on its own goroutine, as net/http
// does; the responses are in the order of the requests.
func (e *Engine) ServeAll(requests ...string) []string {
	responses := make([]string, len(requests))
	var wg sync.WaitGroup
	for i, r := range requests {
		wg.Add(1)
		go func() {
			defer wg.Done()
			method, path, _ := strings.Cut(r, " ")
			responses[i] = e.Serve(method, path, "")
		}()
	}
	wg.Wait()
	return responses
}

// Run calls the handlers of a chain on a fresh context, as a test helper does.
func Run(chain HandlersChain, path string) string {
	c := &Context{Path: path, handlers: chain, index: -1}
	c.Next()
	return fmt.Sprintf("%d %s", c.status, c.out.String())
}

// Describe tells the Go type and value of v, as Go code inspecting it sees it.
func Describe(v any) string {
	data, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprintf("%T (%v)", v, err)
	}
	return fmt.Sprintf("%T %s", v, data)
}

// Group runs funcs concurrently and keeps the first error, like errgroup.Group.
type Group struct {
	wg   sync.WaitGroup
	mu   sync.Mutex
	errs []string
}

// Go runs f on a goroutine.
func (g *Group) Go(f func() error) {
	g.wg.Add(1)
	go func() {
		defer g.wg.Done()
		if err := f(); err != nil {
			g.mu.Lock()
			g.errs = append(g.errs, err.Error())
			g.mu.Unlock()
		}
	}()
}

// Wait waits for the funcs and returns their errors, sorted, joined.
func (g *Group) Wait() error {
	g.wg.Wait()
	if len(g.errs) == 0 {
		return nil
	}
	sort.Strings(g.errs)
	return errors.New(strings.Join(g.errs, "; "))
}

// ErrNotFound is returned by loaders for missing names.
var ErrNotFound = errors.New("not found")

// Loader is a named func type with a result and an error.
type Loader func(name string) (H, error)

// LoadAll merges what the loader returns for each name, skipping ErrNotFound.
func LoadAll(load Loader, names ...string) (H, error) {
	all := H{}
	for _, n := range names {
		h, err := load(n)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, fmt.Errorf("loading %s: %w", n, err)
		}
		for k, v := range h {
			all[k] = v
		}
	}
	return all, nil
}

// Parser returns a Go func with an error result to Scala.
func Parser(base int) func(string) (int, error) {
	return func(s string) (int, error) {
		n := 0
		for _, r := range s {
			d := int(r - '0')
			if d < 0 || d >= base {
				return 0, fmt.Errorf("bad digit %q for base %d", r, base)
			}
			n = n*base + d
		}
		return n, nil
	}
}

// Split returns a Go func with several results.
func Split(sep string) func(string) (string, string) {
	return func(s string) (string, string) {
		before, after, _ := strings.Cut(s, sep)
		return before, after
	}
}
