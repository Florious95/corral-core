package api

import (
	"strings"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// l2detect_grok.go — Grok CLI title marks only (requirement 062).
// Idle titles keep the last task summary; never classify from summary text.

func init() {
	registerL2Detector("grok", grokDetector{})
}

type grokDetector struct{}

const (
	grokThinkMark  = " - Thinking - "
	grokWaitMark   = " - Waiting for response"
	grokIdleSuffix = " - grok"
	grokBrailleMin = '\u2800'
	grokBrailleMax = '\u28FF'
)

func (grokDetector) Match(title string) (status string, claimed bool) {
	r, ok := firstNonSpace(title)
	if ok && r >= grokBrailleMin && r <= grokBrailleMax {
		return protocol.SessionStatusWorking, true
	}
	if strings.Contains(title, grokThinkMark) || strings.Contains(title, grokWaitMark) {
		return protocol.SessionStatusWorking, true
	}
	if strings.HasSuffix(title, grokIdleSuffix) && (!ok || !leadingGlyph(r)) {
		return protocol.SessionStatusIdle, true
	}
	return "", false
}
