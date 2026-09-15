package api

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/discovery"
	"github.com/coder/websocket"
)

type rawControlEnvelope struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

func sendRawControl(t *testing.T, env *wsEnv, raw string) rawControlEnvelope {
	t.Helper()
	if err := env.conn.Write(context.Background(), websocket.MessageText, []byte(raw)); err != nil {
		t.Fatalf("write raw control frame: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	typ, data, err := env.conn.Read(ctx)
	if err != nil {
		t.Fatalf("read raw control response: %v", err)
	}
	if typ != websocket.MessageText {
		t.Fatalf("response message type=%v, want text; data=%q", typ, data)
	}
	var envelope rawControlEnvelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		t.Fatalf("decode response %q: %v", data, err)
	}
	return envelope
}

func authenticateRaw(t *testing.T, env *wsEnv) rawControlEnvelope {
	t.Helper()
	return sendRawControl(t, env, `{"v":1,"type":"auth","payload":{"token":"test-token"}}`)
}

func payloadObject(t *testing.T, payload json.RawMessage) map[string]json.RawMessage {
	t.Helper()
	var object map[string]json.RawMessage
	if err := json.Unmarshal(payload, &object); err != nil {
		t.Fatalf("decode response payload %q: %v", payload, err)
	}
	return object
}

func TestAuthAckAdvertisesAgentLaunchers(t *testing.T) {
	e := startWS(t, Options{
		Token: "test-token", Discoverer: scriptedDiscoverer{model: &discovery.Model{}},
		ListInterval: time.Hour,
	})
	ack := authenticateRaw(t, e)
	if ack.Type != "auth_ack" {
		t.Fatalf("auth response type=%q, want auth_ack; payload=%s", ack.Type, ack.Payload)
	}
	if _, ok := payloadObject(t, ack.Payload)["agent_launchers"]; !ok {
		t.Fatalf("auth_ack omits agent_launchers: payload=%s", ack.Payload)
	}
}

func TestCreateAgentFrameReturnsTypedResult(t *testing.T) {
	e := startWS(t, Options{
		Token: "test-token", Discoverer: scriptedDiscoverer{model: &discovery.Model{}},
		ListInterval: time.Hour,
	})
	ack := authenticateRaw(t, e)
	if ack.Type != "auth_ack" {
		t.Fatalf("auth response type=%q, want auth_ack", ack.Type)
	}
	// The anchor is intentionally absent. A supported server must still return
	// create_agent_result with a controlled target_not_found failure, rather
	// than treating the request as an unknown protocol frame.
	result := sendRawControl(t, e, `{"v":1,"type":"create_agent","payload":{"req_id":101,"workspace":"/repo","anchor_ref":"/tmp/mahjong.sock\u001f%0","provider":"pi","name":"red-test-agent","bypass":false}}`)
	if result.Type != "create_agent_result" {
		t.Fatalf("create_agent response type=%q, want create_agent_result; payload=%s", result.Type, result.Payload)
	}
	payload := payloadObject(t, result.Payload)
	if ok, present := payload["ok"]; !present {
		t.Fatalf("create_agent_result omits ok: payload=%s", result.Payload)
	} else if string(ok) != "false" {
		t.Fatalf("invalid anchor create result ok=%s, want false", ok)
	}
	if reason, present := payload["reason"]; !present {
		t.Fatalf("failed create_agent_result omits reason: payload=%s", result.Payload)
	} else if string(reason) != `"target_not_found"` {
		t.Fatalf("invalid anchor reason=%s, want target_not_found", reason)
	}
}
