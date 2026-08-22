// Package protocol defines the wire contract between the Android app and the
// agentmirrord service. The human-readable contract is docs/protocol.md; this
// package is its machine-verifiable Go reference implementation.
//
// @consumes none
// @produces internal/protocol
//
// Transport model: a single WebSocket connection carries two interleaved
// message kinds —
//   - text messages are JSON control frames (pairing auth, workspace listing +
//     incremental deltas, session subscribe/unsubscribe, input injection with
//     a decidable ack, scrollback paging, resize, agent state, errors);
//   - binary messages are terminal byte-stream frames (raw ANSI/VT content,
//     never JSON-escaped). Each binary frame header carries a session
//     reference so one connection can multiplex several mirrors, and a
//     protocol-version byte.
//
// Versioning is explicit: every JSON frame carries a "v" field and every
// binary frame a version byte (see Version). New frame types and new JSON
// fields are additive; an unknown "type" is an error, unknown JSON fields are
// ignored by design.
//
// This package declares frame shapes and their codec (marshal / unmarshal
// round-trips) only; it implements no service logic — tmux discovery, the
// pane bridge, and the WebSocket API land in later tasks. Requirement 008's
// isolation iron law is reflected structurally: agent state travels only in
// control frames (listing / list_delta) and never in the binary mirror
// channel, so an "unknown" state can never block mirroring or input.
package protocol
