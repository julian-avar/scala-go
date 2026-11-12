// Package style is used by package elm's API only: its facades are generated as a dependency.
package style

import "strings"

// Style is a struct used by value, like lipgloss.Style.
type Style struct {
	Upper  bool
	Prefix string
}

func (s Style) Render(text string) string {
	if s.Upper {
		text = strings.ToUpper(text)
	}
	return s.Prefix + text
}
