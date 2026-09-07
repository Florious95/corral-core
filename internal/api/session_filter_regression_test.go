package api

import (
	"context"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// cliIdentityProbe is a credential-free fixture for the production nodeprobe
// join: one identified Agent CLI and one ordinary shell share a workspace.
type cliIdentityProbe struct{}

func (cliIdentityProbe) Sample(_ context.Context, socket string) (nodeprobe.Report, error) {
	piName := "agent-idle"
	return nodeprobe.Report{SchemaVersion: 1, Socket: socket, Nodes: []nodeprobe.Node{
		{Session: "agent-idle", PaneID: "%0", Provider: "pi", State: "idle", Activity: "idle", SessionName: &piName, Health: "unknown"},
		{Session: "agent-working", PaneID: "%1", Provider: "grok", State: "working", Activity: "working", Health: "normal"},
		{Session: "agent-unknown", PaneID: "%2", Provider: "codex", State: "unknown", Activity: "unknown", Health: "unknown"},
		{Session: "shell", PaneID: "%3", Provider: "unknown", State: "unknown", Activity: "unknown", Health: "unknown"},
	}}, nil
}

func cliIdentityModel() *discovery.Model {
	return &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/fixture", Panes: []discovery.Pane{
		{Socket: "/fixture/socket", Session: "agent-idle", PaneID: "%0", CWD: "/fixture", WindowName: "agent-idle", Width: 80, Height: 24},
		{Socket: "/fixture/socket", Session: "agent-working", PaneID: "%1", CWD: "/fixture", WindowName: "agent-working", Width: 80, Height: 24},
		{Socket: "/fixture/socket", Session: "agent-unknown", PaneID: "%2", CWD: "/fixture", WindowName: "agent-unknown", PaneTitle: "agent-unknown", Width: 80, Height: 24},
		{Socket: "/fixture/socket", Session: "shell", PaneID: "%3", CWD: "/fixture", WindowName: "shell", Command: "bash", Width: 80, Height: 24},
	}}}}
}

// TestListingAndLevel2ExcludeOrdinaryShell keeps the existing discovery scope:
// a known Agent CLI stays listed even when activity/health are unknown, while a
// structurally joined unknown provider row is not an Agent session.
func TestListingAndLevel2ExcludeOrdinaryShell(t *testing.T) {
	e := startWS(t, Options{
		Token:        "test-token",
		Discoverer:   scriptedDiscoverer{model: cliIdentityModel()},
		Nodeprobe:    cliIdentityProbe{},
		ListInterval: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 1})
	listing := mustListing(t, e, 1)
	if len(listing.Workspaces) != 1 || len(listing.Workspaces[0].Sessions) != 3 {
		t.Fatalf("listing=%+v, want three Agent CLI sessions", listing.Workspaces)
	}
	byName := map[string]protocol.Session{}
	for _, s := range listing.Workspaces[0].Sessions {
		byName[s.Name] = s
	}
	if len(byName) != 3 {
		t.Fatalf("listing names=%v, ordinary shell or duplicate leaked", byName)
	}
	if idle := byName["agent-idle"]; idle.Provider != "pi" || idle.Activity != "idle" || idle.Health != "unknown" {
		t.Fatalf("idle Agent=%+v, want known provider despite unknown health", idle)
	}
	if working := byName["agent-working"]; working.Provider != "grok" || working.Activity != "working" {
		t.Fatalf("working Agent=%+v, want working provider", working)
	}
	if unknown := byName["agent-unknown"]; unknown.Provider != "codex" || unknown.Activity != "unknown" || unknown.Health != "unknown" {
		t.Fatalf("unknown-axis Agent=%+v, want known provider despite unknown axes", unknown)
	}
	if _, ok := byName["shell"]; ok {
		t.Fatalf("ordinary shell leaked into listing: %v", byName)
	}

	e.sendFrame(&protocol.Level2Subscribe{Workspace: "/fixture"})
	frame := waitLevel2Frame(t, e, 2*time.Second)
	if len(frame.Sessions) != 3 {
		t.Fatalf("level2=%+v, want the same three Agent CLI sessions", frame.Sessions)
	}
	for _, s := range frame.Sessions {
		if s.Name == "shell" {
			t.Fatalf("ordinary shell leaked into level2: %+v", frame.Sessions)
		}
	}
}
