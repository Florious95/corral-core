package protocol

import (
	"reflect"
	"strings"
	"testing"
)

func TestCloseSessionFramesRoundTrip(t *testing.T) {
	req := CloseSession{ReqID: 7, Ref: "/tmp/tmux.sock\x1f%10"}
	wire, err := MarshalFrame(req)
	if err != nil {
		t.Fatalf("MarshalFrame request: %v", err)
	}
	if !strings.Contains(string(wire), `"type":"close_session"`) {
		t.Fatalf("wire=%s, missing close_session type", wire)
	}
	decoded, err := UnmarshalFrame(wire)
	if err != nil {
		t.Fatalf("UnmarshalFrame request: %v", err)
	}
	if got, ok := decoded.(CloseSession); !ok || !reflect.DeepEqual(got, req) {
		t.Fatalf("decoded=%#v, want %#v", decoded, req)
	}

	result := CloseSessionResult{ReqID: req.ReqID, OK: true}
	wire, err = MarshalFrame(result)
	if err != nil {
		t.Fatalf("MarshalFrame result: %v", err)
	}
	decoded, err = UnmarshalFrame(wire)
	if err != nil {
		t.Fatalf("UnmarshalFrame result: %v", err)
	}
	if got, ok := decoded.(CloseSessionResult); !ok || got != result {
		t.Fatalf("decoded result=%#v, want %#v", decoded, result)
	}
}

func TestCloseSessionValidation(t *testing.T) {
	for _, frame := range []Typed{
		CloseSession{},
		CloseSession{ReqID: 1},
		CloseSessionResult{},
		CloseSessionResult{ReqID: 1, OK: false},
		CloseSessionResult{ReqID: 1, OK: true, Reason: "close_failed"},
	} {
		if _, err := MarshalFrame(frame); err == nil {
			t.Fatalf("MarshalFrame(%#v) unexpectedly succeeded", frame)
		}
	}
	if err := (CloseSessionResult{ReqID: 1, Reason: "session_not_found"}).Validate(); err != nil {
		t.Fatalf("failure result Validate: %v", err)
	}
}
