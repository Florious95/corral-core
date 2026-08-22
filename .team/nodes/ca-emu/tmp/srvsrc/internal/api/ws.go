package api

// ws.go declares the WebSocket transport types used across the connection
// layer. coder/websocket is the chosen library (docs/protocol.md §1): it is
// a single, clean, dependency-light RFC 6455 implementation, Apache-2.0
// licensed (matching the project's license posture, requirement 008), with an
// API that guarantees one-writer-at-a-time serialization internally so the
// per-connection send queue never needs its own write lock. gorilla/websocket
// was the alternative; coder's ctx-based Read/Write makes cancellation
// explicit, which fits the per-connection lifecycle here.
//
// The wrapper types (wsConn, wsMsg) hide the library behind a tiny surface so
// the frame router reads like protocol code, not transport code.

import (
	"github.com/coder/websocket"
)

// Message-kind constants used by the send queue, mirroring the WebSocket text
// / binary split of the protocol (docs/protocol.md §1: control frames are
// text, terminal byte streams are binary).
const (
	wsText   = websocket.MessageText
	wsBinary = websocket.MessageBinary
)

// wsMsg is one message queued for the connection's writer goroutine. Control
// frames and binary mirror frames share the queue so their order is the order
// the router enqueued them (snapshot before delta, ack after input).
type wsMsg struct {
	typ  websocket.MessageType
	data []byte

	// close, when set, tells the writer to send a WebSocket close frame with
	// the given code/reason after any already-queued message (used for auth
	// rejection and unsupported-version: send the error/ack, then close).
	close  bool
	code   websocket.StatusCode
	reason string
}
