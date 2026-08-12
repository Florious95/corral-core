package agentstate

import (
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// The fixtures below are hand-built approximations of real pane output shapes
// (requirement 012 fixture law: real-shape samples). The Claude Code samples
// mirror what a real pane shows today — a status action bar carrying
// "esc to interrupt" while working, and a rest-state bar with
// "bypass permissions on · N shell" when idle. Screen text (unlike the OSC
// title, which tmux consumes) is what a plain capture-pane window exposes.
// Each is kept as a raw Go string literal so a future CLI redesign that
// changes the wording breaks the corresponding test instead of silently
// degrading to unknown.

const (
	claudeWorkingBar        = "\n\x1b[38;5;131m●\x1b[39m \x1b[38;5;131mbypass\x1b[39m \x1b[38;5;131mpermissions\x1b[39m \x1b[38;5;131mon\x1b[39m (shift+tab to cycle) · esc to interrupt · ctrl+t to hide tasks\n"
	claudeBlockedPermission = "\n\x1b[38;5;16m● \x1b[38;5;105mDo you want to proceed?\x1b[39m\n\x1b[38;5;16m  Bash command:\x1b[39m\n\x1b[38;5;16m  · \x1b[1mgit push\x1b[0m\n\n  (esc to cancel)\x1b[0m\n"
	claudeIdleBar           = "\n\x1b[38;5;241m⠤⠤⠤\x1b[39m \x1b[2m\x1b[38;5;241mCtx: 245213 (25%) | Fable 5 | main\x1b[0m\n\x1b[38;5;131m●\x1b[39m \x1b[38;5;131mbypass\x1b[39m \x1b[38;5;131mpermissions\x1b[39m \x1b[38;5;131mon\x1b[39m (shift+tab to cycle) · \x1b[38;5;241m1 shell\x1b[39m\n"
	codexWorkingFallback    = "\n• Working (…) · esc to interrupt\n"
	codexBlockedPrompt      = "? Allow command?\n  ✓ 1. Yes (y)\n  2. No (n)\n\nPress enter to confirm or esc to cancel\n"
	codexIdlePrompt         = "\n❯ \n"
)

// TestClaudeAdapterFixtureDriven exercises the Claude Code adapter against
// whole-output samples. Every case pairs a fixture with the state the rule
// table must produce; a rule going silent (matching nothing → unknown) fails
// the test rather than quietly degrading, so CLI-wording drift is surfaced
// instead of hidden (requirement 012: positive controls must fail loudly).
func TestClaudeAdapterFixtureDriven(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	cases := []struct {
		name string
		out  string
		want protocol.AgentState
	}{
		{name: "working action bar", out: claudeWorkingBar, want: protocol.StateWorking},
		{name: "blocked permission prompt", out: claudeBlockedPermission, want: protocol.StateBlocked},
		{name: "idle rest bar", out: claudeIdleBar, want: protocol.StateIdle},
		{name: "spinner on line (fallback)", out: "⠋ thinking hard\n", want: protocol.StateWorking},
		{name: "empty output degrades to unknown", out: "", want: protocol.StateUnknown},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := Sample{PaneCommand: "claude", RecentOutput: []byte(tc.out)}
			got := adapter.Detect(s)
			if got.State != tc.want {
				t.Errorf("Detect(%q) = %+v, want %v", tc.out, got, tc.want)
			}
		})
	}
}

// TestCodexAdapterFixtureDriven mirrors the Claude tests for the Codex rule
// table. Codex renders a different prompt ("Allow command?" with a y/n
// selector) and a different working marker (a "• Working (…) · esc to
// interrupt" status line), so its fixtures are separate.
func TestCodexAdapterFixtureDriven(t *testing.T) {
	adapter := &CodexAdapter{}
	cases := []struct {
		name string
		out  string
		want protocol.AgentState
	}{
		{name: "blocked allow-command prompt", out: codexBlockedPrompt, want: protocol.StateBlocked},
		{name: "working status fallback", out: codexWorkingFallback, want: protocol.StateWorking},
		{name: "idle prompt marker", out: codexIdlePrompt, want: protocol.StateIdle},
		{name: "garbage degrades to unknown", out: "\x1b[?25h\x1b[?25l random bytes", want: protocol.StateUnknown},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := Sample{PaneCommand: "codex", RecentOutput: []byte(tc.out)}
			got := adapter.Detect(s)
			if got.State != tc.want {
				t.Errorf("Detect(%q) = %+v, want %v", tc.out, got, tc.want)
			}
		})
	}
}

// TestCodexPromptIdleNotConfusedWithBlocked is the positive control for the
// Codex idle/blocked distinction: both states live at the prompt region, and
// the "Allow command?" blocker must win over the plain "❯" prompt only when a
// y/n selector is actually present. Idle must never read as blocked (a false
// blocked is a wake-up push the user did not ask for, requirement 003).
func TestCodexPromptIdleNotConfusedWithBlocked(t *testing.T) {
	adapter := &CodexAdapter{}
	s := Sample{PaneCommand: "codex", RecentOutput: []byte("❯ ")}
	if got := adapter.Detect(s); got.State != protocol.StateIdle {
		t.Errorf("plain prompt = %+v, want idle", got)
	}
}

