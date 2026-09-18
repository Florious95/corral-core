package api

import (
	"context"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

func workingCount(t *testing.T, ws protocol.Workspace) int {
	t.Helper()
	field := reflect.ValueOf(ws).FieldByName("WorkingCount")
	if !field.IsValid() {
		t.Fatalf("protocol.Workspace.WorkingCount is missing; listing cannot expose the aggregate")
	}
	if field.Kind() != reflect.Int {
		t.Fatalf("protocol.Workspace.WorkingCount kind = %s, want int", field.Kind())
	}
	return int(field.Int())
}

func statusScenarioModel(titles ...string) *discovery.Model {
	panes := make([]discovery.Pane, 0, len(titles))
	for i, title := range titles {
		panes = append(panes, discovery.Pane{
			Socket: "/tmp/mahjong.sock", Session: "agent", WindowIndex: i, PaneID: "%" + string(rune('0'+i)),
			CWD: "/repo", PanePID: 100 + i, PaneTitle: title,
			Command: "claude", Width: 100, Height: 30,
		})
	}
	return &discovery.Model{Workspaces: []discovery.Workspace{{CWD: "/repo", Panes: panes}}}
}

type statusScenarioSampler struct {
	mu     sync.Mutex
	report nodeprobe.Report
}

func (s *statusScenarioSampler) Sample(_ context.Context, socket string) (nodeprobe.Report, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	report := s.report
	report.Socket = socket
	return report, nil
}

func (s *statusScenarioSampler) set(report nodeprobe.Report) {
	s.mu.Lock()
	s.report = report
	s.mu.Unlock()
}

func scenarioReport(activities ...string) nodeprobe.Report {
	report := nodeprobe.Report{SchemaVersion: 1, Socket: "/tmp/mahjong.sock"}
	for i, activity := range activities {
		report.Nodes = append(report.Nodes, nodeprobe.Node{
			Session: "agent", WindowIndex: i, PaneID: "%" + string(rune('0'+i)),
			Provider: "claude_code", State: activity, Activity: activity, Health: "normal",
		})
	}
	return report
}

func TestListingAggregatesWorkingPanesByWorkspace(t *testing.T) {
	// The accepted nodeprobe activity axis is authoritative: two working, one
	// idle, and one unknown pane must aggregate to two.
	e := startWS(t, Options{
		Token:        "test-token",
		Discoverer:   scriptedDiscoverer{model: statusScenarioModel("working-a", "working-b", "idle", "unknown")},
		Nodeprobe:    &statusScenarioSampler{report: scenarioReport("working", "working", "idle", "unknown")},
		ListInterval: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 1})
	listing := mustListing(t, e, 1)
	if len(listing.Workspaces) != 1 {
		t.Fatalf("workspaces = %d, want 1; listing=%+v", len(listing.Workspaces), listing)
	}
	ws := listing.Workspaces[0]
	if ws.SessionCount != 4 {
		t.Fatalf("session_count = %d, want 4", ws.SessionCount)
	}
	if got := workingCount(t, ws); got != 2 {
		t.Fatalf("working_count = %d, want 2 for two working + idle + unknown panes", got)
	}
}

func TestWorkingToIdlePublishesChangedWorkspaceCount(t *testing.T) {
	d := &mutableDiscoverer{model: statusScenarioModel("working")}
	sampler := &statusScenarioSampler{report: scenarioReport("working")}
	e := startWS(t, Options{
		Token:        "test-token",
		Discoverer:   d,
		Nodeprobe:    sampler,
		ListInterval: time.Hour,
	})
	e.auth()
	e.sendFrame(&protocol.List{ReqID: 1})
	initial := mustListing(t, e, 1)
	if got := workingCount(t, initial.Workspaces[0]); got != 1 {
		t.Fatalf("initial working_count = %d, want 1", got)
	}

	// Only the accepted nodeprobe activity changes; cwd and identity stay put.
	d.set(statusScenarioModel("idle"))
	sampler.set(scenarioReport("idle"))
	if err := e.srv.rebuildCatalog(context.Background()); err != nil {
		t.Fatalf("rebuild after activity transition: %v", err)
	}
	frame := e.readControlDraining()
	delta, ok := frame.(protocol.ListDelta)
	if !ok {
		t.Fatalf("state transition frame = %T (%+v), want list_delta", frame, frame)
	}
	if len(delta.ChangedWorkspaces) != 1 {
		t.Fatalf("changed_workspaces = %d, want 1; delta=%+v", len(delta.ChangedWorkspaces), delta)
	}
	if got := workingCount(t, delta.ChangedWorkspaces[0]); got != 0 {
		t.Fatalf("working→idle delta working_count = %d, want 0", got)
	}
}
