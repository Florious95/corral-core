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

// classifyForProvider runs only the detector registered for providerID, then
// falls back to the shared three-state rule (062).
//
// 🔴 068 §4 原先写的是「已知是哪家但检测器认不出 ⇒ unknown」，那条**盖掉了 062**，
// 是个回归：某些 CLI 刚起会话时标题就是一个光秃秃的产品名（不带该家的空闲后缀），
// 于是本家检测器不认领 ⇒ 整窗判成「未知」。用户实测报「新对话被标记为未知」。
// 正确语义：**无前导符号（字母/数字/空）一律空闲**；unknown 只留给
// **认不出的前导符号**——那才是「某家的判据缺样本」这条真信号。
// ⛔ 本文件是共享层，注释里也不得出现任何具体 CLI 名字（068 §3，判据 grep 反测）。
func classifyForProvider(providerID, title string) (status string, first rune, known bool) {
	r, _ := firstNonSpace(title)
	d, ok := l2ByProvider[providerID]
	if !ok {
		return classifyFallback(title)
	}
	if st, claimed := d.Match(title); claimed {
		return st, r, true
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
