package protocol_test

import (
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestCloseSessionRoundTrip is the E12 codec gate: close_session / ack must
// decode. Prior-red (before TypeCloseSession exists) is ErrUnknownType.
func TestCloseSessionRoundTrip(t *testing.T) {
	raw := []byte(`{"v":1,"type":"close_session","payload":{"req_id":1,"ref":"s1"}}`)
	got, err := protocol.UnmarshalFrame(raw)
	if err != nil {
		t.Fatalf("UnmarshalFrame(close_session): %v", err)
	}
	if got.FrameType() != protocol.FrameType("close_session") {
		t.Fatalf("type = %s, want close_session", got.FrameType())
	}
	ackRaw := []byte(`{"v":1,"type":"close_session_ack","payload":{"req_id":1,"ok":true}}`)
	ack, err := protocol.UnmarshalFrame(ackRaw)
	if err != nil {
		t.Fatalf("UnmarshalFrame(close_session_ack): %v", err)
	}
	if ack.FrameType() != protocol.FrameType("close_session_ack") {
		t.Fatalf("type = %s, want close_session_ack", ack.FrameType())
	}
	re, err := protocol.MarshalFrame(got)
	if err != nil {
		t.Fatalf("MarshalFrame(close_session): %v", err)
	}
	again, err := protocol.UnmarshalFrame(re)
	if err != nil {
		t.Fatalf("re-unmarshal: %v", err)
	}
	if again.FrameType() != got.FrameType() {
		t.Fatalf("round-trip type %s vs %s", again.FrameType(), got.FrameType())
	}
}
