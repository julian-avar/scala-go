// A Go program using exported Scala code with many types.
package main

import (
	"errors"
	"fmt"
	"strings"

	lib "scalagolib"
)

func main() {
	fmt.Println(lib.Add(2, 0.5))
	fmt.Println(lib.Describe(true, -3, 'é', -128, 1.5))
	fmt.Println(lib.Shout("grüße"))
	fmt.Println(lib.Utf16Length("a😀"))

	ints := []int32{1, 2, 3}
	fmt.Println(lib.Reversed(ints), ints)
	fmt.Println(lib.Longs(4))
	buf := []byte("abc")
	lib.Clear(buf)
	fmt.Println(buf)
	fmt.Printf("%q\n", lib.Words("to be or"))

	c := make(chan int, 3)
	c <- 1
	c <- 2
	c <- 3
	close(c)
	fmt.Println(lib.Total(c))
	n := 41
	lib.Bump(&n)
	fmt.Println(n)

	var b strings.Builder
	lib.Write(&b, "x")
	lib.Write(&b, "y")
	fmt.Println(b.String())

	fmt.Println(lib.Check(nil))
	fmt.Println(lib.Check(errors.New("from Go")))
	fmt.Println(lib.Failure("") == nil, lib.Failure("from Scala"))

	add3 := lib.Adder(3)
	fmt.Println(add3(4), add3(10))
	fmt.Println(lib.ApplyTwice(func(s string, i int32) string { return fmt.Sprintf("%s-%d", s, i) }, "s"))

	var animals []lib.Animal
	animals = append(animals, lib.NewDog("rex"), lib.NewPuppy("bit", 3))
	for _, a := range animals {
		fmt.Println(a.Speak(), "/", a.Name(), "/", a.Sound())
	}
	p := lib.NewPuppy("pip", 2)
	fmt.Println(p.Speak(), p.Age())
	var d lib.Dog = p
	fmt.Println(d.Speak())
	fmt.Println(lib.Keep(p) == lib.Animal(p), lib.Keep(p) == animals[1])

	fmt.Println("before first use")
	fmt.Println(lib.Register("a"))
	fmt.Println(lib.Register("b"))

	lib.Log("one")
	fmt.Println("go after one")
	lib.Log("two")
	fmt.Println("go after two")
}
