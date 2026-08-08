// Package agentstate maps per-agent CLI output and process trees to a
// normalized state (working/idle/blocked/done), degrading to unknown when
// undecidable.
//
// Landing zone for the state-parser task, with per-agent adapters (first
// batch: Claude Code, Codex) referencing the Apache-2.0 herdr implementation
// in a license-compliant way. The state layer must stay strictly isolated
// from the mirror/input path: any state failure must never affect
// mirroring or input (requirement 008). Only the package contract is
// declared here; no implementation has landed yet.
package agentstate
