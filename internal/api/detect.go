package api

import (
	"fmt"
	"log/slog"
	"unicode"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// detect.go is the shared three-state fallback (requirement 062).
// CLI-specific Match implementations register via registerL2Detector.
// This file must not name any concrete CLI.

// l2Detector claims a title or leaves it for the fallback.
type l2Detector interface {
	Match(title string) (status string, claimed bool)
}

var l2Detectors []l2Detector

func registerL2Detector(d l2Detector) {
	l2Detectors = append(l2Detectors, d)
}

func firstNonSpace(title string) (r rune, ok bool) {
	for _, r := range title {
		if !unicode.IsSpace(r) {
			return r, true
		}
	}
	return 0, false
}

// leadingGlyph reports a first scalar that is neither Letter nor Number.
// Shared layer must not enumerate symbol blocks; detectors claim those.
func leadingGlyph(r rune) bool {
	return !unicode.IsLetter(r) && !unicode.IsNumber(r)
}

func formatCodepoint(r rune) string {
	return fmt.Sprintf("U+%04X", uint32(r))
}

func logUnknownGlyph(log *slog.Logger, title string, first rune) {
	if log == nil {
		return
	}
	letter := unicode.IsLetter(first)
	number := unicode.IsNumber(first)
	// Operands then verdict: raw codepoint + full title + the two
	// comparisons that sent this title down the unknown path.
	log.Warn("level2: pane_title glyph unknown",
		"codepoint", formatCodepoint(first),
		"title", title,
		"is_letter", letter,
		"is_number", number,
		"leading_glyph", leadingGlyph(first),
		"status", protocol.SessionStatusUnknown,
	)
}

func (s *Server) logUnknownGlyph(title string, first rune) {
	logUnknownGlyph(s.log, title, first)
}

// classifyPaneTitle walks registered detectors, then the three-state fallback.
// No leading glyph (empty / letter / number) is idle. An unclaimed leading
// glyph is unknown (known=false) so the caller can log codepoint + title.
func classifyPaneTitle(title string) (status string, first rune, known bool) {
	for _, d := range l2Detectors {
		if st, claimed := d.Match(title); claimed {
			r, _ := firstNonSpace(title)
			return st, r, true
		}
	}
	return classifyFallback(title)
}

func classifyFallback(title string) (status string, first rune, known bool) {
	r, ok := firstNonSpace(title)
	if !ok {
		return protocol.SessionStatusIdle, 0, true
	}
	letter := unicode.IsLetter(r)
	number := unicode.IsNumber(r)
	if !letter && !number {
		return protocol.SessionStatusUnknown, r, false
	}
	return protocol.SessionStatusIdle, r, true
}
