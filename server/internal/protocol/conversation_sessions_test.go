package protocol

import (
	"encoding/json"
	"testing"
)

func TestConversationSessionFramesRoundTripAndRequireIDOnlyScope(t *testing.T) {
	frames := []Typed{
		ConversationListSessions{ReqID: 7, Workspace: "/work"},
		ConversationListSessions{ReqID: 8, Ref: "pane"},
		ConversationSessions{ReqID: 7, Ref: "pane", OK: true, Sessions: json.RawMessage(`[{"session_id":"selected","name":"title"}]`)},
		ConversationSessions{ReqID: 8, OK: false, Reason: "unavailable"},
		ConversationResumeSession{Ref: "pane", SessionID: "selected"},
		ConversationSessionResumed{Ref: "pane", OK: true, Data: json.RawMessage(`{"session_id":"selected","stream":"new","head_seq":12}`)},
		ConversationSessionResumed{Ref: "pane", OK: false, Reason: "busy", Data: json.RawMessage(`{"busy":true}`)},
	}
	for _, frame := range frames {
		raw, err := MarshalFrame(frame)
		if err != nil {
			t.Fatal(err)
		}
		got, err := UnmarshalFrame(raw)
		if err != nil || got.FrameType() != frame.FrameType() {
			t.Fatalf("round trip %T err=%v", frame, err)
		}
	}
	for _, frame := range []Typed{ConversationListSessions{Workspace: "/work"}, ConversationListSessions{ReqID: 1}, ConversationResumeSession{Ref: "pane"}, ConversationResumeSession{SessionID: "selected"}, ConversationSessionResumed{Ref: "pane", OK: true, Reason: "bad"}} {
		if _, err := MarshalFrame(frame); err == nil {
			t.Fatalf("invalid %T accepted", frame)
		}
	}
}
