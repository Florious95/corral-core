package protocol

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// conversation_v1 multiplexes structured Agent conversations over the same
// authenticated /ws connection as the terminal mirror (docs/contracts/06).
// A client may only send these frames after auth_ack echoed
// ConversationCapability; there is no second socket and no separate route.
//
// @contract
// @pre the connection negotiated ConversationCapability in auth/auth_ack
// @post every C→S conversation frame has a decidable reply: conversation_ready,
// conversation_event (including a failed `response`), conversation_closed or
// conversation_created
// @err malformed frames are rejected by Validate before reaching the handler
// @inv events are the managed agent's own JSON records, never terminal bytes

// ConversationCapability is the auth capability that enables these frames.
const ConversationCapability = "conversation_v1"

const (
	// TypeConversationCreate launches a managed structured agent (C→S).
	TypeConversationCreate FrameType = "conversation_create"
	// TypeConversationCreated answers TypeConversationCreate (S→C).
	TypeConversationCreated FrameType = "conversation_created"
	// TypeConversationSubscribe attaches to one managed conversation (C→S).
	TypeConversationSubscribe FrameType = "conversation_subscribe"
	// TypeConversationUnsubscribe detaches one conversation (C→S). Idempotent.
	TypeConversationUnsubscribe FrameType = "conversation_unsubscribe"
	// TypeConversationCommand forwards one whitelisted agent command (C→S).
	TypeConversationCommand FrameType = "conversation_command"
	// TypeConversationReady opens a subscribed stream (S→C).
	TypeConversationReady FrameType = "conversation_ready"
	// TypeConversationEvent carries one agent record (S→C).
	TypeConversationEvent FrameType = "conversation_event"
	// TypeConversationClosed ends a subscribed stream (S→C).
	TypeConversationClosed FrameType = "conversation_closed"
)

// Closed set for ConversationClosed.Reason.
const (
	// ConversationUnavailable: the ref is not a managed conversation pane.
	ConversationUnavailable = "unavailable"
	// ConversationExited: the managed agent process is gone.
	ConversationExited = "exited"
	// ConversationLost: the stream dropped while the agent is alive (slow
	// reader or transient IPC failure). Resubscribe with after_seq.
	ConversationLost = "lost"
)

// ConversationCreate mirrors CreateAgent; only providers with a structured
// adapter are accepted (Pi RPC in phase 1).
type ConversationCreate struct {
	ReqID     uint32 `json:"req_id"`
	Workspace string `json:"workspace"`
	AnchorRef string `json:"anchor_ref"`
	Provider  string `json:"provider"`
	Name      string `json:"name"`
}

// ConversationCreated carries the same controlled result vocabulary as
// CreateAgentResult.
type ConversationCreated struct {
	ReqID  uint32 `json:"req_id"`
	OK     bool   `json:"ok"`
	Ref    string `json:"ref,omitempty"`
	Name   string `json:"name,omitempty"`
	Reason string `json:"reason,omitempty"`
}

// ConversationSubscribe resumes after AfterSeq when Stream still matches the
// worker's stream; otherwise the server answers ready{reset:true} and replays
// everything it retains.
type ConversationSubscribe struct {
	Ref      string `json:"ref"`
	Stream   string `json:"stream,omitempty"`
	AfterSeq uint64 `json:"after_seq,omitempty"`
}

// ConversationUnsubscribe detaches the stream for Ref.
type ConversationUnsubscribe struct {
	Ref string `json:"ref"`
}

// ConversationCommand carries one agent command object. ID correlates the
// agent's `response` record and is stamped into the forwarded command.
type ConversationCommand struct {
	Ref     string          `json:"ref"`
	ID      string          `json:"id"`
	Command json.RawMessage `json:"command"`
}

