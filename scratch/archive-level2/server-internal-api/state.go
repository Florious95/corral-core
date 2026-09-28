package api

// state.go declares the agent-state seam. Requirement 008's isolation law is
// structural: the API layer asks the provider for a state and the provider
// always answers with one of the five closed values, degrading to unknown on
// any failure. A state problem can never block mirroring or input because
// those two paths never consult the state provider at all.

import (
	"context"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/agentmirror/agentmirror/internal/protocol"
)

// StateProvider maps one discovered pane to its normalized agent state. The
// production implementation is wiredStateProvider (state_wiring.go), assembled
// by NewStateProvider and handed to the api server by cmd/agentmirrord
// (task fix-state-wiring); a caller that wires no provider gets unknownState,
// which always returns protocol.StateUnknown (requirement 008 first-class
// value).
type StateProvider interface {
	// State returns the pane's normalized agent state, degrading to
	// protocol.StateUnknown when undecidable.
	// @contract
	// @pre ctx 非 nil；p 为 discovery.Pane
	// @post 返回五种闭值之一（working/idle/blocked/done/unknown）；判定不出返回 StateUnknown
	// @err none — 永不返回 error（008 隔离铁律）
	// @inv 不触碰镜像/输入路径
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
