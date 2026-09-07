package api

import (
	"context"
	"sync"

	"github.com/agentmirror/agentmirror/internal/nodeprobe"
)

type testNodeprobe struct {
	mu       sync.Mutex
	activity string
	provider string
	health   string
	name     *string
	session  string
	paneID   string
	calls    int
}

func (n *testNodeprobe) Sample(_ context.Context, socket string) (nodeprobe.Report, error) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.calls++
	activity, provider, health := n.activity, n.provider, n.health
	if activity == "" {
		activity = "unknown"
	}
	if provider == "" {
		provider = "unknown"
	}
	if health == "" {
		health = "unknown"
	}
	session, paneID := n.session, n.paneID
	if session == "" {
		session = "alpha"
	}
	if paneID == "" {
		paneID = "%0"
	}
	return nodeprobe.Report{SchemaVersion: 1, Socket: socket, Nodes: []nodeprobe.Node{{
		Session: session, WindowIndex: 0, PaneID: paneID, Provider: provider,
		State: activity, Activity: activity, SessionName: n.name, Health: health,
	}}}, nil
}

func (n *testNodeprobe) setActivity(activity string) {
	n.mu.Lock()
	n.activity = activity
	n.mu.Unlock()
}

func (n *testNodeprobe) count() int {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.calls
}
