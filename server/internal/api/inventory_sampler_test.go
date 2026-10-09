package api

import (
	"context"
	"github.com/agentmirror/agentmirror/internal/nodeprobe"
	"sync"
	"testing"
	"time"
)

type inventoryTestProbe struct {
	mu       sync.Mutex
	calls    int
	provider string
	gate     <-chan struct{}
	entered  chan struct{}
}

func (s *inventoryTestProbe) Sample(ctx context.Context, socket string) (nodeprobe.Report, error) {
	s.mu.Lock()
	s.calls++
	provider, gate := s.provider, s.gate
	s.mu.Unlock()
	select {
	case s.entered <- struct{}{}:
	default:
	}
	if gate != nil {
		select {
		case <-gate:
		case <-ctx.Done():
			return nodeprobe.Report{}, ctx.Err()
		}
	}
	return nodeprobe.Report{SchemaVersion: 1, Socket: socket, Nodes: []nodeprobe.Node{{Session: "agent", PaneID: "%0", Provider: provider, Activity: "unknown", Health: "unknown"}}}, nil
}
func TestWorkspaceInventoryCachedL1DoesNotWaitForStatusAndRechecksUnknown(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	probe := &inventoryTestProbe{provider: "unknown", entered: make(chan struct{}, 8)}
	p := newInventorySampler(ctx, probe)
	defer func() { cancel(); p.workers.Wait() }()
	model := workspaceFixtureModel("/A", "/fixture/A", "%0")
	obs, err := p.sample(ctx, model)
	if err != nil || obs["/fixture/A\x1f%0"].Provider != "unknown" {
		t.Fatal(obs, err)
	}
	<-probe.entered
	gate := make(chan struct{})
	probe.mu.Lock()
	probe.provider = "claude_code"
	probe.gate = gate
	probe.mu.Unlock()
	p.mu.Lock()
	p.entries["/fixture/A"].updated = time.Now().Add(-3 * time.Second)
	p.mu.Unlock()
	// No timed sleep oracle: block the dependency, require the light call to
	// complete while the actual background status request remains blocked.
	done := make(chan error, 1)
	go func() { _, err := p.sample(ctx, model); done <- err }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("L1 waited for state sampling")
	}
	select {
	case <-probe.entered:
	case <-time.After(time.Second):
		t.Fatal("unknown provider was never rechecked")
	}
	close(gate)
	catalogEventually(t, "new Agent in unchanged shell never became visible", func() bool {
		obs, err := p.sample(ctx, model)
		return err == nil && obs["/fixture/A\x1f%0"].Provider == "claude_code"
	})
	probe.mu.Lock()
	calls := probe.calls
	probe.mu.Unlock()
	if calls != 2 {
		t.Fatalf("duplicate sampling: %d", calls)
	}
}

func TestWorkspaceInventoryChangedPIDRequiresFreshIdentity(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	probe := &inventoryTestProbe{provider: "claude_code", entered: make(chan struct{}, 8)}
	p := newInventorySampler(ctx, probe)
	defer func() { cancel(); p.workers.Wait() }()
	m := workspaceFixtureModel("/A", "/fixture/A", "%0")
	if _, err := p.sample(ctx, m); err != nil {
		t.Fatal(err)
	}
	m.Workspaces[0].Panes[0].PanePID = 321
	probe.mu.Lock()
	probe.provider = "unknown"
	probe.mu.Unlock()
	obs, err := p.sample(ctx, m)
	if err != nil || obs["/fixture/A\x1f%0"].Provider != "unknown" {
		t.Fatal("replaced process inherited old Agent identity", obs, err)
	}
}
