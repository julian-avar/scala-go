package main

import (
	"fmt"

	"scalagolib"
)

func main() {
	p := scalagolib.NewPoint(1)
	fmt.Println(scalagolib.Answer, p.X(), p.Sum())
}
