package guirpc

import (
	"context"
	"encoding/json"
	"testing"
)

func TestGrokLiveCommandsUpdateRefreshesOnlyCurrentSession(t *testing.T) {
	var events []map[string]any
	g := newGrokACP(context.Background(), func([]byte) error { return nil }, func(raw []byte) { var e map[string]any; _ = json.Unmarshal(raw, &e); events = append(events, e) }, func() {})
	g.session = "current"
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"current","update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"workflow","description":"Native workflows"},{"name":"goal","description":"Native goals"}]}}}`))
	if len(g.commands) != 2 || len(events) != 1 || events[0]["type"] != "response" || events[0]["command"] != "get_commands" || events[0]["success"] != true {
		t.Fatalf("metadata not published: %v", events)
	}
	data := events[0]["data"].(map[string]any)
	if len(data["commands"].([]any)) != 2 {
		t.Fatal("native metadata lost")
	}
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"retired","update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"other-session"}]}}}`))
	if len(events) != 1 || len(g.commands) != 2 {
		t.Fatal("another session polluted current commands")
	}
}
