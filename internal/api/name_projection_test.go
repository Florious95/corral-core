package api

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func paneForName(provider, title, window, session string, index int) (discovery.Pane, nodeprobe.Observation) {
	return discovery.Pane{
		Socket: "/synthetic/name.sock", Session: session, WindowIndex: index,
		WindowName: window, PaneID: "%0", CWD: "/synthetic/workspace",
		PaneTitle: title, Width: 120, Height: 40,
	}, nodeprobe.Observation{Provider: provider, Activity: "idle", Health: "normal"}
}

func TestProviderAwareDisplayNamesAndStructuralFields(t *testing.T) {
	piName := "派席-中文"
	tests := []struct {
		name     string
		provider string
		title    string
		window   string
		session  string
		piName   *string
		want     string
	}{
		{name: "codex keeps complete title", provider: "codex", title: "编码甲 | 远程Agent安卓", window: "node", session: "codex-a", want: "编码甲 | 远程Agent安卓"},
		{name: "codex empty title is unknown", provider: "codex", window: "node", session: "codex-empty", want: ""},
		{name: "pi uses authoritative session name", provider: "pi", title: "π stale title", window: "node", session: "pi-a", piName: &piName, want: piName},
		{name: "pi missing name stays unknown", provider: "pi", window: "node", session: "pi-missing", want: ""},
		{name: "pi conflicting name stays unknown", provider: "pi", window: "node", session: "pi-conflict", piName: nil, want: ""},
		{name: "claude window rule unchanged", provider: "claude", title: "◐ claude", window: "claude-window", session: "claude-session", want: "claude-window"},
		{name: "grok window rule unchanged", provider: "grok", title: "✳ grok", window: "grok-window", session: "grok-session", want: "grok-window"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pane, observation := paneForName(tt.provider, tt.title, tt.window, tt.session, 3)
			observation.SessionName = tt.piName
			got := sessionFromPane(pane, observation)
			if got.Name != tt.want {
				t.Fatalf("name=%q want=%q session=%+v", got.Name, tt.want, got)
			}
			if got.Ref != pane.Socket+"\x1f"+pane.PaneID {
				t.Fatalf("ref=%q changed with display projection", got.Ref)
			}
			if got.WindowName != pane.WindowName || got.WindowIndex != pane.WindowIndex {
				t.Fatalf("structural fields=%q/%d want=%q/%d", got.WindowName, got.WindowIndex, pane.WindowName, pane.WindowIndex)
			}
			if got.Title != pane.PaneTitle {
				t.Fatalf("title=%q want verbatim=%q", got.Title, pane.PaneTitle)
			}
		})
	}
}

func TestLevel2NameChangeKeepsIdentityAndChangesSnapshotKey(t *testing.T) {
	oldPane, observation := paneForName("codex", "编码甲 | 远程Agent安卓", "node", "codex-a", 0)
	newPane := oldPane
	newPane.PaneTitle = "审查乙 | 远程Agent安卓"
	before := sessionFromPane(oldPane, observation)
	after := sessionFromPane(newPane, observation)
	if before.Ref != after.Ref {
		t.Fatalf("name change changed ref: before=%q after=%q", before.Ref, after.Ref)
	}
	if before.Name == after.Name {
		t.Fatalf("name projection did not change: before=%q after=%q", before.Name, after.Name)
	}
	if level2SnapKey([]protocol.Session{before}) == level2SnapKey([]protocol.Session{after}) {
		t.Fatal("level2 snapshot key ignored display-name change")
	}
}

func TestNameOnlyChangeKeepsRefAndProducesDelta(t *testing.T) {
	oldTitle := "编码甲 | 远程Agent安卓"
	newTitle := "审查乙 | 远程Agent安卓"
	oldPane, observation := paneForName("codex", oldTitle, "node", "codex-a", 0)
	newPane := oldPane
	newPane.PaneTitle = newTitle
	model := func(p discovery.Pane) *discovery.Model {
		return &discovery.Model{Workspaces: []discovery.Workspace{{CWD: p.CWD, Panes: []discovery.Pane{p}}}}
	}
	catalog := newSessionCatalog()
	catalog.rebuild(model(oldPane), map[string]nodeprobe.Observation{sessionRef(oldPane): observation})
	before := buildSnapshot(catalog)
	catalog.rebuild(model(newPane), map[string]nodeprobe.Observation{sessionRef(newPane): observation})
	after := buildSnapshot(catalog)

	delta := after.diff(before)
	if len(delta.ChangedSessions) != 1 {
		t.Fatalf("changed sessions=%d want 1: %+v", len(delta.ChangedSessions), delta)
	}
	changed := delta.ChangedSessions[0]
	if changed.Ref != sessionRef(oldPane) || changed.Name != newTitle {
		t.Fatalf("changed=%+v want stable ref=%q and name=%q", changed, sessionRef(oldPane), newTitle)
	}
	if changed.WindowName != "node" || changed.WindowIndex != 0 {
		t.Fatalf("structural fields were overwritten: %+v", changed)
	}
	if len(delta.ChangedWorkspaces) != 0 {
		t.Fatalf("name-only change altered workspace aggregate: %+v", delta.ChangedWorkspaces)
	}
}
