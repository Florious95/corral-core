package protocol

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestCreateAgentFramesRoundTrip(t *testing.T) {
	req := CreateAgent{ReqID: 101, Workspace: "/repo", AnchorRef: "/tmp/tmux.sock\x1f%0", Provider: "pi", Name: "修复测试员", Bypass: true}
	wire, err := MarshalFrame(req)
	if err != nil {
		t.Fatalf("MarshalFrame: %v", err)
	}
	if !strings.Contains(string(wire), `"type":"create_agent"`) {
		t.Fatalf("wire=%s, missing create_agent type", wire)
	}
	decoded, err := UnmarshalFrame(wire)
	if err != nil {
		t.Fatalf("UnmarshalFrame: %v", err)
	}
	got, ok := decoded.(CreateAgent)
	if !ok || !reflect.DeepEqual(got, req) {
		t.Fatalf("decoded=%#v, want %#v", decoded, req)
	}

	result := CreateAgentResult{ReqID: 101, OK: false, Reason: string(CreateAgentTargetNotFound)}
	wire, err = MarshalFrame(result)
	if err != nil {
		t.Fatalf("MarshalFrame result: %v", err)
	}
	decoded, err = UnmarshalFrame(wire)
	if err != nil {
		t.Fatalf("UnmarshalFrame result: %v", err)
	}
	if got, ok := decoded.(CreateAgentResult); !ok || got != result {
		t.Fatalf("decoded result=%#v, want %#v", decoded, result)
	}
}

func TestAuthAckAgentLaunchersWire(t *testing.T) {
	ack := AuthAck{OK: true, AgentLaunchers: []AgentLauncher{{Provider: "pi", DisplayName: "Pi Coding Agent", SupportsBypass: true, Naming: "cli"}}}
	wire, err := MarshalFrame(ack)
	if err != nil {
		t.Fatalf("MarshalFrame: %v", err)
	}
	var env struct {
		Payload map[string]json.RawMessage `json:"payload"`
	}
	if err := json.Unmarshal(wire, &env); err != nil {
		t.Fatalf("decode wire: %v", err)
	}
	if _, ok := env.Payload["agent_launchers"]; !ok {
		t.Fatalf("wire=%s, missing agent_launchers", wire)
	}
}

func TestCreateAgentResultRejectsUncontrolledReason(t *testing.T) {
	_, err := MarshalFrame(CreateAgentResult{ReqID: 1, Reason: "command output"})
	if err == nil || !strings.Contains(err.Error(), "unknown create_agent reason") {
		t.Fatalf("err=%v, want controlled reason failure", err)
	}
}