// TestRegistryDispatch matches the pane command to the right adapter. A
// dispatch miss must degrade to unknown and never panic.
func TestRegistryDispatch(t *testing.T) {
	reg := DefaultRegistry()

	s := Sample{PaneCommand: "claude", RecentOutput: []byte(claudeWorkingBar)}
	if got := reg.Detect(s); got.State != protocol.StateWorking {
		t.Errorf("claude dispatch = %+v, want working", got)
	}

	s = Sample{PaneCommand: "codex", RecentOutput: []byte(codexBlockedPrompt)}
	if got := reg.Detect(s); got.State != protocol.StateBlocked {
		t.Errorf("codex dispatch = %+v, want blocked", got)
	}

	s = Sample{PaneCommand: "unknown-binary", RecentOutput: []byte(claudeWorkingBar)}
	if got := reg.Detect(s); got.State != protocol.StateUnknown {
		t.Errorf("unrecognized command = %+v, want unknown", got)
	}
}

// TestRegistryKeysAreStable lists the adapter keys: claude and codex are the
// first batch (task contract). Adding a key here without a matching adapter
// fails, and renaming a key is a deliberate breaking decision.
func TestRegistryKeysAreStable(t *testing.T) {
	reg := DefaultRegistry()
	want := []string{"claude", "codex"}
	if len(reg) != len(want) {
		t.Fatalf("registry keys = %v, want %v", keys(reg), want)
	}
	for _, k := range want {
		if _, ok := reg[k]; !ok {
			t.Errorf("missing adapter key %q (have %v)", k, keys(reg))
		}
	}
}

// TestAnsiStrippingDoesNotMaskBlocked is the coupling guard between the ANSI
// cleaner and the rule table: the permission prompt is recognized only after
// SGR sequences are stripped. If the stripper starts eating text, this fails.
func TestAnsiStrippingDoesNotMaskBlocked(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	s := Sample{PaneCommand: "claude", RecentOutput: []byte(claudeBlockedPermission)}
	if got := adapter.Detect(s); got.State != protocol.StateBlocked {
		t.Errorf("blocked prompt after strip = %+v, want blocked", got)
	}
}

// TestLastOutputAgeDoesNotChangeDecision pins that the adapter decision is a
// pure function of the visible output, not of wall-clock age. This is the 008
// isolation law: stale output must not freeze or spin the state machine, and
// the same bytes must yield the same decision regardless of when they were
// captured.
func TestLastOutputAgeDoesNotChangeDecision(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}
	s := Sample{PaneCommand: "claude", RecentOutput: []byte(claudeWorkingBar)}
	fresh := adapter.Detect(s)
	s.LastOutputAge = 5 * time.Minute
	if got := adapter.Detect(s); got.State != fresh.State {
		t.Errorf("age changed decision: fresh=%+v stale=%+v", fresh, got)
	}
}

// TestClaudeTitleSignalKeysState pins the D-26 title-signal fix (task
// fix-state-detection): Claude Code writes a braille spinner in the pane title
// while working and a ✳ (U+2733) prefix while idle, and those markers must
// decide the state even when the screen text would mislead (the idle
// action-bar "for agents" that previously false-matched working).
func TestClaudeTitleSignalKeysState(t *testing.T) {
	adapter := &ClaudeCodeAdapter{}

	// The screen tail that previously false-matched working: an idle status bar
	// whose "for agents" hint is a substring of the working rule's anyContains,
	// plus the bare ❯ prompt the idle-prompt rule keys on. This is the REAL idle
	// screen shape the D-26 probe measured on the fleet (e.g. w-librarian).
	const idleScreenWithAgentsHint = "\n❯ \n⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents\n"

	cases := []struct {
		name  string
		title string
		out   string
		want  protocol.AgentState
	}{
		// Working title (braille spinner) beats an idle-looking screen.
		{name: "spinner title + idle bar", title: "⠙ w-librarian", out: idleScreenWithAgentsHint, want: protocol.StateWorking},
		// Idle title (✳) beats the false-working screen.
		{name: "star title + agents hint", title: "✳ w-librarian", out: idleScreenWithAgentsHint, want: protocol.StateIdle},
		// Empty title falls back to the screen table (the bare prompt reads idle).
		{name: "empty title falls back", title: "", out: idleScreenWithAgentsHint, want: protocol.StateIdle},
		// Blocked screen outranks a working title (a box needs attention).
		{name: "blocked box beats spinner", title: "⠙ w-librarian", out: claudeBlockedPermission, want: protocol.StateBlocked},
		// Spinner in title is working regardless of screen tail.
		{name: "spinner title working", title: "⠹ agent", out: "❯\n", want: protocol.StateWorking},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := Sample{PaneCommand: "claude", PaneTitle: tc.title, RecentOutput: []byte(tc.out)}
			if got := adapter.Detect(s); got.State != tc.want {
				t.Errorf("Detect(title=%q, out=%q) = %+v, want %v", tc.title, tc.out, got, tc.want)
			}
		})
	}
}

func keys(m map[string]Adapter) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}
