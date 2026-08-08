package protocol

import "encoding/json"

// Envelope is the outer JSON control frame. Every control message is exactly
// one Envelope: a protocol version, a frame-type discriminator, and a typed
// payload. The payload never contains terminal bytes — those travel in binary
// frames (see EncodeBinary). Clients MUST ignore unknown fields inside the
// envelope and inside any payload (forward compatibility); an unknown "type"
// is an error.
type Envelope struct {
	// V is the wire protocol version (see Version). MarshalFrame always sets
	// it to Version; UnmarshalFrame rejects anything else.
	V uint16 `json:"v"`

	// Type discriminates the payload structure.
	Type FrameType `json:"type"`

	// Payload holds the frame-type-specific object.
	Payload json.RawMessage `json:"payload,omitempty"`
}

// Typed is implemented by every control-frame payload. It lets the codec
// derive the wire "type" discriminator from the Go value and validate the
// frame before it is marshaled or after it is unmarshaled.
type Typed interface {
	// FrameType returns the wire "type" discriminator of this payload.
	FrameType() FrameType

	// Validate reports whether the frame is well-formed (required fields
	// present, enumerated values in the closed set). It is called by both
	// MarshalFrame and UnmarshalFrame so an invalid frame never crosses the
	// wire in either direction.
	Validate() error
}

// Auth is the pairing handshake (C→S). Token is write-only: it travels once
// from client to server, is never echoed in any reply, and must never be
// logged (requirement 011 route (a)).
type Auth struct {
	Token string `json:"token"`
}

// AuthAck is the server's verdict on Auth (S→C). OK=true means the connection
// is authenticated; OK=false means it was rejected and Reason carries the
// reason. The server MUST close the connection after a rejection, so the
// client can treat "connection closed right after auth" as a rejection too.
type AuthAck struct {
	OK     bool   `json:"ok"`
	Reason string `json:"reason,omitempty"`
}

// List requests a fresh full listing (C→S). ReqID correlates the Listing
// reply; it must be >= 1 so 0 is distinguishable from "unset".
type List struct {
	ReqID uint32 `json:"req_id"`
}

// Workspace is one first-level group of the two-level model (requirement
// 002): it aggregates every session whose cwd equals Cwd. AggregateState is
// computed server-side and is authoritative — the client renders it and never
// recomputes the aggregation rule. In a full Listing, Sessions carries the
// group's members; in a ListDelta's ChangedWorkspaces it is empty and only the
// aggregate/count are meaningful.
type Workspace struct {
	Cwd            string     `json:"cwd"`
	SessionCount   int        `json:"session_count"`
	AggregateState AgentState `json:"aggregate_state"`
	Sessions       []Session  `json:"sessions,omitempty"`
}

// Session is one second-level entry of the model (requirement 002): a single
// mirrored agent CLI pane. Ref is a server-assigned opaque string that the
// client uses to address subscribe / input / scrollback / resize; it is
// distinct from the display-only Name. Rows/Cols are the pane's current
// dimensions.
type Session struct {
	Ref   string     `json:"ref"`
	Name  string     `json:"name"`
	Cwd   string     `json:"cwd"`
	State AgentState `json:"state"`
	Rows  uint16     `json:"rows"`
	Cols  uint16     `json:"cols"`
}

// Listing is the full two-level workspace/session model (S→C, reply to List).
// Seq is a monotonically increasing listing sequence: if a ListDelta arrives
// before any Listing, or with a Seq that does not continue the client's last
// seen value, the client MUST re-request a full Listing (requirement 004
// stateless replay).
type Listing struct {
	ReqID      uint32      `json:"req_id"`
	Seq        uint64      `json:"seq"`
	Workspaces []Workspace `json:"workspaces"`
}

