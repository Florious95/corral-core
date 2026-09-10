package api

import (
	"context"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/codexname"
	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

type renameProjectionSampler struct {
	pane discovery.Pane
	name string
}

func (s *renameProjectionSampler) Sample(_ context.Context, socket string) (nodeprobe.Report, error) {
	return nodeprobe.Report{Socket: socket, Nodes: []nodeprobe.Node{{Session: s.pane.Session, WindowIndex: s.pane.WindowIndex, PaneID: s.pane.PaneID, Provider: "codex", Activity: "idle", Health: "normal"}}}, nil
}
func (s *renameProjectionSampler) CodexNames(_ context.Context, targets []codexname.Target) map[string]string {
	return map[string]string{sessionRef(s.pane): s.name}
}

func renameSnapshot(session protocol.Session) *modelSnapshot {
	return &modelSnapshot{byRef: map[string]protocol.Session{session.Ref: session}, byCWD: map[string]protocol.Workspace{session.Cwd: {Cwd: session.Cwd, SessionCount: 1}}}
}

func TestCodexRenameFlowsFromSampleToListingDeltaAndLevel2(t *testing.T) {
	pane := discovery.Pane{Socket: "/synthetic/rename.sock", Session: "team", WindowIndex: 2, WindowName: "node", PaneID: "%4", PanePID: 100, CWD: "/work/project", PaneTitle: "project", Command: "codex", Width: 80, Height: 24}
	sampler := &renameProjectionSampler{pane: pane}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: pane.CWD, Panes: []discovery.Pane{pane}}}}
	conn := &wsConn{
		s:        &Server{level2Heartbeat: 8 * time.Second},
		level2On: true, level2WS: pane.CWD, level2Epoch: 1,
	}
	var prev protocol.Session
	for i, name := range []string{"最初名称", "最新名称 | review", "第三次名称"} {
		sampler.name = name
		observations, err := nodeprobe.SampleModel(context.Background(), model, sampler)
		if err != nil {
			t.Fatal(err)
		}
		session := sessionFromPane(pane, observations[sessionRef(pane)])
		if session.Name != name {
			t.Fatalf("/rename lost behind cwd/OSC title: got %q want %q", session.Name, name)
		}
		if i > 0 {
			unchanged := session
			unchanged.Name = prev.Name
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

func TestCodexRenamePreservesLegacyFallbackAndOtherProviders(t *testing.T) {
	pane := discovery.Pane{Session: "team", WindowName: "node", PaneTitle: "完整标题 | project"}
	if got := displayName(pane, nodeprobe.Observation{Provider: "codex"}); got != pane.PaneTitle {
		t.Fatal(got)
	}
	piName := "Pi 会话"
	if got := displayName(pane, nodeprobe.Observation{Provider: "pi", SessionName: &piName, DisplayName: "not Pi"}); got != piName {
		t.Fatal(got)
	}
	if got := displayName(pane, nodeprobe.Observation{Provider: "grok", DisplayName: "not Grok"}); got != pane.WindowName {
		t.Fatal(got)
	}
}
