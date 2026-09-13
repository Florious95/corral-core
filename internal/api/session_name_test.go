package api

// session_name_test.go pins display-name resolution of toSession.
// Requirement 023's unconditional window_name (then tmux session fallback) is
// replaced by requirement 101: meaningful non-project window names still win;
// the tmux session team name is never a fallback.

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/sessionname"
)

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
	s := toSession(e)
	if s.Name != "wiki-r5-acceptance-tester" {
		t.Fatalf("toSession name = %q, want the window name %q", s.Name, "wiki-r5-acceptance-tester")
	}
}

func TestToSessionDoesNotFallBackToTmuxSessionName(t *testing.T) {
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
	s := toSession(e)
	if s.Name == pane.Session {
		t.Fatalf("toSession used tmux session %q", s.Name)
	}
	if s.Name != "a" {
		t.Fatalf("toSession name = %q, want project basename from cwd (old rule: tmux session)", s.Name)
	}
	if sessionname.Resolve(pane.WindowName, pane.PaneTitle, pane.CWD, pane.Command).Value != s.Name {
		t.Fatal("toSession diverged from sessionname.Resolve")
	}
}
