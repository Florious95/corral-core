package guirpc

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"
)

func acpTestBridge(t *testing.T) (*grokACP, func() []map[string]json.RawMessage, func() []map[string]json.RawMessage) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	var mu sync.Mutex
	var wires, events []map[string]json.RawMessage
	decode := func(raw []byte) map[string]json.RawMessage {
		var r map[string]json.RawMessage
		if json.Unmarshal(raw, &r) != nil {
			t.Fatal("invalid adapter JSON")
		}
		return r
	}
	g := newGrokACP(ctx, func(raw []byte) error { mu.Lock(); defer mu.Unlock(); wires = append(wires, decode(raw)); return nil }, func(raw []byte) { mu.Lock(); defer mu.Unlock(); events = append(events, decode(raw)) }, func() { t.Error("unexpected adapter loss") })
	g.session = "native-session"
	t.Cleanup(func() { cancel(); g.close() })
	wire := func() []map[string]json.RawMessage {
		mu.Lock()
		defer mu.Unlock()
		return append([]map[string]json.RawMessage(nil), wires...)
	}
	event := func() []map[string]json.RawMessage {
		mu.Lock()
		defer mu.Unlock()
		return append([]map[string]json.RawMessage(nil), events...)
	}
	return g, wire, event
}

func acpUpdate(g *grokACP, update map[string]any) {
	raw, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "method": "session/update", "params": map[string]any{"sessionId": "native-session", "update": update}})
	g.ingest(raw)
}

func TestGrokACPProjectsRealProtocolShapesAndKeepsRequestIDs(t *testing.T) {
	g, wire, events := acpTestBridge(t)
	if _, err := g.Write([]byte(`{"type":"prompt","id":"client-1","message":"hello"}`)); err != nil {
		t.Fatal(err)
	}
	request := wire()[0]
	var method string
	_ = json.Unmarshal(request["method"], &method)
	if method != "session/prompt" || strings.Contains(string(request["params"]), "client-1") {
		t.Fatal("conversation command was not translated")
	}
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"_x.ai/queue/changed","params":{"sessionId":"native-session","entries":[],"runningPromptId":"native-prompt"}}`))
	acpUpdate(g, map[string]any{"sessionUpdate": "agent_thought_chunk", "content": map[string]any{"type": "text", "text": "think"}})
	acpUpdate(g, map[string]any{"sessionUpdate": "agent_thought_chunk", "content": map[string]any{"type": "text", "text": "ing"}})
	acpUpdate(g, map[string]any{"sessionUpdate": "tool_call", "toolCallId": "tool-1", "title": "read_file"})
	acpUpdate(g, map[string]any{"sessionUpdate": "tool_call_update", "toolCallId": "tool-1", "rawInput": map[string]any{"path": "proof.txt"}, "status": "completed", "content": []any{map[string]any{"type": "content", "content": map[string]any{"type": "text", "text": "proof"}}}})
	acpUpdate(g, map[string]any{"sessionUpdate": "agent_message_chunk", "content": map[string]any{"type": "text", "text": "answer"}})
	g.ingest(append(append([]byte(`{"jsonrpc":"2.0","id":`), request["id"]...), []byte(`,"result":{"stopReason":"end_turn"}}`)...))
	var admitted, toolEnd, final, settled bool
	for _, e := range events() {
		var kind string
		_ = json.Unmarshal(e["type"], &kind)
		switch kind {
		case "response":
			var id string
			var ok bool
			_ = json.Unmarshal(e["id"], &id)
			_ = json.Unmarshal(e["success"], &ok)
			admitted = id == "client-1" && ok
		case "tool_execution_end":
			toolEnd = strings.Contains(string(e["result"]), "proof")
		case "message_end":
			if strings.Contains(string(e["message"]), "assistant") {
				final = strings.Contains(string(e["message"]), "thinking") && strings.Contains(string(e["message"]), "answer") && strings.Contains(string(e["message"]), "proof.txt")
			}
		case "agent_settled":
			settled = true
		}
	}
	if !admitted || !toolEnd || !final || !settled {
		t.Fatalf("projection admitted=%v tool=%v final=%v settled=%v", admitted, toolEnd, final, settled)
	}
}

func TestGrokACPConfigurationRequiresNativeConfirmation(t *testing.T) {
	for _, confirm := range []bool{false, true} {
		t.Run(map[bool]string{false: "incomplete-reply", true: "confirmed"}[confirm], func(t *testing.T) {
			g, wire, events := acpTestBridge(t)
			g.metadata(json.RawMessage(`{"models":{"currentModelId":"model-a","availableModels":[{"modelId":"model-a","name":"A"},{"modelId":"model-b","name":"B"}]},"configOptions":[{"id":"model","category":"model","currentValue":"model-a","options":[{"value":"model-a"},{"value":"model-b"}]}]}`))
			if _, err := g.Write([]byte(`{"type":"set_model","id":"model-change","modelId":"model-b"}`)); err != nil {
				t.Fatal(err)
			}
			r := wire()[0]
			result := `{}`
			if confirm {
				result = `{"configOptions":[{"id":"model","currentValue":"model-b"}]}`
			}
			g.ingest(append(append(append([]byte(`{"jsonrpc":"2.0","id":`), r["id"]...), []byte(`,"result":`)...), []byte(result+`}`)...))
			last := events()[len(events())-1]
			var ok bool
			_ = json.Unmarshal(last["success"], &ok)
			if ok != confirm || (g.models.Current == "model-b") != confirm {
				t.Fatal("selection moved without authoritative confirmation")
			}
		})
	}
}

func TestGrokACPRejectsUnknownSessionAndCancelsPermissions(t *testing.T) {
	g, wire, events := acpTestBridge(t)
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"other-session","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"wrong"}}}}`))
	if len(events()) != 0 {
		t.Fatal("another session contaminated this conversation")
	}
	g.ingest([]byte(`{"jsonrpc":"2.0","id":31,"method":"session/request_permission","params":{"sessionId":"native-session","options":[{"optionId":"allow","kind":"allow_once"}]}}`))
	if len(wire()) != 1 || string(wire()[0]["id"]) != "31" || !strings.Contains(string(wire()[0]["result"]), "cancelled") {
		t.Fatal("permission was not explicitly cancelled with native ID preserved")
	}
	if _, err := g.Write([]byte(`{"type":"not-a-grok-command","id":"unknown"}`)); err == nil {
		t.Fatal("unsupported command silently accepted")
	}
	if len(wire()) != 1 {
		t.Fatal("a Pi/unknown command entered native ACP stdin")
	}
}

