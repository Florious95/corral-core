// Package agentstate maps per-agent CLI output and process trees to a
// normalized state (working/idle/blocked/done), degrading to unknown when
// undecidable.
//
// Landing zone for the state-parser task, with per-agent adapters (first
// batch: Claude Code, Codex) referencing the Apache-2.0 herdr implementation
// in a license-compliant way. It also hosts the wrapper-scene identifier
// (state-ident-wrapper task): Identify resolves the agent kind of a wrapper
// pane (pane_current_command=bash) by walking its process tree, and
// Registry.DetectForKind dispatches on the kind to the same rule tables.
// The state layer must stay strictly isolated from the mirror/input path:
// any state failure — including a failed identification, which always degrades
// to unknown — must never affect mirroring or input (requirement 008).
//
// @consumes internal/protocol
package agentstate
