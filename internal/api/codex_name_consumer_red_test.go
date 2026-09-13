package api

// This consumer contract test calls the existing listing/toSession and Level2
// conversion paths; it does not reimplement either conversion.
//
// Old Codex-complete-title rule → requirement 101: window_name=node is noise,
// so name is the first non-project title segment. Title stays the full OSC
// string. Structural window fields are unchanged.

import (
	"context"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

const (
	codexTitleA    = "编码甲 | 远程Agent安卓"
	codexTitleB    = "审查乙 | 远程Agent安卓"
	codexNameA     = "编码甲"
	codexNameB     = "审查乙"
	codexRedSocket = "/synthetic/codex-name-red.sock"
)

type codexNameRedSampler struct{}

func (codexNameRedSampler) Sample(_ context.Context, socket string) (nodeprobe.Report, error) {
	return nodeprobe.Report{
		SchemaVersion: 1,
		Socket:        socket,
		Nodes: []nodeprobe.Node{
			{Socket: socket, Session: "codex-a3", WindowIndex: 0, PaneID: "%0", Provider: "codex", State: "idle", Activity: "idle", Health: "normal"},
			{Socket: socket, Session: "codex-b3", WindowIndex: 0, PaneID: "%1", Provider: "codex", State: "idle", Activity: "idle", Health: "normal"},
		},
	}, nil
}

func codexNameRedModel() discovery.Model {
	return discovery.Model{Workspaces: []discovery.Workspace{{
		CWD: "/Volumes/nvme/Projects/远程Agent安卓",
		Panes: []discovery.Pane{
			{Socket: codexRedSocket, Session: "codex-a3", WindowIndex: 0, PaneID: "%0", CWD: "/Volumes/nvme/Projects/远程Agent安卓", Command: "codex", PaneTitle: codexTitleA, WindowName: "node", Width: 120, Height: 40},
			{Socket: codexRedSocket, Session: "codex-b3", WindowIndex: 0, PaneID: "%1", CWD: "/Volumes/nvme/Projects/远程Agent安卓", Command: "codex", PaneTitle: codexTitleB, WindowName: "node", Width: 120, Height: 40},
		},
	}}}
}

func assertUnifiedListingDisplayNames(t *testing.T, sessions []protocol.Session) {
	t.Helper()
	want := map[string]string{
		codexRedSocket + "\x1f%0": codexNameA,
		codexRedSocket + "\x1f%1": codexNameB,
	}
	if len(sessions) != len(want) {
		t.Fatalf("sessions = %d, want %d", len(sessions), len(want))
	}
	for _, s := range sessions {
		if s.Name != want[s.Ref] {
			t.Errorf("ref=%q name=%q want title segment %q", s.Ref, s.Name, want[s.Ref])
		}
		if s.WindowName != "node" || s.WindowIndex != "0" {
			t.Errorf("ref=%q structural fields=%q/%q want window_name=node/window_index=0", s.Ref, s.WindowName, s.WindowIndex)
		}
	}
}

func assertUnifiedLevel2DisplayNames(t *testing.T, sessions []protocol.Session) {
	t.Helper()
	if len(sessions) != 2 {
		t.Fatalf("sessions = %d, want 2", len(sessions))
	}
	for _, s := range sessions {
		if s.Name == s.Title {
			t.Errorf("ref=%q name unexpectedly equals complete title %q", s.Ref, s.Title)
		}
		if s.WindowName != "node" || s.WindowIndex != "0" {
			t.Errorf("ref=%q structural fields=%q/%q want window_name=node/window_index=0", s.Ref, s.WindowName, s.WindowIndex)
		}
	}
}

func TestUnifiedTitleSegmentInListingAndLevel2(t *testing.T) {
	model := codexNameRedModel()
	observations := map[string]nodeprobe.Observation{
		codexRedSocket + "\x1f%0": {Provider: "codex", Activity: "idle", Health: "normal"},
		codexRedSocket + "\x1f%1": {Provider: "codex", Activity: "idle", Health: "normal"},
	}
	catalog := newSessionCatalog()
	catalog.rebuild(&model, observations)
	listing := buildSnapshot(catalog).listing()
	if len(listing) != 1 {
		t.Fatalf("listing workspaces = %d, want 1", len(listing))
	}
	assertUnifiedListingDisplayNames(t, listing[0].Sessions)

	discoverer := &mutableDiscoverer{model: &model}
	e := startWS(t, Options{
		Token:           "test-token",
		Discoverer:      discoverer,
		Nodeprobe:       codexNameRedSampler{},
		ListInterval:    time.Hour,
		Level2Interval:  30 * time.Millisecond,
		Level2Heartbeat: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/Volumes/nvme/Projects/远程Agent安卓"})
	frame := waitLevel2Frame(t, e, 5*time.Second)
	assertUnifiedLevel2DisplayNames(t, frame.Sessions)
	assertUnifiedListingDisplayNames(t, frame.Sessions)
	if frame.Sessions[0].Title != codexTitleA && frame.Sessions[1].Title != codexTitleA {
		t.Fatalf("verbatim titles lost: %+v", frame.Sessions)
	}
}
