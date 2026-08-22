package protocol_test

import (
	"encoding/json"
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
					Cwd: "/proj/a", SessionCount: 2,
					Sessions: []protocol.Session{
						{Ref: "s1", Name: "claude", Cwd: "/proj/a", Rows: 40, Cols: 100},
						{Ref: "s2", Name: "codex", Cwd: "/proj/a", Rows: 24, Cols: 80},
					},
				},
				{Cwd: "/proj/b", SessionCount: 1,
					Sessions: []protocol.Session{
						{Ref: "s3", Name: "claude", Cwd: "/proj/b", Rows: 30, Cols: 90},
					}},
			},
		}},
		{"list_delta added", protocol.ListDelta{
			Seq: 43,
			AddedSessions: []protocol.Session{
				{Ref: "s4", Name: "claude", Cwd: "/proj/c", Rows: 25, Cols: 100},
			},
		}},
		{"list_delta removed", protocol.ListDelta{Seq: 44, RemovedRefs: []string{"s1"}}},
		{"list_delta changed", protocol.ListDelta{Seq: 45,
			ChangedSessions:   []protocol.Session{{Ref: "s2", Name: "codex", Cwd: "/proj/a", Rows: 24, Cols: 80}},
			ChangedWorkspaces: []protocol.Workspace{{Cwd: "/proj/a", SessionCount: 2}},
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
		{"level2_subscribe scoped", protocol.Level2Subscribe{Workspace: "/proj/a"}},
		{"level2_unsubscribe", protocol.Level2Unsubscribe{}},
		{"level2_unsubscribe scoped", protocol.Level2Unsubscribe{Workspace: "/proj/a"}},
		{"level2_frame", protocol.Level2Frame{
			Workspace: "/proj/a",
			Seq:       7,
			Sessions: []protocol.Session{
				{Ref: "s1", Name: "claude", Cwd: "/proj/a", Title: "◐ w-librarian", Status: protocol.SessionStatusWorking, Rows: 24, Cols: 80},
				{Ref: "s2", Name: "codex", Cwd: "/proj/a", Title: "✳ dev-state", Status: protocol.SessionStatusIdle, Rows: 24, Cols: 80},
			},
		}},
		{"level2_heartbeat", protocol.Level2Heartbeat{Workspace: "/proj/a", Seq: 8}},
		{"overlay_subscribe", protocol.OverlaySubscribe{Socket: "/tmp/ov-a/sock"}},
		{"overlay_unsubscribe", protocol.OverlayUnsubscribe{}},
		{"overlay_frame", protocol.OverlayFrame{Seq: 1, Text: "(0) - ovp: 1 windows", Rows: 24, Cols: 80}},
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

// TestScrollWheelRoundTrip verifies scroll_wheel marshal→unmarshal is lossless.
func TestScrollWheelRoundTrip(t *testing.T) {
	cases := []protocol.ScrollWheel{
		{Ref: "s1", Delta: -3}, // scroll up
		{Ref: "s2", Delta: 1},  // scroll down (single notch)
		{Ref: "s3", Delta: -1}, // scroll up single notch
	}
	for _, sw := range cases {
		got := roundTrip(t, sw)
		if !reflect.DeepEqual(got, sw) {
			t.Errorf("ScrollWheel round trip mismatch:\n got %#v\nwant %#v", got, sw)
		}
	}
}

// TestScrollWheelValidate verifies that invalid ScrollWheel frames are rejected.
func TestScrollWheelValidate(t *testing.T) {
	cases := []struct {
		name string
		sw   protocol.ScrollWheel
	}{
		{"empty ref", protocol.ScrollWheel{Delta: -1}},
		{"zero delta", protocol.ScrollWheel{Ref: "s1", Delta: 0}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if err := tc.sw.Validate(); !errors.Is(err, protocol.ErrInvalidField) {
				t.Errorf("Validate() = %v, want ErrInvalidField", err)
			}
		})
	}
}

// TestAttachPreviewRoundTrip verifies requirement 057's new C→S frame
// encodes/decodes byte-for-byte.
func TestAttachPreviewRoundTrip(t *testing.T) {
	cases := []protocol.AttachPreview{
		{Ref: "s1", Path: "/host/img.png"},
		{Ref: "s2", Path: "/host/uploads/photo.jpg"},
	}
	for _, ap := range cases {
		got := roundTrip(t, ap)
		if !reflect.DeepEqual(got, ap) {
			t.Errorf("AttachPreview round trip mismatch:\n got %#v\nwant %#v", got, ap)
		}
	}
}

// TestAttachPreviewValidate verifies that invalid AttachPreview frames are
// rejected: empty ref, empty path.
func TestAttachPreviewValidate(t *testing.T) {
	cases := []struct {
		name string
		ap   protocol.AttachPreview
	}{
		{"empty ref", protocol.AttachPreview{Path: "/host/img.png"}},
		{"empty path", protocol.AttachPreview{Ref: "s1"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if err := tc.ap.Validate(); !errors.Is(err, protocol.ErrInvalidField) {
				t.Errorf("Validate() = %v, want ErrInvalidField", err)
			}
		})
	}
}

// TestInputWithAttachmentPathValidatesLikeText verifies AttachmentPath alone
// (no Text) is legal, and AttachmentPath together with Keys is rejected the
// same way Text+Keys already was (requirement 057 extends the existing
// mutual-exclusivity rule to the new field).
func TestInputWithAttachmentPathValidatesLikeText(t *testing.T) {
	// AttachmentPath alone: legal.
	if err := (protocol.Input{ReqID: 1, Ref: "s1", AttachmentPath: "/host/img.png"}).Validate(); err != nil {
		t.Errorf("Input with only AttachmentPath should validate, got %v", err)
	}
	// AttachmentPath + Keys: rejected, same rule as Text + Keys.
	bad := protocol.Input{ReqID: 1, Ref: "s1", AttachmentPath: "/host/img.png", Keys: []protocol.Key{protocol.KeyEsc}}
	if err := bad.Validate(); !errors.Is(err, protocol.ErrInvalidField) {
		t.Errorf("Input with AttachmentPath+Keys should reject, got %v", err)
	}
}

// TestPaneModeChangedIsServerToClientOnly verifies that a client cannot send
// pane_mode_changed (it is S→C only and must be rejected as an unknown type
// from the C→S decoder path).
func TestPaneModeChangedIsServerToClientOnly(t *testing.T) {
	raw := []byte(`{"v":1,"type":"pane_mode_changed","payload":{"ref":"s1","in_copy_mode":true}}`)
	if _, err := protocol.UnmarshalFrame(raw); !errors.Is(err, protocol.ErrUnknownType) {
		t.Fatalf("pane_mode_changed must be server-to-client only; got err=%v", err)
	}
}

// TestPaneModeChangedMarshal verifies that PaneModeChanged can be marshaled
// (for the S→C direction) and has correct wire shape.
func TestPaneModeChangedMarshal(t *testing.T) {
	frame := protocol.PaneModeChanged{Ref: "s1", InCopyMode: true}
	data, err := protocol.MarshalFrame(frame)
	if err != nil {
		t.Fatalf("MarshalFrame: %v", err)
	}
	var env struct {
		V       int             `json:"v"`
		Type    string          `json:"type"`
		Payload json.RawMessage `json:"payload"`
	}
	if err := json.Unmarshal(data, &env); err != nil {
		t.Fatalf("unmarshal envelope: %v", err)
	}
	if env.Type != "pane_mode_changed" {
		t.Errorf("type = %q, want %q", env.Type, "pane_mode_changed")
	}
	var p protocol.PaneModeChanged
	if err := json.Unmarshal(env.Payload, &p); err != nil {
		t.Fatalf("unmarshal payload: %v", err)
	}
	if p.Ref != "s1" || !p.InCopyMode {
		t.Errorf("payload = %+v", p)
	}
}