// ListDelta is a server-pushed incremental change to the listing (S→C,
// unsolicited; requirement 001 fleet scenario avoids polling). The sets are
// disjoint — a session appears in exactly one of AddedSessions,
// RemovedRefs, or ChangedSessions per delta. Added/Removed/Changed fields
// carry full current values so the client applies them by replace.
// ChangedWorkspaces carries workspace-level aggregate/count changes (the
// server re-computes aggregates so the rule stays single-sourced).
type ListDelta struct {
	Seq               uint64      `json:"seq"`
	AddedSessions     []Session   `json:"added_sessions,omitempty"`
	RemovedRefs       []string    `json:"removed_refs,omitempty"`
	ChangedSessions   []Session   `json:"changed_sessions,omitempty"`
	ChangedWorkspaces []Workspace `json:"changed_workspaces,omitempty"`
}

// Subscribe starts mirroring a session (C→S). Rows/Cols are the client's
// initial terminal dimensions, applied so the CLI redraws for the phone
// (requirement 005). The server answers with a binary Snapshot frame followed
// by a Delta stream; a failure to subscribe is reported as an Error frame.
// Subscribe is idempotent for the same ref: re-subscribing replays a fresh
// snapshot and re-streams (requirement 004 reconnect semantics).
type Subscribe struct {
	Ref  string `json:"ref"`
	Rows uint16 `json:"rows"`
	Cols uint16 `json:"cols"`
}

// Unsubscribe stops mirroring a session (C→S). It is idempotent: unsubscribing
// a session that is not subscribed is not an error. Closing the connection
// implicitly unsubscribes every session the connection held.
type Unsubscribe struct {
	Ref string `json:"ref"`
}

// Input injects one whole text line into a session (C→S; requirement 003 —
// whole-line send-keys, never per-keystroke). The server appends a newline
// (Enter) after Text, matching "inject then Enter"; an empty Text is a bare
// Enter and is allowed. The server MUST reply with InputAck so "sent with no
// effect" cannot happen.
type Input struct {
	ReqID uint32 `json:"req_id"`
	Ref   string `json:"ref"`
	Text  string `json:"text"`
}

// InputAck is the decidable receipt of an Input (S→C; requirement 003 send-
// must-arrive). OK=true means the bytes entered the pane; OK=false means they
// did not, and Reason says why (a closed InputFailReason set). Reason is
// present if and only if OK is false.
type InputAck struct {
	ReqID  uint32          `json:"req_id"`
	OK     bool            `json:"ok"`
	Reason InputFailReason `json:"reason,omitempty"`
}

// Scrollback fetches one line range of history (C→S; requirement 006 local
// scrolling). FromLine addresses lines in tmux capture-pane semantics,
// relative to the current screen top: 0 = the top line of the visible screen,
// negative = history above it. Count is the number of lines requested (>= 1).
// The server clamps to the available range and reports the actual range in
// the binary Scrollback reply.
type Scrollback struct {
	ReqID    uint32 `json:"req_id"`
	Ref      string `json:"ref"`
	FromLine int32  `json:"from_line"`
	Count    uint32 `json:"count"`
}

// Resize reports the client's current terminal dimensions (C→S; requirement
// 005). The server resizes the underlying pane so the CLI redraws itself;
// grouped sessions + window-size latest mean the pane follows whoever last
// operated it. Applies to a subscribed session.
type Resize struct {
	Ref  string `json:"ref"`
	Rows uint16 `json:"rows"`
	Cols uint16 `json:"cols"`
}

// ErrorFrame is a protocol-level failure (S→C): bad frame, unknown type,
// missing session, unsupported version, or internal error. Code is a
// machine-readable closed set the client switches on; Reason is
// human-readable.
type ErrorFrame struct {
	Code   ErrorCode `json:"code"`
	Reason string    `json:"reason"`
}

// UploadResp is the JSON body the image-upload HTTP endpoint returns on
// success (over plain HTTP, not WebSocket): the absolute path of the file
// after the server wrote it to the host disk. The client then injects this
// path as Input.Text so the CLI can load the image (requirement 003 image
// pipeline). It is NOT a WebSocket control frame and does not implement
// Typed; Validate is provided for the HTTP handler's use.
type UploadResp struct {
	Path string `json:"path"`
}
