package agentstate

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// D-26 red tests: the acceptance criteria for the working-indicator regression.
//
//  1. A pane showing the new ◐-family indicator must read working (immediate
//     fix, layer ①).
//  2. A static pane must read idle.
//  3. The glyph-independent criterion (layer ②) must read working from a glyph
//     family the rule tables have never seen — only then is detection truly
//     decoupled from the whitelist, and a future upstream redraw cannot break
//     it again.
//  4. The rule tables stay authoritative where they already decide: a blocked
//     box outranks a churning window, a recognized working bar never reads
//     idle-from-static, and a bare idle prompt never reads working just
//     because the pane content churns underneath.

// realWorkingIndicator is a whole-window capture of the Claude Code status bar
// as it reads today, with the new half-fill-circle working indicator in the
// title region (D-26 field evidence: window title "◐w-dev-cols").
const realWorkingIndicator = "\n\x1b[38;5;131m●\x1b[39m bypass permissions on · ◐ esc to interrupt · ctrl+t to hide tasks\n"

// TestNewIndicatorReadsWorking is acceptance ①: the immediate fix. The pane
// renders the half-fill circle the rule tables previously did not know, with
// the working action-bar wording clipped out of the tail so only the spinner
// fallback can fire. It must read working.
func TestNewIndicatorReadsWorking(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	s := Sample{PaneCommand: "claude", RecentOutput: []byte(realWorkingIndicator)}
	if got := adapter.Detect(s); got.State != protocol.StateWorking {
		t.Errorf("Detect(◐ indicator) = %+v, want working", got)
	}
}

// TestNewIndicatorSequenceEachFrameWorking covers the full ◐◑◒◓◔◕ rotation: any
// single frame of the new spinner on a line must be a working signal, not just
// the one that happened to appear in the field screenshot.
func TestNewIndicatorSequenceEachFrameWorking(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	for _, r := range "◐◑◒◓◔◕✳" {
		out := "◐ title w-dev-cols:   \"" + string(r) + "w-dev-cols\"\n"
		s := Sample{PaneCommand: "claude", RecentOutput: []byte(out)}
		if got := adapter.Detect(s); got.State != protocol.StateWorking {
			t.Errorf("Detect(quoted %q frame) = %+v, want working", string(r), got)
		}
	}
}

// TestStaticFrameReadsIdle is acceptance ②: a static pane is idle. Two
// identical captures — nothing redrawn — must read idle, never unknown, so an
// idle pane is distinguishable from an undecidable one.
func TestStaticFrameReadsIdle(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	frames := [][]byte{
		[]byte("w-dev-cols  \x1b[38;5;131m●\x1b[39m bypass permissions on · 1 shell"),
		[]byte("w-dev-cols  \x1b[38;5;131m●\x1b[39m bypass permissions on · 1 shell"),
	}
	s := Sample{PaneCommand: "claude", FrameHistory: frames}
	if got := adapter.Detect(s); got.State != protocol.StateIdle {
		t.Errorf("Detect(static frames) = %+v, want idle", got)
	}
}

// TestNeverSeenGlyphStillReadsWorking is acceptance ③: the glyph-independent
// criterion. The clock-emoji frame family below exists in no rule table, no
// spinnerFrames entry, nowhere in the package — yet a pane cycling through it
// is provably redrawing, so it must read working. This is the test that would
// have caught the D-26 regression had it existed before the indicator change,
// and it is the guard that keeps the fix from re-whitelisting.
func TestNeverSeenGlyphStillReadsWorking(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	frames := [][]byte{
		[]byte("w-dev-cols  🕐 claude"),
		[]byte("w-dev-cols  🕑 claude"),
		[]byte("w-dev-cols  🕒 claude"),
		[]byte("w-dev-cols  🕓 claude"),
	}
	s := Sample{PaneCommand: "claude", FrameHistory: frames}
	if got := adapter.Detect(s); got.State != protocol.StateWorking {
		t.Errorf("Detect(never-seen glyph frames) = %+v, want working (glyph-independent)", got)
	}
}

// TestStaticNeverSeenGlyphReadsIdle flips the same unknown-glyph scenario to
// static: two identical clock frames are idle, proving the criterion reads
// change and not glyph presence.
func TestStaticNeverSeenGlyphReadsIdle(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	frames := [][]byte{
		[]byte("w-dev-cols  🕐 claude"),
		[]byte("w-dev-cols  🕐 claude"),
	}
	s := Sample{PaneCommand: "claude", FrameHistory: frames}
	if got := adapter.Detect(s); got.State != protocol.StateIdle {
		t.Errorf("Detect(static never-seen glyph frames) = %+v, want idle", got)
	}
}

