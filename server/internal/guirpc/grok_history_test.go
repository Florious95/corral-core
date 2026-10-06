package guirpc

import "testing"

// The final load replay and ordinary get_state share the same native lock and
// state projection, so a busy snapshot cannot follow a captured settled event.
func TestGrokStateProjectionPreservesNativePhaseAndIdentity(t *testing.T) {
	g := &grokACP{session: "native-session", running: true, queued: 2, promptCommand: "compact"}
	g.mu.Lock()
	defer g.mu.Unlock()
	state := g.stateLocked()
	if state["sessionId"] != "native-session" || state["agentProvider"] != "grok" || state["pendingMessageCount"] != 2 {
		t.Fatalf("native identity/queue missing: %v", state)
	}
	if state["isStreaming"] != true || state["isCompacting"] != true {
		t.Fatalf("active native compaction hidden: %v", state)
	}
	g.running, g.queued, g.promptCommand = false, 0, ""
	settled := g.stateLocked()
	if settled["isStreaming"] != false || settled["isCompacting"] != false || settled["pendingMessageCount"] != 0 {
		t.Fatalf("settled state reused stale work: %v", settled)
	}
}
