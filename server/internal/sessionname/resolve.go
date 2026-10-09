// Package sessionname is the single production Session.name algorithm.
//
// Every Agent session — Codex, Pi, Claude, Grok, Cursor, unknown, or a
// future provider — uses the same pure function. It reads only already
// discovered tmux fields and never Provider identity, native session_name,
// process trees, or Provider-private indexes.
//
// @consumes none
// @produces internal/sessionname
package sessionname

import (
	"strings"
	"unicode"

	"golang.org/x/text/cases"
	"golang.org/x/text/unicode/norm"
)

// Source identifies which input produced the resolved display name.
const (
	SourceWindow      = "window"
	SourceTitle       = "title"
	SourceProject     = "project"
	SourcePlaceholder = "placeholder"
)

// Placeholder is the display name when no window, title, or project
// candidate survives filtering.
const Placeholder = "未命名会话"

// Resolved is the display name written to protocol Session.name, plus the
// diagnostic source used by tests. Source is not a wire field.
type Resolved struct {
	Value  string
	Source string
}

var noiseKeys = func() map[string]struct{} {
	// One global table. Do not branch on Provider to pick extra labels.
	labels := []string{
		"tmux", "node", "nodejs", "sh", "bash", "zsh", "fish", "dash", "ksh", "csh", "tcsh",
		"ssh", "sshd", "login", "env", "sudo", "π", "claude_code",
	}
	out := make(map[string]struct{}, len(labels))
	for _, label := range labels {
		out[key(label)] = struct{}{}
	}
	return out
}()

var statusDecorations = []string{"◐", "◓", "◑", "◒", "✳", "●", "○", "◌", "✓", "✔"}

type rank struct {
	project int
	title   int
	pos     int
}

func (a rank) less(b rank) bool {
	if a.project != b.project {
		return a.project < b.project
	}
	if a.title != b.title {
		return a.title < b.title
	}
	return a.pos < b.pos
}

type candidate struct {
	text        string
	source      string
	projectOnly bool
	rank        rank
}

// Resolve picks one display name from a pane's tmux window name, pane title,
// working directory, and optional foreground command. The function does not
// accept Provider, status, or native session_name.
func Resolve(windowName, paneTitle, cwd, currentCommand string) Resolved {
	project := lexicalProjectBasename(cwd)
	executable := executableBasename(currentCommand)

	type input struct {
		raw, source string
		pos         int
	}
	inputs := []input{{windowName, SourceWindow, 0}}
	for i, part := range splitTitleFields(clean(paneTitle)) {
		inputs = append(inputs, input{part, SourceTitle, i})
	}

	bestByKey := map[string]candidate{}
	for _, in := range inputs {
		text := clean(in.raw)
		if text == "" {
			continue
		}
		projectOnly := project != "" && matchesProject(text, project, cwd)
		if !projectOnly && isNoise(text, executable) {
			continue
		}
		// A valid title is preferred over a valid window name. Keep the
		// original title position as the tie-breaker so title segments still
		// win from left to right.
		titleBit := 0
		if in.source == SourceWindow {
			titleBit = 1
		}
		projectBit := 0
		if projectOnly {
			projectBit = 1
		}
		c := candidate{
			text:        text,
			source:      in.source,
			projectOnly: projectOnly,
			rank:        rank{projectBit, titleBit, in.pos},
		}
		k := key(text)
		if prev, ok := bestByKey[k]; ok && !c.rank.less(prev.rank) {
			continue
		}
		bestByKey[k] = c
	}

	var best *candidate
	for _, c := range bestByKey {
		if best == nil || c.rank.less(best.rank) {
			cp := c
			best = &cp
		}
	}
	if best != nil && !best.projectOnly {
		return Resolved{Value: best.text, Source: best.source}
	}
	if project != "" {
		return Resolved{Value: project, Source: SourceProject}
	}
	return Resolved{Value: Placeholder, Source: SourcePlaceholder}
}

