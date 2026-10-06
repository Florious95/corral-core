package guirpc

import (
	"context"
	"encoding/json"
	"testing"
)

func TestGrokNativeTitlePushIsSessionBoundAndCoalescesTwoNotifications(t *testing.T) {
	var events []map[string]any
	g := newGrokACP(context.Background(), func([]byte) error { return nil }, func(raw []byte) { var e map[string]any; _ = json.Unmarshal(raw, &e); events = append(events, e) }, func() {})
	g.session = "sid"
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"_x.ai/session_notification","params":{"sessionId":"sid","update":{"sessionUpdate":"session_summary_generated","session_summary":"NATIVE TITLE"},"_meta":{"x.ai/titleIsManual":true}}}`))
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sid","update":{"sessionUpdate":"session_info_update","title":"NATIVE TITLE"},"_meta":{"x.ai/titleIsManual":true}}}`))
	if g.sessionName != "NATIVE TITLE" || len(events) != 1 {
		t.Fatalf("title=%q events=%v", g.sessionName, events)
	}
	if events[0]["type"] != "session_info_changed" || events[0]["sessionId"] != "sid" || events[0]["titleIsManual"] != true {
		t.Fatalf("native metadata missing: %v", events)
	}
	g.ingest([]byte(`{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"retired-sid","update":{"sessionUpdate":"session_info_update","title":"POLLUTING TITLE"},"_meta":{"x.ai/titleIsManual":true}}}`))
	if g.sessionName != "NATIVE TITLE" || len(events) != 1 {
		t.Fatal("old SID title polluted loaded session")
	}
	g.mu.Lock()
	state := g.stateLocked()
	g.mu.Unlock()
	if state["sessionId"] != "sid" || state["sessionName"] != "NATIVE TITLE" {
		t.Fatalf("get_state=%v", state)
	}
}
