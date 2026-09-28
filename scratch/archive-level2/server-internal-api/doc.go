// Package api implements the service-side WebSocket API and the image upload
// endpoint, wiring together discovery and bridge (task ws-api).
//
// The API serves the transport contract of docs/protocol.md v1 over a single
// HTTP port:
//
//   - /ws — a WebSocket carrying interleaved JSON control frames (auth, list /
//     listing, subscribe, input + decidable input_ack, scrollback, resize,
//     unsubscribe, list_delta pushes) and binary terminal stream frames
//     (snapshot, delta, scrollback with its 12-byte metadata header). The
//     machine-verifiable frame codec is internal/protocol.
//   - /upload — a multipart HTTP endpoint (docs/protocol.md §8) that writes an
//     uploaded image to the host disk and returns its absolute path, which the
//     client then injects as input text.
//
// Wiring: discovery supplies the two-level workspace snapshot, bridge exposes
// each tmux pane as a mirrorable terminal, protocol is the machine-verifiable
// frame codec, agentstate resolves each pane's agent state behind the
// StateProvider seam (task fix-state-wiring). A periodic heartbeat re-scans and
// fans list_delta out to every live client; a reconnecting client re-auths,
// re-subscribes, and receives a fresh snapshot (requirement 004 stateless
// replay). The server keeps no client session state beyond the live
// connections.
package api

// The package's cross-layer dependency declarations (T3-4). Each @consumes is
// kept in its own comment block because the T3-4 extractor records one
// consumes target per block.
//
// @consumes internal/agentstate

// @consumes internal/bridge

// @consumes internal/discovery

// @consumes internal/protocol
