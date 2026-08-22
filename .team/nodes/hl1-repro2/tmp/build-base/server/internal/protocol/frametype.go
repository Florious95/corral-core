package protocol

// FrameType is the string discriminator of a JSON control frame (the "type"
// field of the envelope). New frame types are additive: a receiver MUST treat
// an unknown type as a protocol error and MUST ignore unknown envelope and
// payload fields (forward compatibility).
type FrameType string

const (
	// TypeAuth is the pairing handshake (C→S): carries the pairing token.
	// The token is write-only — never echoed, never logged (011 route (a)).
	TypeAuth FrameType = "auth"

	// TypeAuthAck is the server's verdict on TypeAuth (S→C): ok, or the
	// rejection reason. The server MUST close the connection after a
	// rejection.
	TypeAuthAck FrameType = "auth_ack"

	// TypeList requests a fresh full workspace/session listing (C→S).
	TypeList FrameType = "list"

	// TypeListing is the full two-level listing, reply to TypeList (S→C).
	TypeListing FrameType = "listing"

	// TypeListDelta is a server-pushed incremental listing change (S→C,
	// unsolicited; requirement 001 fleet scenario — no polling).
	TypeListDelta FrameType = "list_delta"

	// TypeSubscribe starts mirroring a session (C→S): the server answers with
	// a binary Snapshot frame, then a Delta stream.
	TypeSubscribe FrameType = "subscribe"

	// TypeUnsubscribe stops mirroring a session (C→S). Idempotent.
	TypeUnsubscribe FrameType = "unsubscribe"

	// TypeInput injects one whole text line into a session (C→S; requirement
	// 003 whole-line send-keys). It MUST be answered with TypeInputAck.
	TypeInput FrameType = "input"

	// TypeInputAck is the decidable receipt of TypeInput (S→C; requirement 003
	// send-must-arrive): success, or a machine-readable failure reason.
	TypeInputAck FrameType = "input_ack"

	// TypeScrollback fetches one line range of history (C→S; requirement 006
	// local scrolling). The reply is a binary Scrollback frame.
	TypeScrollback FrameType = "scrollback"

	// TypeResize reports the client's terminal dimensions so the CLI redraws
	// itself (C→S; requirement 005).
	TypeResize FrameType = "resize"

	// TypeError is a protocol-level failure (S→C): bad frame, unknown type,
	// missing session, unsupported version, internal error.
	TypeError FrameType = "error"

	// TypeScrollWheel delivers one scroll gesture to a remote pane (C→S;
	// feat-remote-scroll-forward). The server atomically judges the pane's
	// mouse-tracking state and either injects raw mouse bytes or enters tmux
	// copy-mode. No ack on success; TypeError on failure.
	TypeScrollWheel FrameType = "scroll_wheel"

	// TypePaneModeChanged notifies the client that a pane entered or exited
	// tmux copy-mode (S→C; feat-remote-scroll-forward). The App shows or hides
	// a minimal indicator so the user knows their keystrokes go to copy-mode
	// rather than the shell/TUI.
	TypePaneModeChanged FrameType = "pane_mode_changed"

	// TypeAttachPreview pastes an image path into a pane ahead of send (C→S;
	// requirement 057, the explicit exception to requirement 003's clause 1).
	// No ack on success (the mirror delta stream carries the `[Image #N]`
	// result); TypeError on failure.
	TypeAttachPreview FrameType = "attach_preview"

	// TypeLevel2Subscribe starts the second-level live stream (C→S; requirement
	// 061). The client sends it on entering the second-level menu; while ≥1
	// level-2 subscriber exists the server scans tmux and pushes TypeLevel2Frame
	// on change (and TypeLevel2Heartbeat when unchanged).
	TypeLevel2Subscribe FrameType = "level2_subscribe"

	// TypeLevel2Unsubscribe stops the second-level live stream (C→S). Idempotent.
	// Sent on leaving the second-level menu; at zero subscribers the server
	// parks the scan loop (idle CPU ≈ 0).
	TypeLevel2Unsubscribe FrameType = "level2_unsubscribe"

	// TypeLevel2Frame is the server-pushed second-level snapshot (S→C;
	// requirement 061). Pushed on subscribe and when the workspace snapshot
	// changes. Seq lets the client detect a gap and re-subscribe.
	TypeLevel2Frame FrameType = "level2_frame"

	// TypeLevel2Heartbeat is the low-frequency keep-alive (S→C; requirement
	// 061) when a subscribed snapshot has not changed.
	TypeLevel2Heartbeat FrameType = "level2_heartbeat"

	// TypeOverlaySubscribe starts the overlay capture stream (C→S; requirement
	// 064). Independent of the level-2 list stream. At ≥1 subscriber the server
	// attaches a dedicated scratch-session client and pushes TypeOverlayFrame.
	TypeOverlaySubscribe FrameType = "overlay_subscribe"

	// TypeOverlayUnsubscribe stops the overlay capture stream (C→S). Idempotent.
	// At zero subscribers the server tears down the scratch client (idle CPU ≈ 0).
	TypeOverlayUnsubscribe FrameType = "overlay_unsubscribe"

	// TypeOverlayFrame is one captured choose-tree screen (S→C; requirement 064).
	// Text is the PTY bytes of the dedicated client (not a self-drawn tree).
	TypeOverlayFrame FrameType = "overlay_frame"
)
