package protocol_test

import (
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// roundTrip marshals p, unmarshals the bytes, and returns the decoded frame.
// Every control frame must survive the marshal→unmarshal cycle losslessly.
func roundTrip(t *testing.T, p protocol.Typed) protocol.Typed {
	t.Helper()
	data, err := protocol.MarshalFrame(p)
	if err != nil {
		t.Fatalf("MarshalFrame(%T) failed: %v", p, err)
	}
	got, err := protocol.UnmarshalFrame(data)
	if err != nil {
		t.Fatalf("UnmarshalFrame(%T) of its own bytes failed: %v", p, err)
	}
	return got
}

// TestControlFramesRoundTrip drives every control frame through
// marshal→unmarshal and checks field-for-field equality.
func TestControlFramesRoundTrip(t *testing.T) {
	tests := []struct {
		name string
		f    protocol.Typed
	}{
		{"auth", protocol.Auth{Token: "tok-abc-123"}},
		{"auth_ack accepted", protocol.AuthAck{OK: true}},
		{"auth_ack rejected", protocol.AuthAck{OK: false, Reason: "bad token"}},
		{"list", protocol.List{ReqID: 7}},
		{"listing", protocol.Listing{
			ReqID: 7, Seq: 42,
			Workspaces: []protocol.Workspace{
				{
					Cwd: "/proj/a", SessionCount: 2, AggregateState: protocol.StateBlocked,
					Sessions: []protocol.Session{
						{Ref: "s1", Name: "claude", Cwd: "/proj/a", State: protocol.StateWorking, Rows: 40, Cols: 100},
						{Ref: "s2", Name: "codex", Cwd: "/proj/a", State: protocol.StateBlocked, Rows: 24, Cols: 80},
					},
				},
				{Cwd: "/proj/b", SessionCount: 1, AggregateState: protocol.StateUnknown,
					Sessions: []protocol.Session{
						{Ref: "s3", Name: "claude", Cwd: "/proj/b", State: protocol.StateUnknown, Rows: 30, Cols: 90},
					}},
			},
		}},
		{"list_delta added", protocol.ListDelta{
			Seq: 43,
			AddedSessions: []protocol.Session{
				{Ref: "s4", Name: "claude", Cwd: "/proj/c", State: protocol.StateDone, Rows: 25, Cols: 100},
			},
		}},
		{"list_delta removed", protocol.ListDelta{Seq: 44, RemovedRefs: []string{"s1"}}},
		{"list_delta changed", protocol.ListDelta{Seq: 45,
			ChangedSessions:   []protocol.Session{{Ref: "s2", Name: "codex", Cwd: "/proj/a", State: protocol.StateDone, Rows: 24, Cols: 80}},
			ChangedWorkspaces: []protocol.Workspace{{Cwd: "/proj/a", SessionCount: 2, AggregateState: protocol.StateDone}},
		}},
		{"subscribe", protocol.Subscribe{Ref: "s1", Rows: 40, Cols: 100}},
		{"unsubscribe", protocol.Unsubscribe{Ref: "s1"}},
		{"input", protocol.Input{ReqID: 9, Ref: "s1", Text: "/model opus"}},
		{"input empty text", protocol.Input{ReqID: 10, Ref: "s1"}},
		{"input keys", protocol.Input{ReqID: 10, Ref: "s1", Keys: []protocol.Key{protocol.KeyEsc, protocol.KeyUp, protocol.KeyTab}}},
		{"input_ack ok", protocol.InputAck{ReqID: 9, OK: true}},
		{"input_ack fail", protocol.InputAck{ReqID: 9, OK: false, Reason: protocol.InputFailInjectFailed}},
		{"scrollback", protocol.Scrollback{ReqID: 5, Ref: "s1", FromLine: -300, Count: 100}},
		{"resize", protocol.Resize{Ref: "s1", Rows: 48, Cols: 120}},
		{"error frame", protocol.ErrorFrame{Code: protocol.ErrCodeSessionNotFound, Reason: "session s1 vanished"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := roundTrip(t, tt.f)
			if !reflect.DeepEqual(got, tt.f) {
				t.Errorf("round trip mismatch:\n got %#v\nwant %#v", got, tt.f)
			}
		})
	}
}

// TestMarshalEnvelopeShape checks the wire shape of a marshaled frame: the
// version stamp and the type discriminator.
func TestMarshalEnvelopeShape(t *testing.T) {
	data, err := protocol.MarshalFrame(protocol.List{ReqID: 1})
	if err != nil {
		t.Fatal(err)
	}
	s := string(data)
	if !strings.Contains(s, `"v":1`) {
		t.Errorf("marshaled frame missing version stamp: %s", s)
	}
	if !strings.Contains(s, `"type":"list"`) {
		t.Errorf("marshaled frame missing type discriminator: %s", s)
	}
}

// TestMarshalValidatesFirst: MarshalFrame must refuse an invalid frame rather
// than emitting garbage.
func TestMarshalValidatesFirst(t *testing.T) {
	cases := []struct {
		name string
		f    protocol.Typed
	}{
		{"auth empty token", protocol.Auth{}},
		{"list req 0", protocol.List{}},
		{"listing bad aggregate", protocol.Listing{ReqID: 1, Seq: 1,
			Workspaces: []protocol.Workspace{{Cwd: "/x", SessionCount: 1, AggregateState: "zombie"}}}},
		{"subscribe zero cols", protocol.Subscribe{Ref: "s1", Rows: 24, Cols: 0}},
		{"input req 0", protocol.Input{ReqID: 0, Ref: "s1"}},
		{"input both text and keys", protocol.Input{ReqID: 1, Ref: "s1", Text: "hi", Keys: []protocol.Key{protocol.KeyEsc}}},
		{"input unknown key", protocol.Input{ReqID: 1, Ref: "s1", Keys: []protocol.Key{"home"}}},
		{"input_ack fail no reason", protocol.InputAck{ReqID: 1, OK: false}},
		{"input_ack ok with reason", protocol.InputAck{ReqID: 1, OK: true, Reason: protocol.InputFailInternal}},
		{"input_ack unknown reason", protocol.InputAck{ReqID: 1, OK: false, Reason: "who knows"}},
		{"auth_ack rejected no reason", protocol.AuthAck{OK: false}},
		{"auth_ack accepted with reason", protocol.AuthAck{OK: true, Reason: "why"}},
		{"error unknown code", protocol.ErrorFrame{Code: "boom"}},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			if _, err := protocol.MarshalFrame(tt.f); err == nil {
				t.Fatalf("MarshalFrame(%T) succeeded, want validation error", tt.f)
			}
		})
	}
}

