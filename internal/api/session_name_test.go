package api

// session_name_test.go pins the display-name resolution of toSession (task
// fix-session-alias): the client renders the tmux window name when the pane
// carries one, and falls back to the tmux session name when it does not.
// Pure logic tests: no tmux, no WebSocket.

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
)

// TestToSessionPrefersWindowName pins the primary rule: a pane whose window
// name was discovered must render that name (e.g. "wiki-r5-acceptance-tester"),
// not the whole-team session name it lives under.
func TestToSessionPrefersWindowName(t *testing.T) {
	pane := discovery.Pane{
		Socket:      "/sock",
		Session:     "team-refactor-maintainability",
		WindowIndex: 0,
		WindowName:  "wiki-r5-acceptance-tester",
		PaneID:      "%0",
		CWD:         "/ws/a",
		Command:     "claude",
		Width:       80,
		Height:      24,
	}
	e := &sessionEntry{ref: sessionRef(pane), pane: pane}
	s := toSession(e, "")
	if s.Name != "wiki-r5-acceptance-tester" {
		t.Fatalf("toSession name = %q, want the window name %q", s.Name, "wiki-r5-acceptance-tester")
	}
}

// TestToSessionFallsBackToSessionName pins the fallback rule: a pane without a
// window name (scan could not produce one) renders the tmux session name.
func TestToSessionFallsBackToSessionName(t *testing.T) {
	pane := discovery.Pane{
		Socket:      "/sock",
		Session:     "team-refactor-maintainability",
		WindowIndex: 0,
		PaneID:      "%0",
		CWD:         "/ws/a",
		Command:     "claude",
		Width:       80,
		Height:      24,
	}
	e := &sessionEntry{ref: sessionRef(pane), pane: pane}
	s := toSession(e, "")
	if s.Name != "team-refactor-maintainability" {
		t.Fatalf("toSession name = %q, want the session fallback %q", s.Name, "team-refactor-maintainability")
	}
}
