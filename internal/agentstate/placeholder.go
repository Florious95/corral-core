// Package agentstate is the post-archive placeholder for the agent-state
// decision layer.
//
// 058 archive (2026-08-15, task t.archive): the full decision implementation
// — rules.go / adapters.go / identify.go / sample.go / track.go / ansi.go and
// all tests, 1699 lines — was archived to docs/archive/agentstate-round4/.
// Three repair rounds (requirement 025) all edited the same glyph-whitelist
// structure, which the evidence says cannot answer the question: the glyph
// set changed (◐/✳ replaced the braille frames) and the whitelist went
// silently blind; two states share the same prefix glyph (✻ Brewed / ✻
// Galloping), so no glyph/prefix match can separate done from working.
//
// This file retains ONLY the type surface the StateProvider seam
// (server/internal/api/state_wiring.go) needs to compile, and every decision
// degrades to StateUnknown — per 058 boundary 3, the state field must report
// unknown during the gap, never a stale working/idle value that could deceive
// the 012 aggregate. The decision functions live in the archive, not here.
//
// @consumes internal/protocol
package agentstate

import (
	"context"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// AgentKind names which agent CLI a pane runs. The process-tree identifier
// (identify.go) is archived; the type is retained only so the StateProvider
// seam compiles. The zero value is AgentKindUnknown.
type AgentKind string

const (
	// AgentKindUnknown is the fallback when no signal identifies the pane.
	AgentKindUnknown AgentKind = ""
	// AgentKindClaude is a Claude Code pane.
	AgentKindClaude AgentKind = "claude"
	// AgentKindCodex is a Codex pane.
	AgentKindCodex AgentKind = "codex"
)

// Command returns the pane_current_command key for a kind. After the archive
// no kind is ever resolved, so it always returns ok=false and the caller
// falls back to the pane's own command (the same degradation as an unknown
// kind pre-archive).
func (k AgentKind) Command() (string, bool) { return "", false }

// IdentifyInput is the archived Identify's input shape, retained for the seam.
type IdentifyInput struct {
	PanePID     int
	PaneTitle   string
	PaneCommand string
}

// Identify always degrades to AgentKindUnknown. The process-tree descent and
// title heuristics are archived (docs/archive/agentstate-round4/identify.go);
// the decision chain must not be revived piecemeal — 058 forbids stacking on
// the archived structure.
func Identify(ctx context.Context, in IdentifyInput) AgentKind { return AgentKindUnknown }

// Sample is the archived decision pipeline's input shape, retained for the
// seam. The RecentOutput / PaneTitle collection that produces it lives outside
// this package and is preserved (058 boundary 1).
type Sample struct {
	PaneCommand   string
	PaneTitle     string
	RecentOutput  []byte
	LastOutputAge time.Duration
}

// State is one decision. After the archive every decision is StateUnknown
// with unknown confidence.
type State struct {
	State      protocol.AgentState
	Confidence Confidence
}

// Confidence grades the strength of a decision; retained for the State shape.
type Confidence int

// Registry maps a command to an adapter. Empty after the archive.
type Registry map[string]Adapter

// Adapter is the archived adapter contract, retained for the Registry shape.
type Adapter interface{ Detect(sample Sample) State }

// DefaultRegistry returns an empty registry after the archive. It satisfies
// the seam but routes nothing.
func DefaultRegistry() Registry { return Registry{} }

// Detect always degrades to StateUnknown after the archive.
func (r Registry) Detect(sample Sample) State { return unknownState() }

// DetectForKind always degrades to StateUnknown after the archive.
func (r Registry) DetectForKind(kind AgentKind, sample Sample) State { return unknownState() }

// Track always degrades to StateUnknown after the archive. The done edge
// (working→idle ⇒ done) is archived — the server no longer produces done
// (user 2026-08-13 ruling); unknown is the only honest value in the gap.
func Track(prev protocol.AgentState, sample Sample) State { return unknownState() }

// unknownState is the single decision this placeholder may make: StateUnknown,
// so a pane reports unknown rather than a stale value (058 boundary 3; 012).
func unknownState() State {
	return State{State: protocol.StateUnknown, Confidence: Confidence(0)}
}
