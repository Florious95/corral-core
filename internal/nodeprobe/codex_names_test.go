package nodeprobe

import (
	"context"
	"reflect"
	"testing"

	"github.com/agentmirror/agentmirror/internal/codexname"
	"github.com/agentmirror/agentmirror/internal/discovery"
)

type codexRenameSampler struct {
	nodes     []Node
	names     map[string]string
	targets   []codexname.Target
	nameCalls int
}

func (s *codexRenameSampler) Sample(_ context.Context, socket string) (Report, error) {
	return Report{SchemaVersion: 1, Socket: socket, Nodes: s.nodes}, nil
}
func (s *codexRenameSampler) CodexNames(_ context.Context, targets []codexname.Target) map[string]string {
	s.nameCalls++
	s.targets = append([]codexname.Target(nil), targets...)
	return s.names
}

func TestCodexNameEnrichmentOnlyAfterUniqueAllowedProviderJoin(t *testing.T) {
	socket := "/synthetic/codex-names.sock"
	panes := []discovery.Pane{
		{Socket: socket, Session: "team", PaneID: "%0", PanePID: 100, Command: "codex"},
		{Socket: socket, Session: "team", PaneID: "%1", PanePID: 101, Command: "pi"},
		{Socket: socket, Session: "team", PaneID: "%2", PanePID: 102, Command: "codex"},
		{Socket: socket, Session: "team", PaneID: "%3", PanePID: 103, Command: "codex"},
	}
	model := &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/same/workspace", Panes: panes}}}
	dup := Node{Session: "team", PaneID: "%2", Provider: "codex", Activity: "idle", Health: "normal"}
	sampler := &codexRenameSampler{nodes: []Node{
		{Session: "team", PaneID: "%0", Provider: "codex", Activity: "idle", Health: "normal"},
		{Session: "team", PaneID: "%1", Provider: "pi", Activity: "working", Health: "abnormal"},
		dup, dup,
	}, names: map[string]string{socket + "\x1f%0": "第一次命名", socket + "\x1f%1": "must not override Pi", "outside": "must not add a pane"}}
	for _, name := range []string{"第一次命名", "最新名称"} {
		sampler.names[socket+"\x1f%0"] = name
		got, err := SampleModel(context.Background(), model, sampler)
		if err != nil {
			t.Fatal(err)
		}
		wantTargets := []codexname.Target{{Ref: socket + "\x1f%0", RootPID: 100, Command: "codex"}}
		if !reflect.DeepEqual(sampler.targets, wantTargets) {
			t.Fatalf("bad allowlist: %#v", sampler.targets)
		}
		obs := got[socket+"\x1f%0"]
		if obs.DisplayName != name || obs.Provider != "codex" || obs.Activity != "idle" || obs.Health != "normal" || obs.SessionName != nil {
			t.Fatalf("axes or name changed: %#v", obs)
		}
		if len(got) != 4 || got[socket+"\x1f%1"].DisplayName != "" || got[socket+"\x1f%2"].Provider != "unknown" || got[socket+"\x1f%3"].Provider != "unknown" {
			t.Fatalf("scope expanded: %#v", got)
		}
	}
	sampler.nodes = nil
	before := sampler.nameCalls
	if _, err := SampleModel(context.Background(), model, sampler); err != nil {
		t.Fatal(err)
	}
	if sampler.nameCalls != before {
		t.Fatal("unknown-only model must not inspect Codex metadata")
	}
}
