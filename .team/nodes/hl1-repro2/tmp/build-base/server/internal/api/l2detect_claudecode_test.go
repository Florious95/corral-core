package api

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestL2ClaudeCodeDetectorWorking(t *testing.T) {
	d := claudeCodeDetector{}
	for _, title := range []string{
		"◐ w-librarian",
		"◓ spinning",
		"◑ right-half",
		"◒ lower-half",
		"  ◐ leading-space",
	} {
		st, claimed := d.Match(title)
		if !claimed || st != protocol.SessionStatusWorking {
			t.Fatalf("Match(%q): status=%q claimed=%v → want working claimed=true", title, st, claimed)
		}
		st2, _, known := classifyForProvider("claude_code", title)
		if st2 != protocol.SessionStatusWorking || !known {
			t.Fatalf("classify(%q): status=%q known=%v → want working", title, st2, known)
		}
	}
}

func TestL2ClaudeCodeDetectorIdle(t *testing.T) {
	d := claudeCodeDetector{}
	title := "✳ dev-state"
	st, claimed := d.Match(title)
	if !claimed || st != protocol.SessionStatusIdle {
		t.Fatalf("Match(%q): status=%q claimed=%v → want idle claimed=true", title, st, claimed)
	}
	st2, _, known := classifyForProvider("claude_code", title)
	if st2 != protocol.SessionStatusIdle || !known {
		t.Fatalf("classify(%q): status=%q known=%v → want idle", title, st2, known)
	}
	// Must not claim titles this CLI does not own.
	if _, claimed := d.Match("hello"); claimed {
		t.Fatal("detector claimed a title with no Claude Code glyph")
	}
}

func TestClaudeCodeDisplayNameStrips062PrefixKeepsCJK(t *testing.T) {
	cases := []struct {
		title string
		want  string
	}{
		{"✳ 远控 leader", "远控 leader"},
		{"◐ 多agent协作", "多agent协作"},
		{"  ◓  spinning-name", "spinning-name"},
		{"far-ctrl", "far-ctrl"},
		{"", ""},
	}
	for _, tc := range cases {
		got := claudeCodeDisplayName(tc.title)
		if got != tc.want {
			t.Fatalf("claudeCodeDisplayName(%q) = %q, want %q", tc.title, got, tc.want)
		}
	}
	// Grok-style titles must not be fed through this helper by callers;
	// the function itself is prefix-only and must not invent numbered aliases.
	if got := claudeCodeDisplayName("Team Agent message from leader"); got != "Team Agent message from leader" {
		t.Fatalf("non-glyph title mutated: %q", got)
	}
}
