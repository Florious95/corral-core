package api

import (
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func renameSnapshot(session protocol.Session) *modelSnapshot {
	return &modelSnapshot{byRef: map[string]protocol.Session{session.Ref: session}, byCWD: map[string]protocol.Workspace{session.Cwd: {Cwd: session.Cwd, SessionCount: 1}}}
}

func TestUnifiedSessionNameFlowsFromTmuxFieldsToListingDeltaAndLevel2(t *testing.T) {
	pane := discovery.Pane{
		Socket: "/synthetic/rename.sock", Session: "team", WindowIndex: 2,
		WindowName: "node", PaneID: "%4", PanePID: 100, CWD: "/work/project",
		PaneTitle: "最初名称 | project", Command: "codex", Width: 80, Height: 24,
	}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: pane.CWD, Panes: []discovery.Pane{pane}}}}
	conn := &wsConn{
		s:        &Server{level2Heartbeat: 8 * time.Second},
		level2On: true, level2WS: pane.CWD, level2Epoch: 1,
	}
	observation := nodeprobe.Observation{Provider: "codex", Activity: "idle", Health: "normal"}
	var prev protocol.Session
	for i, name := range []string{"最初名称", "最新名称", "第三次名称"} {
		pane.PaneTitle = name + " | project"
		model.Workspaces[0].Panes[0] = pane
		session := sessionFromPane(pane, observation)
		if session.Name != name {
			t.Fatalf("title segment lost: got %q want %q", session.Name, name)
		}
		if i > 0 {
			unchanged := session
			unchanged.Name = prev.Name
			unchanged.Title = prev.Title
			if unchanged != prev {
				t.Fatal("rename changed identity/status/geometry")
			}
			delta := renameSnapshot(session).diff(renameSnapshot(prev))
			if len(delta.ChangedSessions) != 1 || delta.ChangedSessions[0].Name != name || len(delta.AddedSessions) != 0 || len(delta.RemovedRefs) != 0 || len(delta.ChangedWorkspaces) != 0 {
				t.Fatalf("bad name-only delta: %#v", delta)
			}
		}
		projection := map[string][]protocol.Session{pane.CWD: {session}}
		output, epoch := conn.level2Output(1, projection, nil)
		frame, ok := output.(protocol.Level2Frame)
		if !ok || epoch != 1 || frame.Workspace != pane.CWD || len(frame.Sessions) != 1 || frame.Sessions[0] != session {
			t.Fatalf("rename missing from Level2 output: %#v epoch=%d", output, epoch)
		}
		if output, _ := conn.level2Output(1, projection, nil); output != nil {
			t.Fatalf("unchanged name causes noisy frame: %#v", output)
		}
		if delta := renameSnapshot(session).diff(renameSnapshot(session)); len(delta.ChangedSessions) != 0 {
			t.Fatal("unchanged name creates list delta")
		}
		prev = session
	}
}

func TestUnifiedSessionNameNativeMetadataCannotOverride(t *testing.T) {
	pane := discovery.Pane{Session: "team", WindowName: "node", PaneTitle: "完整标题 | project", CWD: "/work/project", Command: "node"}
	if got := displayName(pane); got != "完整标题" {
		t.Fatalf("got %q", got)
	}
	piName := "Pi 会话"
	codex := sessionFromPane(pane, nodeprobe.Observation{Provider: "codex"})
	pi := sessionFromPane(pane, nodeprobe.Observation{Provider: "pi", SessionName: &piName})
	grok := sessionFromPane(pane, nodeprobe.Observation{Provider: "grok"})
	if codex.Name != "完整标题" || pi.Name != "完整标题" || grok.Name != "完整标题" {
		t.Fatalf("provider/native override: codex=%q pi=%q grok=%q", codex.Name, pi.Name, grok.Name)
	}
	if pi.SessionName == nil || *pi.SessionName != piName {
		t.Fatal("native session_name must still be forwarded")
	}
}
