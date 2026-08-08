package agentstate

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestANSIStripperRedlines locks in the ANSI stripping contract (requirement
// 008: state parsing must never break on terminal garbage). The stripper is a
// tiny self-contained state machine; these cases pin the boundary conditions
// that are easy to regress: SGR/OSC/CSI sequences, stray ESC at end of input,
// and raw printable bytes that must survive untouched.
func TestANSIStripperRedlines(t *testing.T) {
	cases := []struct {
		name  string
		input string
		want  string
	}{
		{
			name:  "SGR color sequences stripped",
			input: "\x1b[38;5;16mcommit/push\x1b[39m",
			want:  "commit/push",
		},
		{
			name:  "cursor movement CSI stripped",
			input: "head\x1b[1;1Htail",
			want:  "headtail",
		},
		{
			name:  "braille spinner OSC sequence stripped",
			input: "\x1b]0;⠐\x07",
			want:  "",
		},
		{
			name:  "terminal reset and cursor-visible OSC",
			input: "a\x1b[0mb\x1b[?25h\x1b[?25lc",
			want:  "abc",
		},
		{
			name:  "multibyte utf8 preserved",
			input: "❯ 提问",
			want:  "❯ 提问",
		},
		{
			name:  "trailing partial ESC degrades to nothing",
			input: "rest\x1b",
			want:  "rest",
		},
		{
			name:  "bell ignored outside OSC",
			input: "\x07ring\x07",
			want:  "ring",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := stripANSI(tc.input); got != tc.want {
				t.Errorf("stripANSI(%q) = %q, want %q", tc.input, got, tc.want)
			}
		})
	}
}

// TestTrackWorkingToIdleEdge pins the done approximation: the transition from
// working to idle is exactly the "agent stopped working" edge (requirement
// 003: done/blocked drive wake-up push). The tracker must be a pure function
// of (prev, sample) — no internal state — so a stateless server can replay it.
func TestTrackWorkingToIdleEdge(t *testing.T) {
	// The idle sample must genuinely detect as idle (a rest-state bar), or the
	// working→idle edge would not fire. Reuses the adapter fixture so the edge
	// logic is tested against the same real-shape output the rule tables see.
	sample := Sample{PaneCommand: "claude", RecentOutput: []byte(claudeIdleBar), LastOutputAge: 0}
	t.Run("working then idle flags done", func(t *testing.T) {
		got := Track(protocol.StateWorking, sample)
		if got.State != protocol.StateDone || got.Confidence <= 0 {
			t.Errorf("Track(working→idle) = %+v, want done with confidence", got)
		}
	})
	t.Run("idle then idle stays idle", func(t *testing.T) {
		got := Track(protocol.StateIdle, sample)
		if got.State != protocol.StateIdle {
			t.Errorf("Track(idle→idle) = %+v, want idle", got)
		}
	})
	t.Run("unknown prev cannot synthesize done", func(t *testing.T) {
		got := Track(protocol.StateUnknown, sample)
		if got.State != protocol.StateIdle {
			t.Errorf("Track(unknown→idle) = %+v, want idle (not done)", got)
		}
	})
}

// TestTrackWorkingToUnknownDoesNotSynthesizeDone pins the isolation edge: a
// blank/unreadable sample is not proof of completion, so the done edge must
// not fire from working→unknown (only from working→idle). A false done would
// send a wake-up notification the user did not ask for (requirement 003).
func TestTrackWorkingToUnknownDoesNotSynthesizeDone(t *testing.T) {
	sample := Sample{PaneCommand: "claude", RecentOutput: []byte(""), LastOutputAge: 0}
	got := Track(protocol.StateWorking, sample)
	if got.State != protocol.StateUnknown {
		t.Errorf("Track(working→blank) = %+v, want unknown (not done)", got)
	}
}
