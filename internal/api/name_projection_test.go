package api

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/agentmirror/agentmirror/internal/sessionname"
)

func paneForName(provider, title, window, session string, index int) (discovery.Pane, nodeprobe.Observation) {
	return discovery.Pane{
		Socket: "/synthetic/name.sock", Session: session, WindowIndex: index,
		WindowName: window, PaneID: "%0", CWD: "/synthetic/workspace",
		PaneTitle: title, Width: 120, Height: 40,
	}, nodeprobe.Observation{Provider: provider, Activity: "idle", Health: "normal"}
}

func TestUnifiedDisplayNamesIgnoreProviderAndNativeSessionName(t *testing.T) {
	// Old 076/Codex-Pi rules → requirement 101: one algorithm, no Provider
	// branch. Native session_name and former DisplayName metadata cannot win.
	piName := "派席-中文"
	tests := []struct {
		name     string
		provider string
		title    string
		window   string
		session  string
		piName   *string
		command  string
		want     string
	}{
		{name: "codex title first segment not whole title", provider: "codex", title: "编码甲 | 远程Agent安卓", window: "node", session: "codex-a", command: "codex", want: "编码甲"},
		{name: "codex empty title falls to project not unknown-empty", provider: "codex", window: "node", session: "codex-empty", command: "codex", want: "workspace"},
		{name: "pi native session_name does not override title", provider: "pi", title: "π stale title", window: "node", session: "pi-a", piName: &piName, command: "node", want: "π stale title"},
		{name: "pi missing native name still names from tmux", provider: "pi", window: "node", session: "pi-missing", command: "node", want: "workspace"},
		{name: "claude meaningful window still wins", provider: "claude", title: "◐ claude", window: "claude-window", session: "claude-session", command: "claude", want: "claude-window"},
		{name: "grok meaningful window still wins", provider: "grok", title: "✳ grok", window: "grok-window", session: "grok-session", command: "grok", want: "grok-window"},
		{name: "unknown provider uses the same path", provider: "unknown", title: "审查任务 | workspace", window: "node", session: "x", command: "node", want: "审查任务"},
		{name: "future provider uses the same path", provider: "cursor", title: "审查任务 | workspace", window: "node", session: "x", command: "node", want: "审查任务"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			pane, observation := paneForName(tt.provider, tt.title, tt.window, tt.session, 3)
			pane.Command = tt.command
			observation.SessionName = tt.piName
			got := sessionFromPane(pane, observation)
			if got.Name != tt.want {
				t.Fatalf("name=%q want=%q session=%+v", got.Name, tt.want, got)
			}
			if got.Ref != pane.Socket+"\x1f"+pane.PaneID {
				t.Fatalf("ref=%q changed with display projection", got.Ref)
			}
			if got.WindowName != pane.WindowName || got.WindowIndex != "3" {
				t.Fatalf("structural fields=%q/%q want=%q/3", got.WindowName, got.WindowIndex, pane.WindowName)
			}
			if got.Title != pane.PaneTitle {
				t.Fatalf("title=%q want verbatim=%q", got.Title, pane.PaneTitle)
			}
			if got.SessionName != tt.piName {
				t.Fatalf("native session_name was rewritten")
			}
		})
	}
}

func TestSameTmuxFieldsSameNameAcrossProviders(t *testing.T) {
	pane := discovery.Pane{
		Socket: "/s", Session: "team", WindowIndex: 1, WindowName: "node!",
		PaneID: "%2", CWD: "/work/多agent协作", Command: "node",
		PaneTitle: "多 agent leader | 多agent协作", Width: 80, Height: 24,
	}
	want := "多 agent leader"
	var names []string
	for _, provider := range []string{"codex", "pi", "claude", "grok", "cursor", "unknown", "future-cli"} {
		got := sessionFromPane(pane, nodeprobe.Observation{Provider: provider, Activity: "idle", Health: "normal"})
		names = append(names, got.Name)
		if got.Name != want {
			t.Fatalf("provider=%s name=%q want %q", provider, got.Name, want)
		}
		if got.Provider != provider {
			t.Fatalf("provider axis clobbered: %q", got.Provider)
		}
	}
	for i := 1; i < len(names); i++ {
		if names[i] != names[0] {
			t.Fatalf("provider changed the name: %v", names)
		}
	}
}

func TestLevel2NameChangeKeepsIdentityAndChangesSnapshotKey(t *testing.T) {
	oldPane, observation := paneForName("codex", "编码甲 | 远程Agent安卓", "node", "codex-a", 0)
	oldPane.Command = "codex"
	newPane := oldPane
	newPane.PaneTitle = "审查乙 | 远程Agent安卓"
	before := sessionFromPane(oldPane, observation)
	after := sessionFromPane(newPane, observation)
	if before.Ref != after.Ref {
		t.Fatalf("name change changed ref: before=%q after=%q", before.Ref, after.Ref)
	}
	if before.Name != "编码甲" || after.Name != "审查乙" {
		t.Fatalf("name projection: before=%q after=%q", before.Name, after.Name)
	}
	if level2SnapKey([]protocol.Session{before}) == level2SnapKey([]protocol.Session{after}) {
		t.Fatal("level2 snapshot key ignored display-name change")
	}
}

func TestNameOnlyChangeKeepsRefAndProducesDelta(t *testing.T) {
	oldTitle := "编码甲 | 远程Agent安卓"
	newTitle := "审查乙 | 远程Agent安卓"
	oldPane, observation := paneForName("codex", oldTitle, "node", "codex-a", 0)
	oldPane.Command = "codex"
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
	if changed.Ref != sessionRef(oldPane) || changed.Name != "审查乙" {
		t.Fatalf("changed=%+v want stable ref=%q and name=%q", changed, sessionRef(oldPane), "审查乙")
	}
	if changed.WindowName != "node" || changed.WindowIndex != "0" {
		t.Fatalf("structural fields were overwritten: %+v", changed)
	}
	if len(delta.ChangedWorkspaces) != 0 {
		t.Fatalf("name-only change altered workspace aggregate: %+v", delta.ChangedWorkspaces)
	}
}

func TestDisplayNameDoesNotReadObservation(t *testing.T) {
	pane := discovery.Pane{WindowName: "smoke-luna", PaneTitle: "workspace", CWD: "/synthetic/workspace", Command: "node"}
	got := displayName(pane)
	if got != "smoke-luna" {
		t.Fatalf("got %q", got)
	}
	if sessionname.Resolve(pane.WindowName, pane.PaneTitle, pane.CWD, pane.Command).Value != got {
		t.Fatal("listing wrapper diverged from sessionname.Resolve")
	}
}