// TestUnmarshalRedPaths drives the failure paths the codec must reject.
func TestUnmarshalRedPaths(t *testing.T) {
	cases := []struct {
		name string
		msg  string
		want error
	}{
		{"missing version", `{"type":"list","payload":{"req_id":1}}`, protocol.ErrMissingVersion},
		{"unsupported version", `{"v":2,"type":"list","payload":{"req_id":1}}`, protocol.ErrUnsupportedVersion},
		{"unknown type", `{"v":1,"type":"nope","payload":{}}`, protocol.ErrUnknownType},
		{"empty type", `{"v":1,"type":"","payload":{}}`, protocol.ErrInvalidField},
		{"malformed json", `{"v":1,`, protocol.ErrBadPayload},
		{"payload not object", `{"v":1,"type":"auth","payload":"notanobject"}`, protocol.ErrBadPayload},
		{"auth missing token", `{"v":1,"type":"auth","payload":{}}`, protocol.ErrInvalidField},
		{"list missing req_id", `{"v":1,"type":"list","payload":{}}`, protocol.ErrInvalidField},
		{"session bad state", `{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[{"cwd":"/x","session_count":1,"aggregate_state":"working","sessions":[{"ref":"s1","name":"c","cwd":"/x","state":"flying","rows":24,"cols":80}]}]}}`, protocol.ErrInvalidState},
		{"workspace bad aggregate", `{"v":1,"type":"listing","payload":{"req_id":1,"seq":1,"workspaces":[{"cwd":"/x","session_count":1,"aggregate_state":"zombie"}]}}`, protocol.ErrInvalidState},
		{"subscribe missing ref", `{"v":1,"type":"subscribe","payload":{"rows":24,"cols":80}}`, protocol.ErrInvalidField},
		{"scrollback zero count", `{"v":1,"type":"scrollback","payload":{"req_id":1,"ref":"s1","from_line":0,"count":0}}`, protocol.ErrInvalidField},
		{"input unknown named key", `{"v":1,"type":"input","payload":{"req_id":1,"ref":"s1","keys":["home"]}}`, protocol.ErrInvalidField},
		{"input both text and keys", `{"v":1,"type":"input","payload":{"req_id":1,"ref":"s1","text":"hi","keys":["esc"]}}`, protocol.ErrInvalidField},
	}
	for _, tt := range cases {
		t.Run(tt.name, func(t *testing.T) {
			_, err := protocol.UnmarshalFrame([]byte(tt.msg))
			if err == nil {
				t.Fatalf("UnmarshalFrame(%q) succeeded, want error", tt.msg)
			}
			if !errors.Is(err, tt.want) {
				t.Errorf("UnmarshalFrame(%q) error = %v, want errors.Is(err, %v)", tt.msg, err, tt.want)
			}
		})
	}
}

// TestUnmarshalIgnoresUnknownFields is the forward-compatibility contract:
// an older client must survive a newer server's extra envelope and payload
// fields.
func TestUnmarshalIgnoresUnknownFields(t *testing.T) {
	msg := `{"v":1,"type":"list","future_header":42,"payload":{"req_id":3,"future_payload":true}}`
	got, err := protocol.UnmarshalFrame([]byte(msg))
	if err != nil {
		t.Fatalf("UnmarshalFrame with extra fields failed: %v", err)
	}
	if l, ok := got.(protocol.List); !ok || l.ReqID != 3 {
		t.Fatalf("decoded payload = %#v, want List{ReqID:3}", got)
	}
}

// TestUnmarshalWithNoPayload: a control frame that omits its payload entirely
// must decode to the zero value and then be rejected if the type requires
// fields (auth), or accepted if the type is a valid empty value (empty delta).
func TestUnmarshalWithNoPayload(t *testing.T) {
	if _, err := protocol.UnmarshalFrame([]byte(`{"v":1,"type":"auth"}`)); err == nil {
		t.Error("auth without payload should fail validation (missing token)")
	}
	got, err := protocol.UnmarshalFrame([]byte(`{"v":1,"type":"list_delta","payload":{"seq":1}}`))
	if err != nil {
		t.Fatalf("list_delta without optional sets should decode: %v", err)
	}
	d, ok := got.(protocol.ListDelta)
	if !ok || d.Seq != 1 || len(d.AddedSessions) != 0 {
		t.Fatalf("decoded = %#v, want empty ListDelta{Seq:1}", got)
	}
}

// TestUploadRespIsNotAFrame pins the contract that the HTTP upload response
// is not a WebSocket control frame.
func TestUploadRespIsNotAFrame(t *testing.T) {
	if _, err := protocol.UnmarshalFrame([]byte(`{"v":1,"type":"upload_resp","payload":{"path":"/x"}}`)); !errors.Is(err, protocol.ErrUnknownType) {
		t.Fatalf("upload_resp must be an unknown frame type, got err=%v", err)
	}
}
