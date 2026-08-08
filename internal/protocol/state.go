package protocol

// AgentState is the normalized lifecycle state of one agent CLI session
// (requirement 008). The five values form a closed set; a sixth state requires
// a protocol version bump. State values travel only in control frames —
// never in the binary mirror channel — so an undecidable parse degrades to
// StateUnknown without affecting mirroring or input (the 008 isolation law).
type AgentState string

const (
	// StateWorking means the agent is actively producing output.
	StateWorking AgentState = "working"
	// StateIdle means the agent is present but not currently doing work.
	StateIdle AgentState = "idle"
	// StateBlocked means the agent is waiting for input (e.g. a prompt) and
	// needs the user.
	StateBlocked AgentState = "blocked"
	// StateDone means the agent finished its task.
	StateDone AgentState = "done"
	// StateUnknown is the fallback when no per-agent adapter could decide the
	// state. It is a first-class value, never an error, and it must never gate
	// mirroring or input.
	StateUnknown AgentState = "unknown"
)

// IsValid reports whether s is one of the five closed state values. The codec
// rejects any other value on decode so a typo is caught at the boundary.
func (s AgentState) IsValid() bool {
	switch s {
	case StateWorking, StateIdle, StateBlocked, StateDone, StateUnknown:
		return true
	}
	return false
}
