// Package feed is used by the interop-gears-go test case: Go channels and contexts in gears code.
package feed

import (
	"context"
	"time"
)

// Numbers sends 1..n on a new channel from a goroutine, then closes it.
func Numbers(n int) <-chan int {
	c := make(chan int)
	go func() {
		for i := 1; i <= n; i++ {
			c <- i
		}
		close(c)
	}()
	return c
}

// Never returns a channel nothing is sent on.
func Never() <-chan int { return make(chan int) }

// Square squares slowly, on the calling goroutine.
func Square(x int) int {
	time.Sleep(10 * time.Millisecond)
	return x * x
}

// Cancelled returns a context that is already cancelled.
func Cancelled() context.Context {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return ctx
}
