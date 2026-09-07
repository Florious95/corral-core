package api

import (
	"context"
	"errors"
	"testing"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

type errorNodeprobe struct{}

func (errorNodeprobe) Sample(context.Context, string) (nodeprobe.Report, error) {
	return nodeprobe.Report{}, errors.New("sample failed")
}

func TestFourAxesPropagateThroughListingDeltaAndLevel2Key(t *testing.T) {
	name := "seat"
	m := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/w", Panes: []discovery.Pane{{Socket: "/s", Session: "alpha", WindowIndex: 0, PaneID: "%0", CWD: "/w", Width: 80, Height: 24}}}}}
	c := newSessionCatalog()
	c.rebuild(m, map[string]nodeprobe.Observation{"/s\x1f%0": {Provider: "pi", Activity: "working", SessionName: &name, Health: "normal"}})
	first := buildSnapshot(c)
	got := first.byRef["/s\x1f%0"]
	if got.Provider != "pi" || got.Activity != "working" || got.Status != got.Activity || got.SessionName == nil || *got.SessionName != "seat" || got.Health != "normal" {
		t.Fatalf("session=%+v", got)
	}
	c.rebuild(m, map[string]nodeprobe.Observation{"/s\x1f%0": {Provider: "pi", Activity: "idle", Health: "abnormal"}})
	second := buildSnapshot(c)
	d := second.diff(first)
	if len(d.ChangedSessions) != 1 || d.ChangedSessions[0].Activity != "idle" || d.ChangedSessions[0].SessionName != nil {
		t.Fatalf("delta=%+v", d)
	}
	oldKey := level2SnapKey([]protocol.Session{got})
	newKey := level2SnapKey([]protocol.Session{d.ChangedSessions[0]})
	if oldKey == newKey {
		t.Fatal("four-axis change missing from level2 snapshot key")
	}
}

func TestNodeprobeFailureDoesNotPublishEmptyReplacement(t *testing.T) {
	md := &mutableDiscoverer{model: testModel()}
	s := NewServer(Options{Discoverer: md, Nodeprobe: &testNodeprobe{provider: "pi", activity: "working", health: "normal"}})
	defer s.Close()
	if err := s.rebuildCatalog(context.Background()); err != nil {
		t.Fatal(err)
	}
	before, _ := s.currentSnapshot()
	if before == nil || len(before.byRef) == 0 {
		t.Fatal("missing initial snapshot")
	}
	s.nodeprobe = errorNodeprobe{}
	if err := s.rebuildCatalog(context.Background()); err == nil {
		t.Fatal("expected visible nodeprobe failure")
	}
	after, _ := s.currentSnapshot()
	if after != before || len(after.byRef) == 0 {
		t.Fatal("failed sample published empty replacement")
	}
}