func TestGrokACPBoundsRequestsAndRequiresJSONRPCEnvelope(t *testing.T) {
	g, wire, _ := acpTestBridge(t)
	called := 0
	for i := 0; i < 64; i++ {
		if err := g.call("probe", map[string]any{}, time.Minute, func(json.RawMessage, error) { called++ }); err != nil {
			t.Fatal(err)
		}
	}
	if err := g.call("overflow", map[string]any{}, time.Minute, func(json.RawMessage, error) {}); err == nil {
		t.Fatal("pending requests grew without bound")
	}
	r := wire()[0]
	g.ingest(append(append([]byte(`{"id":`), r["id"]...), []byte(`,"result":{}}`)...))
	if called != 0 || len(g.pending) != 64 {
		t.Fatal("non-ACP JSON resolved a native request")
	}
	g.ingest(append(append([]byte(`{"jsonrpc":"2.0","id":`), r["id"]...), []byte(`,"result":{}}`)...))
	if called != 1 || len(g.pending) != 63 {
		t.Fatal("native response did not resolve its exact request")
	}
}

func TestGrokACPStateIdentitySurvivesWorkerProjection(t *testing.T) {
	g, _, _ := acpTestBridge(t)
	w := newWorker(&syncBuffer{})
	defer w.shutdown()
	_, _, client := w.attach(Hello{Type: "hello"}, true)
	g.emit = w.ingest // exercise the real projection, not just the adapter output
	if _, err := g.Write([]byte(`{"type":"get_state","id":"projected-state"}`)); err != nil {
		t.Fatal(err)
	}
	select {
	case raw := <-client.ch:
		r := decode(t, [][]byte{raw})[0]
		var response struct {
			Data struct {
				AgentProvider string `json:"agentProvider"`
			} `json:"data"`
		}
		if json.Unmarshal(r.Event, &response) != nil || response.Data.AgentProvider != "grok" {
			t.Fatalf("native CLI identity lost on client stream: %s", r.Event)
		}
	case <-time.After(time.Second):
		t.Fatal("client state never published")
	}
	w.ingest([]byte(`{"type":"response","command":"get_state","success":true,"data":{"model":{"id":"native-model","provider":"grok","apiKey":"must-stay-private"}}}`))
	select {
	case raw := <-client.ch:
		if strings.Contains(string(raw), "agentProvider") || strings.Contains(string(raw), "must-stay-private") {
			t.Fatal("legacy state invented a CLI identity or leaked model metadata")
		}
	case <-time.After(time.Second):
		t.Fatal("legacy client state never published")
	}
}

