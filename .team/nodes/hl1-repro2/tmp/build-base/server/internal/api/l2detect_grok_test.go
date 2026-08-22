package api

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestL2GrokDetectorWorking(t *testing.T) {
	d := grokDetector{}
	// Contract 062 sample: braille U+283C + wait mark.
	title := "⠼ - Waiting for response… - 修滚动摘要"
	st, claimed := d.Match(title)
	if !claimed || st != protocol.SessionStatusWorking {
		t.Fatalf("Match(%q): status=%q claimed=%v → want working claimed=true", title, st, claimed)
	}
	st2, first, known := classifyForProvider("grok", title)
	if st2 != protocol.SessionStatusWorking || !known {
		t.Fatalf("classify(%q): status=%q known=%v first=U+%04X → want working", title, st2, known, first)
	}
	if first != '\u283C' {
		t.Fatalf("first=U+%04X, want U+283C", first)
	}

	think := " - Thinking - mid-title without braille"
	st, claimed = d.Match(think)
	if !claimed || st != protocol.SessionStatusWorking {
		t.Fatalf("Match think-mark %q: status=%q claimed=%v → want working", think, st, claimed)
	}
}

func TestL2GrokDetectorIdle(t *testing.T) {
	d := grokDetector{}
	title := "修滚动摘要 - grok"
	st, claimed := d.Match(title)
	if !claimed || st != protocol.SessionStatusIdle {
		t.Fatalf("Match(%q): status=%q claimed=%v → want idle claimed=true (not unknown)", title, st, claimed)
	}
	st2, _, known := classifyForProvider("grok", title)
	if st2 != protocol.SessionStatusIdle || !known {
		t.Fatalf("classify(%q): status=%q known=%v → want idle, not unknown", title, st2, known)
	}

	// Summary may contain the word Thinking; only the mark " - Thinking - " is live.
	summaryThink := "Thinking about the scroll bug - grok"
	st, claimed = d.Match(summaryThink)
	if !claimed || st != protocol.SessionStatusIdle {
		t.Fatalf("Match(%q): status=%q claimed=%v → want idle (summary ≠ mark)", summaryThink, st, claimed)
	}
	st2, _, known = classifyForProvider("grok", summaryThink)
	if st2 != protocol.SessionStatusIdle || !known {
		t.Fatalf("classify(%q): status=%q → want idle (must not flip on summary text)", summaryThink, st2)
	}
}