// TestSingleFrameDegradesToUnknown pins the <2-frame boundary: one capture has
// no temporal information, so the fallback must degrade to unknown rather than
// guess. It also documents that the current production path (single capture,
// no FrameHistory) is unchanged by layer ②.
func TestSingleFrameDegradesToUnknown(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	s := Sample{PaneCommand: "claude", RecentOutput: []byte("some pane text"),
		FrameHistory: [][]byte{[]byte("some pane text")}}
	if got := adapter.Detect(s); got.State != protocol.StateUnknown {
		t.Errorf("Detect(single frame) = %+v, want unknown", got)
	}
}

// TestNilFrameHistoryDegradesToUnknown pins the current production shape: a
// producer that hands one RecentOutput and no FrameHistory keeps the exact
// pre-layer-② behavior — rule tables, then unknown. This is the isolation
// guarantee that adding the fallback did not widen the default decision path.
func TestNilFrameHistoryDegradesToUnknown(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	s := Sample{PaneCommand: "claude", RecentOutput: []byte("some pane text")}
	if got := adapter.Detect(s); got.State != protocol.StateUnknown {
		t.Errorf("Detect(no history) = %+v, want unknown", got)
	}
}

// TestActivityOnlyOnANSIChurnIsIdle pins that the activity signal reads visible
// content, not terminal escape noise: two captures with different SGR byte
// sequences but identical visible text are still idle. A CLI repainting color
// codes without content change is not working.
func TestActivityOnlyOnANSIChurnIsIdle(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	frames := [][]byte{
		[]byte("status  \x1b[38;5;131m●\x1b[39m bar"),
		[]byte("status  \x1b[38;5;241m●\x1b[39m bar"),
	}
	s := Sample{PaneCommand: "claude", FrameHistory: frames}
	if got := adapter.Detect(s); got.State != protocol.StateIdle {
		t.Errorf("Detect(ANSI-only churn) = %+v, want idle (visible content unchanged)", got)
	}
}

// TestBlockedOutranksActivity pins coexistence rule ①: the rule tables stay
// authoritative. A churning pane behind a permission confirmation box must read
// blocked, never working — the tables decide first and the activity fallback
// never overrides a recognized interactive state.
func TestBlockedOutranksActivity(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	frames := [][]byte{
		[]byte("\x1b[38;5;105mDo you want to proceed?\x1b[39m  (esc to cancel)  🕐"),
		[]byte("\x1b[38;5;105mDo you want to proceed?\x1b[39m  (esc to cancel)  🕑"),
	}
	s := Sample{PaneCommand: "claude",
		RecentOutput: []byte("\x1b[38;5;105mDo you want to proceed?\x1b[39m  (esc to cancel)"),
		FrameHistory: frames,
	}
	if got := adapter.Detect(s); got.State != protocol.StateBlocked {
		t.Errorf("Detect(churn behind blocked box) = %+v, want blocked (table wins)", got)
	}
}

// TestWorkingBarOutranksStaticIdle pins coexistence rule ②: a recognized
// working action bar must never read idle-from-static, even with two identical
// frames sitting in the window.
func TestWorkingBarOutranksStaticIdle(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	frames := [][]byte{
		[]byte(claudeWorkingBar),
		[]byte(claudeWorkingBar),
	}
	s := Sample{PaneCommand: "claude",
		RecentOutput: []byte(claudeWorkingBar),
		FrameHistory: frames,
	}
	if got := adapter.Detect(s); got.State != protocol.StateWorking {
		t.Errorf("Detect(working bar + static frames) = %+v, want working (table wins)", got)
	}
}

// TestIdlePromptNotOverturnedByActivity pins coexistence rule ③: a bare idle
// prompt is a table decision and must win over a churning FrameHistory — the
// input line itself blinks or the pane scrolls without the agent doing work, so
// content churn below a prompt is not grounds to flip idle to working.
func TestIdlePromptNotOverturnedByActivity(t *testing.T) {
	adapter := &CodexAdapter{}
	// The frames genuinely churn (each differs from the previous), so the
	// activity fallback would say working on its own — but the rule table's
	// bare-❯ idle rule runs first and must win.
	frames := [][]byte{
		[]byte("old tool output line one\n❯ "),
		[]byte("old tool output line two\n❯ "),
		[]byte("old tool output line three\n❯ "),
	}
	s := Sample{PaneCommand: "codex",
		RecentOutput: []byte("\n❯ "),
		FrameHistory: frames,
	}
	if got := adapter.Detect(s); got.State != protocol.StateIdle {
		t.Errorf("Detect(idle prompt + churn) = %+v, want idle (table wins)", got)
	}
}