func clean(s string) string {
	for {
		next := cleanStep(s)
		if next == s {
			return next
		}
		s = next
	}
}

func cleanStep(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		if unicode.IsControl(r) {
			if unicode.IsSpace(r) {
				b.WriteByte(' ')
			}
			continue
		}
		b.WriteRune(r)
	}
	s = strings.TrimSpace(b.String())
	if n := len(s); n >= 2 {
		if (s[0] == '"' && s[n-1] == '"') || (s[0] == '\'' && s[n-1] == '\'') {
			s = strings.TrimSpace(s[1 : n-1])
		}
	}
	for {
		trimmed := false
		for _, d := range statusDecorations {
			if strings.HasPrefix(s, d) {
				s = strings.TrimLeftFunc(strings.TrimPrefix(s, d), unicode.IsSpace)
				trimmed = true
				break
			}
		}
		if trimmed {
			continue
		}
		withoutBraille := strings.TrimLeftFunc(s, func(r rune) bool {
			return unicode.IsSpace(r) || (r >= '\u2800' && r <= '\u28ff')
		})
		if withoutBraille != s {
			s = withoutBraille
			continue
		}
		break
	}
	return strings.TrimRight(s, "*")
}

func splitTitleFields(title string) []string {
	if title == "" {
		return nil
	}
	runes := []rune(title)
	var parts []string
	start := 0
	for i := 0; i < len(runes); i++ {
		r := runes[i]
		if r == '|' {
			parts = append(parts, string(runes[start:i]))
			start = i + 1
			continue
		}
		if r == '-' && i > 0 && i+1 < len(runes) && unicode.IsSpace(runes[i-1]) && unicode.IsSpace(runes[i+1]) {
			parts = append(parts, string(runes[start:i]))
			start = i + 1
		}
	}
	return append(parts, string(runes[start:]))
}

func lexicalProjectBasename(cwd string) string {
	trimmed := trimTrailingSeparators(cwd)
	if trimmed == "" {
		return ""
	}
	base := lastSegment(trimmed)
	if base == "" || base == "." || base == ".." {
		return ""
	}
	return base
}

func executableBasename(command string) string {
	command = strings.TrimSpace(command)
	if command == "" {
		return ""
	}
	base := lastSegment(command)
	if base == "" || base == "." || base == ".." {
		return command
	}
	return base
}

func trimTrailingSeparators(p string) string {
	return strings.TrimRight(p, `/\`)
}

func lastSegment(p string) string {
	p = trimTrailingSeparators(p)
	if p == "" {
		return ""
	}
	if i := strings.LastIndexAny(p, `/\`); i >= 0 {
		return p[i+1:]
	}
	return p
}

func matchesProject(text, project, cwd string) bool {
	k := key(text)
	if k == key(project) {
		return true
	}
	trimmed := trimTrailingSeparators(cwd)
	return trimmed != "" && k == key(trimmed)
}

func isNoise(text, executable string) bool {
	if text == "" || isSeparatorOnly(text) {
		return true
	}
	form := stripNoiseDecorations(text)
	if form == "" || isSeparatorOnly(form) {
		return true
	}
	k := key(form)
	if _, ok := noiseKeys[k]; ok {
		return true
	}
	if executable != "" && (k == key(executable) || key(text) == key(executable)) {
		return true
	}
	return false
}

func isSeparatorOnly(s string) bool {
	if s == "" {
		return true
	}
	for _, r := range s {
		if r != '|' && r != '-' && !unicode.IsSpace(r) {
			return false
		}
	}
	return true
}

func stripNoiseDecorations(s string) string {
	for {
		t := strings.TrimSpace(s)
		if len(t) >= 2 && t[0] == '[' && t[len(t)-1] == ']' {
			s = t[1 : len(t)-1]
			continue
		}
		s = t
		break
	}
	return strings.TrimSpace(strings.TrimRight(s, "*!#~"))
}

func key(text string) string {
	return cases.Fold().String(norm.NFC.String(text))
}
