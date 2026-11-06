// A Go program using a Scala library.
package main

import (
	"fmt"
	"sync"

	lib "scalagolib"
)

func main() {
	fmt.Println(lib.Greet("Go"))
	fmt.Println(lib.Sum([]int{1, 2, 3, 4}))

	n, err := lib.Parse(" 42 ")
	fmt.Println(n, err)
	_, err = lib.Parse("forty-two")
	fmt.Println("error:", err != nil)

	fmt.Println(lib.Twice(func(x int32) int32 { return x * 3 }, 2))

	func() {
		defer func() { fmt.Println("recovered:", recover()) }()
		lib.Fail("boom")
	}()

	counts := lib.WordCounts("to be or not to be")
	fmt.Println(counts["to"], counts["be"], counts["or"], len(counts))

	shapes := []lib.Shape{lib.NewCircle(1), lib.NewSquare(2)}
	for _, s := range shapes {
		fmt.Println(lib.Describe(s), "/", s.Name())
	}
	biggest := lib.Largest(shapes)
	fmt.Println("largest:", biggest.Name(), biggest == shapes[1])
	fmt.Println("none:", lib.Largest(nil) == nil)

	c := lib.NewCounter(10)
	var wg sync.WaitGroup
	for i := 0; i < 100; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			c.Increment()
		}()
	}
	wg.Wait()
	fmt.Println(c.Value(), c.Same(c), c.Same(lib.NewCounter(0)))
}
