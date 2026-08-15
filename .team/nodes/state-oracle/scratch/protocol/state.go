package protocol

// AgentState is the normalized lifecycle state of one agent CLI session
// (requirement 008). The four values form a closed set; a fifth state requires
// a protocol version bump. State values travel only in control frames —
// never in the binary mirror channel — so an undecidable parse degrades to
// StateUnknown without affecting mirroring or input (the 008 isolation law).
//
// The former "done" state was removed on 2026-08-15 (user ruling 2026-08-13:
// the server has no done; "done" is an app-side unread marker derived from
// idle+unseen). See docs/archive/agentstate-round4/ and requirement 025 §4.B.
type AgentState string

const (
	// StateWorking means the agent is actively producing output.
	StateWorking AgentState = "working"
	// StateIdle means the agent is present but not currently doing work.
	StateIdle AgentState = "idle"
	// StateBlocked means the agent is waiting for input (e.g. a prompt) and
	// needs the user.
	StateBlocked AgentState = "blocked"
	// StateUnknown is the fallback when no per-agent adapter could decide the
	// state. It is a first-class value, never an error, and it must never gate
	// mirroring or input.
	StateUnknown AgentState = "unknown"
	// StateDone is the ARCHIVED fifth state (t.oracle scratch-harness ONLY).
	// It was removed from live protocol on 2026-08-15 (058/025 §4.B: server has
	// no done; done = app-side unread marker). It is re-added HERE ONLY so the
	// byte-identical archived fossil (docs/archive/agentstate-round4/track.go,
	// which derives working→idle ⇒ StateDone) compiles in the scratch module.
	// Nothing in the new decision layer may reference it; t.impl's target is
	// working/idle only. This file is probe tooling, not accepted code.
	StateDone AgentState = "done"
)

// IsValid reports whether s is one of the four closed state values. The codec
// rejects any other value on decode so a typo is caught at the boundary.
func (s AgentState) IsValid() bool {
	switch s {
	case StateWorking, StateIdle, StateBlocked, StateUnknown:
		return true
	}
	return false
}