// ConversationReady precedes the replay of a subscription. Reset means the
// client must discard local state for Ref before applying the replay.
type ConversationReady struct {
	Ref              string `json:"ref"`
	Stream           string `json:"stream"`
	HeadSeq          uint64 `json:"head_seq"`
	Reset            bool   `json:"reset"`
	HistoryTruncated bool   `json:"history_truncated"`
	Running          bool   `json:"running"`
	ServerTimeMS     int64  `json:"server_time_ms"`
}

// ConversationEvent is one agent record. Seq is 0 for server-synthesized,
// non-replayable records (for example a rejected command's response).
type ConversationEvent struct {
	Ref   string          `json:"ref"`
	Seq   uint64          `json:"seq,omitempty"`
	TS    int64           `json:"ts,omitempty"`
	Event json.RawMessage `json:"event"`
}

// ConversationClosed ends the stream for Ref with a reason from the closed set.
type ConversationClosed struct {
	Ref    string `json:"ref"`
	Reason string `json:"reason"`
}

func (ConversationCreate) FrameType() FrameType      { return TypeConversationCreate }
func (ConversationCreated) FrameType() FrameType     { return TypeConversationCreated }
func (ConversationSubscribe) FrameType() FrameType   { return TypeConversationSubscribe }
func (ConversationUnsubscribe) FrameType() FrameType { return TypeConversationUnsubscribe }
func (ConversationCommand) FrameType() FrameType     { return TypeConversationCommand }
func (ConversationReady) FrameType() FrameType       { return TypeConversationReady }
func (ConversationEvent) FrameType() FrameType       { return TypeConversationEvent }
func (ConversationClosed) FrameType() FrameType      { return TypeConversationClosed }

func (c ConversationCreate) Validate() error {
	if c.ReqID == 0 {
		return fmt.Errorf("%w: conversation_create req_id must be >= 1", ErrInvalidField)
	}
	return nil
}

func (r ConversationCreated) Validate() error {
	if r.ReqID == 0 {
		return fmt.Errorf("%w: conversation_created req_id must be >= 1", ErrInvalidField)
	}
	if r.OK != (r.Reason == "") || r.OK != (r.Ref != "") {
		return fmt.Errorf("%w: conversation_created must carry either ref or reason", ErrInvalidField)
	}
	return nil
}

func (s ConversationSubscribe) Validate() error {
	if s.Ref == "" {
		return fmt.Errorf("%w: conversation_subscribe ref must be non-empty", ErrInvalidField)
	}
	return nil
}

func (u ConversationUnsubscribe) Validate() error {
	if u.Ref == "" {
		return fmt.Errorf("%w: conversation_unsubscribe ref must be non-empty", ErrInvalidField)
	}
	return nil
}

func (c ConversationCommand) Validate() error {
	if c.Ref == "" || c.ID == "" || len(c.ID) > 64 {
		return fmt.Errorf("%w: conversation_command needs ref and an id of 1..64 bytes", ErrInvalidField)
	}
	if trimmed := bytes.TrimSpace(c.Command); len(trimmed) == 0 || trimmed[0] != '{' {
		return fmt.Errorf("%w: conversation_command command must be an object", ErrInvalidField)
	}
	return nil
}

func (r ConversationReady) Validate() error {
	if r.Ref == "" || r.Stream == "" {
		return fmt.Errorf("%w: conversation_ready needs ref and stream", ErrInvalidField)
	}
	return nil
}

func (e ConversationEvent) Validate() error {
	if e.Ref == "" || len(e.Event) == 0 {
		return fmt.Errorf("%w: conversation_event needs ref and event", ErrInvalidField)
	}
	return nil
}

func (c ConversationClosed) Validate() error {
	switch c.Reason {
	case ConversationUnavailable, ConversationExited, ConversationLost:
	default:
		return fmt.Errorf("%w: unknown conversation_closed reason %q", ErrInvalidField, c.Reason)
	}
	if c.Ref == "" {
		return fmt.Errorf("%w: conversation_closed ref must be non-empty", ErrInvalidField)
	}
	return nil
}