func TestGrokACPNewSessionPersistsIdentityBeforeSuccess(t *testing.T) {
	g, wire, events := acpTestBridge(t)
	g.cwd = "/owned-test-cwd"
	remembered := ""
	g.remember = func(id string) error {
		if g.session != "native-session" {
			t.Fatal("identity moved before persistence")
		}
		remembered = id
		return nil
	}
	if _, err := g.Write([]byte(`{"type":"new_session","id":"new-session"}`)); err != nil {
		t.Fatal(err)
	}
	if _, err := g.Write([]byte(`{"type":"prompt","id":"race","message":"blocked"}`)); err == nil {
		t.Fatal("input raced session creation")
	}
	r := wire()[0]
	if string(r["method"]) != `"session/new"` {
		t.Fatal("not a native session creation")
	}
	g.ingest(append(append([]byte(`{"jsonrpc":"2.0","id":`), r["id"]...), []byte(`,"result":{"sessionId":"new-native-session"}}`)...))
	if remembered != "new-native-session" || g.session != remembered || g.sessionChanging {
		t.Fatal("native identity not durably confirmed")
	}
	last := events()[len(events())-1]
	if string(last["id"]) != `"new-session"` || string(last["success"]) != "true" {
		t.Fatal("creation did not confirm its client ID")
	}
}

func TestGrokACPNewSessionWithoutNativeIdentityFailsClosed(t *testing.T) {
	g, wire, events := acpTestBridge(t)
	g.cwd = "/owned-test-cwd"
	g.remember = func(string) error { t.Fatal("missing native identity persisted"); return nil }
	lost := false
	g.lost = func() { lost = true }
	if _, err := g.Write([]byte(`{"type":"new_session","id":"unknown-new"}`)); err != nil {
		t.Fatal(err)
	}
	r := wire()[0]
	g.ingest(append(append([]byte(`{"jsonrpc":"2.0","id":`), r["id"]...), []byte(`,"result":{}}`)...))
	if !lost || g.session != "native-session" || string(events()[len(events())-1]["success"]) != "false" {
		t.Fatal("unknown native context accepted")
	}
}

func TestGrokACPCompactUsesNativePromptAndClientCommand(t *testing.T) {
	g, wire, events := acpTestBridge(t)
	if _, err := g.Write([]byte(`{"type":"compact","id":"compact-client"}`)); err != nil {
		t.Fatal(err)
	}
	r := wire()[0]
	if string(r["method"]) != `"session/prompt"` || !strings.Contains(string(r["params"]), "/compact") {
		t.Fatal("compact did not reach native command handler")
	}
	g.ingest(append(append([]byte(`{"jsonrpc":"2.0","id":`), r["id"]...), []byte(`,"result":{"stopReason":"end_turn"}}`)...))
	for _, event := range events() {
		if string(event["type"]) == `"response"` {
			if string(event["id"]) != `"compact-client"` || string(event["command"]) != `"compact"` || string(event["success"]) != "true" {
				t.Fatal("compact response miscorrelated")
			}
			return
		}
	}
	t.Fatal("compact never confirmed")
}

func TestGrokACPPromptTimeoutDoesNotClaimUnknownWorkSettled(t *testing.T) {
	g, _, events := acpTestBridge(t)
	lost := make(chan struct{})
	g.lost = func() { close(lost) }
	if _, err := g.Write([]byte(`{"type":"prompt","id":"timeout","message":"work"}`)); err != nil {
		t.Fatal(err)
	}
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"_x.ai/queue/changed","params":{"sessionId":"native-session","entries":[],"runningPromptId":"native-work"}}`))
	g.mu.Lock()
	for _, p := range g.pending {
		p.timer.Reset(10 * time.Millisecond)
	}
	g.mu.Unlock()
	select {
	case <-lost:
	case <-time.After(time.Second):
		t.Fatal("deadline did not close unknown bridge")
	}
	g.mu.Lock()
	if !g.running || g.promptID != "timeout" {
		t.Error("timeout manufactured idle")
	}
	g.mu.Unlock()
	var visibleFailure bool
	for _, event := range events() {
		if string(event["type"]) == `"agent_settled"` {
			t.Fatal("timeout manufactured settled")
		}
		if string(event["type"]) == `"response"` && string(event["success"]) == "false" {
			visibleFailure = true
		}
	}
	if !visibleFailure {
		t.Fatal("timeout was invisible")
	}
}

func TestGrokACPRehydrateObservesActualNativeBusyMetadata(t *testing.T) {
	g, _, _ := acpTestBridge(t)
	g.metadata(json.RawMessage(`{"_meta":{"sessionId":"native-session","x.ai/runningPromptId":"actual-native-work"}}`))
	if !g.running {
		t.Fatal("live resumed prompt was treated as idle")
	}
	if _, err := g.Write([]byte(`{"type":"prompt","id":"new","message":"must-not-run"}`)); err == nil {
		t.Fatal("new prompt raced resumed native work")
	}
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"_x.ai/queue/changed","params":{"sessionId":"native-session","entries":[]}}`))
	if g.running {
		t.Fatal("native idle transition was ignored")
	}
}
