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
//
// @contract
// @pre Token 非空
// @post 服务端凭 Token 判定身份后回 TypeAuthAck；Token 不出现在任何回执里
// @err Validate 对空 Token 返回 ErrInvalidField
// @inv Token 绝不被记录或回显（011 路由 (a)）
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
// 002): it aggregates every session whose cwd equals Cwd. In a full Listing,
// Sessions carries the group's members; in a ListDelta's ChangedWorkspaces it
// is empty and only the session count is meaningful.
type Workspace struct {
	Cwd          string    `json:"cwd"`
	SessionCount int       `json:"session_count"`
	Sessions     []Session `json:"sessions,omitempty"`
}

// Session is one second-level entry of the model (requirement 002): a single
// mirrored agent CLI pane. Ref is a server-assigned opaque string that the
// client uses to address subscribe / input / scrollback / resize; it is
// distinct from the display-only Name. Rows/Cols are the pane's current
// dimensions.
type Session struct {
	Ref  string `json:"ref"`
	Name string `json:"name"`
	Cwd  string `json:"cwd"`
	Rows uint16 `json:"rows"`
	Cols uint16 `json:"cols"`
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
//
// Keys is the R-1 named-key alternative (requirement 017): when present, the
// server sends the named special keys without appending an Enter — the
// shortcut-bar semantics are "press that key once". Text and Keys are mutually
// exclusive (a frame carrying both is a protocol error, docs/protocol.md §4.2);
// neither present means a bare Enter, matching the pre-Keys behavior.
//
// AttachmentPath is an additive optional field (feat-image-upload-inline,
// requirement 042; two-step preview added by requirement 057): the
// host-absolute path of an image the client already uploaded via
// POST /upload. AttachmentPath is empty in the overwhelming common case
// (plain text messages) and does not change Text's existing semantics; an
// empty AttachmentPath behaves byte-identically to the pre-existing
// Text-only path. AttachmentPath and Keys are mutually exclusive, same rule
// as Text and Keys.
//
// Two ways a non-empty AttachmentPath is handled, both ending in the same
// `[Image #N]` result — which one runs is decided server-side, transparent
// to the client:
//   - Preview-confirmed (requirement 057, the common path when the client
//     already sent AttachPreview for this exact ref+path): the image is
//     already pasted into the pane. The server waits out whatever remains
//     of the settle window since that preview (often zero — the user's own
//     typing time already covered it) and injects only Text + Enter.
//   - Fallback (AttachmentPath 057, path is empty, stale, or does not match
//     any recorded preview — including plain old clients that never call
//     AttachPreview): the server pastes the path itself, as its own
//     bracketed paste ahead of Text — via bridge.Pane.InjectWithAttachment,
//     never combined into the same paste as Text — then waits the full
//     settle window before Enter. This is the original feat-image-upload-inline
//     behavior, kept as the compatibility path.
//
// @contract
// @pre ReqID >= 1、Ref 非空、(Text 或 AttachmentPath 非空) 与 Keys 至多一类非空、Keys 中每个键都属闭集
// @post 该帧在 wire 上合法（Validate 通过）且不附带服务端状态变更
// @err Validate 对 ReqID 0、空 Ref、(text/attachment)+keys 并存、未知 key 返回 ErrInvalidField
// @inv 空 Text 且空 Keys 且空 AttachmentPath 是合法的裸 Enter，服务端必须接受；
//
//	AttachmentPath 为空时的行为与本字段引入前逐字节一致
type Input struct {
	ReqID uint32 `json:"req_id"`
	Ref   string `json:"ref"`
	Text  string `json:"text,omitempty"`
	Keys  []Key  `json:"keys,omitempty"`
	// AttachmentPath is the host-absolute path of an uploaded image; see the
	// two handling modes documented above.
	AttachmentPath string `json:"attachment_path,omitempty"`
}

// AttachPreview pastes an image path into a pane ahead of send (C→S;
// requirement 057, the explicit exception to requirement 003's clause 1):
// "点加号选择图片之后就应该可以上传到对方主机了...点发送就直接上屏" — the
// server pastes Path into the pane the moment upload succeeds (Claude Code's
// own paste-path recognition inlines it as `[Image #N]` and starts decoding
// it in the background) instead of waiting until send, so the decode time is
// covered by whatever the user types next instead of adding to send latency.
//
// Path must be exactly the image path — never combined with caption text in
// the same paste (requirement 057 clause 2): mixing text into the pasted
// buffer falls back to Claude Code's slower clipboard-lookup branch and the
// following Enter gets silently swallowed (see the fix-image-upload-input-box
// round-1 postmortem this requirement's clause 2 exists to prevent).
//
// Requirement 057 clause 4: attachments accumulate — a pane may carry more
// than one pasted-but-unsent `[Image #N]` at once (the client sends one
// AttachPreview per picked image; the server never clears a pane's existing
// paste before adding another). Requirement 057 clause 3: the server never
// clears an unconfirmed preview either — if the user never sends, the
// `[Image #N]` placeholder is left in the pane. This is intentional: the
// client mirrors the pane, so the placeholder is visible to the user, not a
// silent leftover; reading the pane's rendered UI to decide whether it is
// safe to clear would be a new, silently-breakable dependency on Claude
// Code's own placeholder text format, judged not worth it for a visible
// (not silent) loose end.
//
// No ack on success — the mirror delta stream carries the `[Image #N]`
// result, same doctrine as ScrollWheel. TypeError on failure (pane gone /
// tmux unreachable).
//
// @contract
// @pre Ref 非空、Path 非空
// @post 服务端把 Path 贴进 pane（bracketed paste，不回车）并记下 (Ref, Path, 时间戳)，供后续 Input.AttachmentPath 命中同一 Path 时复用做补差额；成功无 ack，失败发 TypeError
// @err Validate 对空 Ref、空 Path 返回 ErrInvalidField
// @inv 从不清理 pane 里已贴的内容（requirement 057 clause 3）；Path 永不与其它内容共享同一次 paste
type AttachPreview struct {
	Ref  string `json:"ref"`
	Path string `json:"path"`
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
//
// @contract
// @pre ReqID >= 1、Ref 非空、Count >= 1
// @post 服务器返回一个 KindScrollback 的 BinaryPayload，其 FromLine/LineCount 是实际返回的范围
// @err Validate 对 ReqID 0、空 Ref、Count 0 返回 ErrInvalidField
// @inv 变更 FromLine 不影响该帧的合法性（任何 int32 都合法，由服务端 clamp）
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

// ScrollWheel delivers one scroll gesture to a remote pane (C→S;
// feat-remote-scroll-forward). Delta < 0 = scroll up (toward history);
// Delta > 0 = scroll down. Each call represents one wheel-click equivalent.
//
// The server atomically judges the pane's mouse-tracking state via
// tmux if-shell -F '#{mouse_any_flag}' and either injects raw mouse bytes
// (SGR or X10 format, no Enter appended) or enters tmux copy-mode and
// issues a scroll-up/down command. No ack on success (mirror deltas carry
// the visual result); TypeError on failure (pane gone / tmux unreachable).
//
// @contract
// @pre Ref 非空、Delta != 0
// @post 服务端执行 if-shell 原子判定+动作；成功无 ack，失败发 TypeError
// @err Validate 对空 Ref、Delta 0 返回 ErrInvalidField
// @inv 不追加 Enter，不调用 Inject 路径
type ScrollWheel struct {
	Ref   string `json:"ref"`
	Delta int32  `json:"delta"` // <0=up (history), >0=down
}

// PaneModeChanged notifies the client that a pane entered or exited tmux
// copy-mode (S→C; feat-remote-scroll-forward). The App shows or hides a
// minimal UI indicator so the user knows their keystrokes go to copy-mode
// commands rather than the shell/TUI. No ack required.
//
// @contract
// @pre Ref 非空
// @post 客户端更新 copy-mode 指示器；无需 ack
// @err Validate 对空 Ref 返回 ErrInvalidField
// @inv 服务端只在 copy-mode 真实进/出时发此帧，不重复推送同状态
type PaneModeChanged struct {
	Ref        string `json:"ref"`
	InCopyMode bool   `json:"in_copy_mode"`
}
