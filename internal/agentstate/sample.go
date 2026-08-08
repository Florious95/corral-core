package agentstate

import (
	"time"

	"github.com/remote-agent/agentmirror/internal/protocol"
)

// Sample is the input contract for one state decision. It is produced by the
// mirror layer (ws-api feeds it); this package only defines the interface and
// never performs I/O to obtain it — the 008 isolation law requires every
// decision to be a pure function of the bytes handed in, so a state-layer
// failure can never block mirroring or input.
type Sample struct {
	// PaneCommand is the pane's current foreground command (pane_current_command),
	// used to select the per-agent adapter (claude / codex).
	PaneCommand string

	// RecentOutput is the tail window of recent pane output (e.g. the last
	// 4KiB), ANSI escape sequences included. Rules run on this after stripping.
	RecentOutput []byte

	// LastOutputAge is how long ago the last output was observed. It is an
	// input for time-assisted rules (the idle/blocked distinction) but never a
	// blocking wait — decisions stay synchronous and side-effect free.
	LastOutputAge time.Duration
}

// Confidence grades how strongly a decision is grounded. It accompanies every
// State so the consumer (ws-api push logic) can weigh whether an edge like
// done/blocked is worth a notification (requirement 003 standard four).
type Confidence int

const (
	// ConfidenceUnknown grades the fallback: we could not decide, nothing lost.
	ConfidenceUnknown Confidence = 0
	// ConfidenceLow grades weak signals: e.g. a bare prompt marker that could
	// also be a plain shell prompt.
	ConfidenceLow Confidence = 1
	// ConfidenceMedium grades a UI signal that is specific to the CLI but could
	// still be a near-miss wording (e.g. a status-bar marker).
	ConfidenceMedium Confidence = 2
	// ConfidenceHigh grades an unambiguous interactive element (a permission
	// confirmation box) — the strongest evidence we can see from output alone.
	ConfidenceHigh Confidence = 3
)

// State is one decision: a normalized agent state plus how confident we are.
type State struct {
	State      protocol.AgentState
	Confidence Confidence
}

// Adapter decides the state for exactly one agent CLI from a Sample. Adapters
// are pure: Detect never blocks, never fails, and always returns a State (the
// StateUnknown fallback when nothing matches). This is the extension point the
// task contract reserves for future CLIs beyond Claude Code and Codex.
type Adapter interface {
	Detect(sample Sample) State
}

// Registry maps a pane_current_command value to the adapter for that CLI.
type Registry map[string]Adapter

// DefaultRegistry returns the registry for the first batch of adapters
// (Claude Code and Codex, per the task contract). Adding a CLI here is the
// documented extension path; the registry itself stays a plain map so future
// adapters can be wired in without touching the rule tables.
func DefaultRegistry() Registry {
	return Registry{
		"claude": &ClaudeCodeAdapter{},
		"codex":  &CodexAdapter{},
	}
}

// Detect routes a sample to the adapter named by PaneCommand. An unrecognized
// command is not an error: it degrades to StateUnknown with unknown confidence,
// keeping the state layer isolated from panes this server does not understand
// (requirement 008: undecidable must never affect mirroring or input).
func (r Registry) Detect(sample Sample) State {
	if a, ok := r[sample.PaneCommand]; ok {
		return a.Detect(sample)
	}
	return State{State: protocol.StateUnknown, Confidence: ConfidenceUnknown}
}
