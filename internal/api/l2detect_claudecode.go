package api

import "github.com/agentmirror/agentmirror/internal/protocol"

// l2detect_claudecode.go — Claude Code title glyphs only (requirement 062).

func init() {
	registerL2Detector(claudeCodeDetector{})
}

type claudeCodeDetector struct{}

func (claudeCodeDetector) Match(title string) (status string, claimed bool) {
	r, ok := firstNonSpace(title)
	if !ok {
		return "", false
	}
	switch r {
	case '\u25D0', '\u25D3', '\u25D1', '\u25D2': // ◐◓◑◒
		return protocol.SessionStatusWorking, true
	case '\u2733': // ✳
		return protocol.SessionStatusIdle, true
	default:
		return "", false
	}
}
