// Package deadline makes Go contexts for the gears-go-context test case.
package deadline

import (
	"context"
	"time"
)

// After returns a context that is done after ms milliseconds.
func After(ms int) context.Context {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(ms)*time.Millisecond)
	go func() {
		<-ctx.Done()
		cancel()
	}()
	return ctx
}
