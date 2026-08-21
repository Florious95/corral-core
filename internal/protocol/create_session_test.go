package protocol_test

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestCreateSessionRoundTrip: prior-red is ErrUnknownType.
func TestCreateSessionRoundTrip(t *testing.T) {
	raw := []byte(`{"v":1,"type":"create_session","payload":{"req_id":1,"cwd":"/ws","argv":["sleep","30"]}}`)
	got, err := protocol.UnmarshalFrame(raw)
	if err != nil {
		t.Fatalf("UnmarshalFrame(create_session): %v", err)
	}
	if got.FrameType() != protocol.FrameType("create_session") {
		t.Fatalf("type = %s, want create_session", got.FrameType())
	}
	ackRaw := []byte(`{"v":1,"type":"create_session_ack","payload":{"req_id":1,"ok":true,"ref":"s1"}}`)
	ack, err := protocol.UnmarshalFrame(ackRaw)
	if err != nil {
		t.Fatalf("UnmarshalFrame(create_session_ack): %v", err)
	}
	if ack.FrameType() != protocol.FrameType("create_session_ack") {
		t.Fatalf("type = %s", ack.FrameType())
	}
}
