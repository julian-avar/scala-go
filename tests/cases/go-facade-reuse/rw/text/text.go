// Package text uses types of the standard library (io, strings, time) and of package base in its
// API: its facades (facades-textfacades.txt) reuse scalago-gostd's.
package text

import (
	"fmt"
	"io"
	"strings"
	"time"

	"example.com/rw/base"
)

// Upper reads from another reader, upper-casing ASCII letters. It is an io.Reader and a
// base.Named.
type Upper struct {
	r io.Reader
	n int64
}

func NewUpper(r io.Reader) *Upper { return &Upper{r: r} }

func (u *Upper) Read(p []byte) (int, error) {
	n, err := u.r.Read(p)
	for i := 0; i < n; i++ {
		if 'a' <= p[i] && p[i] <= 'z' {
			p[i] -= 'a' - 'A'
		}
	}
	u.n += int64(n)
	return n, err
}

func (u *Upper) Name() string { return "upper" }

// Count is the number of bytes read so far.
func (u *Upper) Count() int64 { return u.n }

// Fill writes s to b n times.
func Fill(b *strings.Builder, s string, n int) {
	for i := 0; i < n; i++ {
		b.WriteString(s)
	}
}

// Elapsed formats a duration in seconds.
func Elapsed(d time.Duration) string { return fmt.Sprintf("%.1fs", d.Seconds()) }

// Describe names a base.Named.
func Describe(n base.Named) string { return "named " + n.Name() }
