package api

import (
	"unicode"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// l2detect_claudecode.go — Claude Code title glyphs only (requirement 062).
// 076 §3a 显示名剥前导符号必须走这一套，禁止另写一份符号表。

func init() {
	registerL2Detector("claude_code", claudeCodeDetector{})
}

type claudeCodeDetector struct{}

func claudeCodeStatusRune(r rune) (status string, ok bool) {
	switch r {
	case '\u25D0', '\u25D3', '\u25D1', '\u25D2': // ◐◓◑◒
		return protocol.SessionStatusWorking, true
	case '\u2733': // ✳
		return protocol.SessionStatusIdle, true
	default:
		return "", false
	}
}

func (claudeCodeDetector) Match(title string) (status string, claimed bool) {
	r, ok := firstNonSpace(title)
	if !ok {
		return "", false
	}
	return claudeCodeStatusRune(r)
}

// claudeCodeDisplayName is the 076 §3a display label: pane_title minus the
// 062 status prefix. Identity still uses structural fields + socket.
func claudeCodeDisplayName(title string) string {
	rs := []rune(title)
	i := 0
	for i < len(rs) && unicode.IsSpace(rs[i]) {
		i++
	}
	if i < len(rs) {
		if _, ok := claudeCodeStatusRune(rs[i]); ok {
			i++
			for i < len(rs) && unicode.IsSpace(rs[i]) {
				i++
			}
		}
	}
	return string(rs[i:])
}
