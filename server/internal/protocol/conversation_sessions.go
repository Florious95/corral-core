package protocol

// Pi history control is opt-in through conversation_v1. Paths remain host-only.
// @contract
// @pre authenticated conversation_v1 and a discovered ref or exact workspace
// @post list/resume have a correlated finite result, including visible failure
// @err malformed IDs and missing selection scope are rejected
// @inv clients select session IDs, never arbitrary host file paths

import (
	"encoding/json"
	"fmt"
)

const (
	TypeConversationListSessions   FrameType = "conversation_list_sessions"
	TypeConversationSessions       FrameType = "conversation_sessions"
	TypeConversationResumeSession  FrameType = "conversation_resume_session"
	TypeConversationSessionResumed FrameType = "conversation_session_resumed"
)

// ConversationListSessions requests the native store for a ref or unique RPC pane in a listed workspace.
type ConversationListSessions struct {
	ReqID     uint32 `json:"req_id"`
	Ref       string `json:"ref,omitempty"`
	Workspace string `json:"workspace,omitempty"`
}

// ConversationSessions returns bounded display metadata, never host file paths.
type ConversationSessions struct {
	ReqID    uint32          `json:"req_id"`
	Ref      string          `json:"ref,omitempty"`
	OK       bool            `json:"ok"`
	Sessions json.RawMessage `json:"sessions,omitempty"`
	Reason   string          `json:"reason,omitempty"`
}

// ConversationResumeSession selects a cwd-scoped ID; Force requires explicit stop consent.
type ConversationResumeSession struct {
	ReqID     uint32 `json:"req_id,omitempty"`
	Ref       string `json:"ref"`
	SessionID string `json:"session_id"`
	Force     bool   `json:"force,omitempty"`
}

// ConversationSessionResumed identifies the new replay stream, or a visible failure/busy result.
type ConversationSessionResumed struct {
	ReqID  uint32          `json:"req_id,omitempty"`
	Ref    string          `json:"ref"`
	OK     bool            `json:"ok"`
	Data   json.RawMessage `json:"data,omitempty"`
	Reason string          `json:"reason,omitempty"`
}

func (ConversationListSessions) FrameType() FrameType   { return TypeConversationListSessions }
func (ConversationSessions) FrameType() FrameType       { return TypeConversationSessions }
func (ConversationResumeSession) FrameType() FrameType  { return TypeConversationResumeSession }
func (ConversationSessionResumed) FrameType() FrameType { return TypeConversationSessionResumed }
func (r ConversationListSessions) Validate() error {
	if r.ReqID == 0 || (r.Ref == "" && r.Workspace == "") {
		return fmt.Errorf("%w: list_sessions requires req_id and ref or workspace", ErrInvalidField)
	}
	return nil
}
func (r ConversationSessions) Validate() error {
	if r.ReqID == 0 || r.OK != (r.Reason == "") {
		return fmt.Errorf("%w: invalid sessions result", ErrInvalidField)
	}
	return nil
}
func (r ConversationResumeSession) Validate() error {
	if r.Ref == "" || r.SessionID == "" || len(r.SessionID) > 128 {
		return fmt.Errorf("%w: resume_session requires ref and session_id", ErrInvalidField)
	}
	return nil
}
func (r ConversationSessionResumed) Validate() error {
	if r.Ref == "" || r.OK != (r.Reason == "") {
		return fmt.Errorf("%w: invalid resume result", ErrInvalidField)
	}
	return nil
}
