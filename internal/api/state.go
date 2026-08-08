package api

// state.go declares the agent-state seam. Requirement 008's isolation law is
// structural: the API layer asks the provider for a state and the provider
// always answers with one of the five closed values, degrading to unknown on
// any failure. A state problem can never block mirroring or input because
// those two paths never consult the state provider at all.

import (
	"context"

	"github.com/remote-agent/agentmirror/internal/discovery"
	"github.com/remote-agent/agentmirror/internal/protocol"
)

// StateProvider maps one discovered pane to its normalized agent state. The
// state-parser task lands the real implementation; the default always returns
// protocol.StateUnknown (008 first-class value).
type StateProvider interface {
	// State returns the pane's normalized agent state, degrading to
	// protocol.StateUnknown when undecidable.
	State(ctx context.Context, p discovery.Pane) protocol.AgentState
}

// unknownState is the default provider: every pane is unknown until the state
// layer lands. It keeps listing well-formed (a workspace whose members are
// all unknown aggregates to unknown, per requirement 012) without ever
// touching the mirror or input path.
type unknownState struct{}

func (unknownState) State(context.Context, discovery.Pane) protocol.AgentState {
	return protocol.StateUnknown
}
