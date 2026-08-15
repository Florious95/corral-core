// Package agentstate maps per-agent CLI output and process trees to a
// normalized state (working/idle), degrading to unknown when undecidable.
//
// This is the round-4 rebuild (2026-08-15, ledger t.impl). The round-1..3
// decision layer was archived to docs/archive/agentstate-round4/ (058: three
// repairs all edited the same glyph-whitelist structure, which cannot answer
// the question — the glyph set changed and the whitelist went silently blind;
// two states share the same prefix glyph, so no glyph/prefix match can
// separate done from working). The new layer is a ZERO-glyph-whitelist
// decision:
//
//   - Existence (single-frame Detect): a structural work signal — the "esc to
//     interrupt" action bar below the last prompt anchor — means working.
//     Absence of any work signal means idle.
//   - Derivative (time-series Track): the pane's content changed between two
//     consecutive samples = activity = working; consecutive identical frames
//     = stopped = idle (a finished task's "✻ Churned for 4s" does not tick, so
//     identical frames are honest "stopped", never misread working).
//
// The state layer stays strictly isolated from the mirror/input path: any
// state failure — including a failed identification, which always degrades to
// unknown — must never affect mirroring or input (requirement 008).
//
// @consumes internal/protocol
package agentstate
