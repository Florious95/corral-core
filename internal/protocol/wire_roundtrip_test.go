package protocol_test

import (
	"errors"
	"strings"
	"testing"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestWireRoundTripOverlaySubscribeValidateNamesSocket(t *testing.T) {
	err := protocol.OverlaySubscribe{Socket: ""}.Validate()
	if !errors.Is(err, protocol.ErrInvalidField) {
		t.Fatalf("empty socket Validate = %v, want ErrInvalidField", err)
	}
	if !strings.Contains(err.Error(), "socket") {
		t.Fatalf("Validate must name field socket, got %q", err)
	}
	if err := (protocol.OverlaySubscribe{Socket: "/tmp/tmux-1000/default"}).Validate(); err != nil {
		t.Fatalf("path socket Validate = %v", err)
	}
}

func TestWireRoundTripErrorCodeInvalidFieldAccepted(t *testing.T) {
	ok := protocol.ErrorFrame{Code: protocol.ErrCodeInvalidField, Reason: "socket must be non-empty"}
	if err := ok.Validate(); err != nil {
		t.Fatalf("invalid_field ErrorFrame.Validate = %v", err)
	}
}
