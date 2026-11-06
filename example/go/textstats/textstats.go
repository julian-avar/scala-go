// Package textstats is Go code called from the Scala example (see GoFacades.scala).
package textstats

import (
	"errors"
	"strings"
	"unicode"
)

// Words splits text into words, dropping punctuation.
func Words(text string) []string {
	return strings.FieldsFunc(text, func(r rune) bool { return !unicode.IsLetter(r) && r != '\'' })
}

// Longest returns the longest word, or an error if there are none.
func Longest(words []string) (string, error) {
	if len(words) == 0 {
		return "", errors.New("no words")
	}
	longest := words[0]
	for _, w := range words[1:] {
		if len(w) > len(longest) {
			longest = w
		}
	}
	return longest, nil
}
