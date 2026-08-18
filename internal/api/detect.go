package api

import (
	"fmt"
	"log/slog"
	"unicode"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// detect.go is the shared three-state fallback (requirement 062/068).
// CLI-specific Match implementations register via registerL2Detector.
// This file must not name any concrete CLI.
//
// 068: identity is decided from comm *before* this file runs. Detectors
// are dispatched by provider id — they never compete on one title.

// l2Detector claims a title or leaves it for the unknown path.
type l2Detector interface {
	Match(title string) (status string, claimed bool)
}

var l2ByProvider = map[string]l2Detector{}

func registerL2Detector(providerID string, d l2Detector) {
	if providerID == "" || d == nil {
		return
	}
	l2ByProvider[providerID] = d
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

func logUnknownForProvider(log *slog.Logger, providerID, title string, first rune) {
	if log == nil {
		return
	}
	letter := unicode.IsLetter(first)
	number := unicode.IsNumber(first)
	// Operands then verdict: provider + raw codepoint + full title.
	log.Warn("level2: title unknown for provider",
		"provider", providerID,
		"codepoint", formatCodepoint(first),
		"title", title,
		"is_letter", letter,
		"is_number", number,
		"leading_glyph", leadingGlyph(first),
		"status", protocol.SessionStatusUnknown,
	)
}

func (s *Server) logUnknownForProvider(providerID, title string, first rune) {
	logUnknownForProvider(s.log, providerID, title, first)
}

// classifyForProvider runs only the detector registered for providerID.
// Unclaimed titles are unknown (068: we know the family, not this title).
func classifyForProvider(providerID, title string) (status string, first rune, known bool) {
	r, _ := firstNonSpace(title)
	d, ok := l2ByProvider[providerID]
	if !ok {
		return protocol.SessionStatusUnknown, r, false
	}
	if st, claimed := d.Match(title); claimed {
		return st, r, true
	}
	return protocol.SessionStatusUnknown, r, false
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
