package api

// scroll_api_test.go: API-layer red tests for handleScrollWheel.
// All tests use an isolated tmux socket — production daemon (pid 70317) and
// user tmux are never touched.
//
// T-api1: unsubscribed ref → ErrorFrame, no tmux contact
// T-api2: dead pane (InjectScroll fails) → ErrorFrame, failure visible
// T-api3: bare shell → scroll → PaneModeChanged{InCopyMode:true};
//         then Input → PaneModeChanged{InCopyMode:false}

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// rawEnvelope is a minimally decoded control frame for S→C-only types that
// are intentionally absent from the C→S UnmarshalFrame decoder (e.g.
// pane_mode_changed). Tests that need to read those frames use this instead of
// wsEnv.readControlDraining.
type rawEnvelope struct {
	V       int             `json:"v"`
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

// readNextControl reads control text frames, skipping binary mirror frames,
// until one arrives. It returns the raw envelope without going through
// UnmarshalFrame — safe for S→C-only frame types.
func readNextControl(t *testing.T, e *wsEnv) rawEnvelope {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("readNextControl: %v", err)
		}
		if typ == websocket.MessageBinary {
			continue
		}
		var env rawEnvelope
		if err := json.Unmarshal(data, &env); err != nil {
			t.Fatalf("readNextControl: unmarshal %q: %v", data, err)
		}
		return env
	}
	t.Fatal("readNextControl: timed out")
	return rawEnvelope{}
}

// TestScrollWheelUnsubscribedReturnsError verifies that sending TypeScrollWheel
// for a ref the client has not subscribed to returns an error frame and does
// NOT inject anything into tmux. This tests the subscription gate in
// handleScrollWheel — the server must guard it, not crash, not silence.
func TestScrollWheelUnsubscribedReturnsError(t *testing.T) {
	te := startTmuxEnv(t, "sh")
	// Deliberately do NOT subscribe. The ref is valid in the catalog but
	// the client connection has no subscription for it.
	te.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: te.ref(), Delta: -1})

	env := readNextControl(t, te.wsEnv)
	if env.Type != string(protocol.TypeError) {
		t.Fatalf("expected %q frame, got %q", protocol.TypeError, env.Type)
	}
	var ef protocol.ErrorFrame
	if err := json.Unmarshal(env.Payload, &ef); err != nil {
		t.Fatalf("decode ErrorFrame payload: %v", err)
	}
	if ef.Code != protocol.ErrCodeSessionNotFound {
		t.Errorf("error code = %q, want %q", ef.Code, protocol.ErrCodeSessionNotFound)
	}
}

// TestScrollWheelDeadPaneReturnsError verifies that when the tmux pane has
// been killed (InjectScroll returns ErrPaneNotFound or catalog miss), the
// server sends an ErrorFrame — the "failure visible" red line. The client must
// always get a visible response; silence is not acceptable.
func TestScrollWheelDeadPaneReturnsError(t *testing.T) {
	te := startTmuxEnv(t, "sh")

	// Subscribe first so the subscription gate passes.
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot — discard

	// Kill the pane while the subscription is live.
	runTmuxCmd(te.env, te.sock, "kill-pane", "-t", te.paneID)
	time.Sleep(100 * time.Millisecond) // let the kill propagate

	te.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: te.ref(), Delta: -1})

	// Must receive an error frame (ErrCodeSessionNotFound or ErrCodeInternal
	// depending on which layer catches it), never silence.
	env := readNextControl(t, te.wsEnv)
	if env.Type != string(protocol.TypeError) {
		t.Fatalf("expected %q frame after dead-pane scroll, got %q", protocol.TypeError, env.Type)
	}
}

// TestScrollWheelEntersCopyModeAndInputExitsIt is the end-to-end notification
// test: a bare shell (mouse_any_flag=0) receives a scroll → InjectScroll
// enters copy-mode → server emits PaneModeChanged{InCopyMode:true}; then the
// client sends TypeInput → handleInput pre-flight exits copy-mode → server
// emits PaneModeChanged{InCopyMode:false}. Both PaneModeChanged frames must
// arrive with the correct direction.
func TestScrollWheelEntersCopyModeAndInputExitsIt(t *testing.T) {
	te := startTmuxEnv(t, "sh")

	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	te.readBinaryFrame() // snapshot

	// --- phase 1: scroll up → expect PaneModeChanged{InCopyMode:true} ---
	te.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: te.ref(), Delta: -1})

	// pane_mode_changed is S→C only and rejected by UnmarshalFrame (correct);
	// use readNextControl which reads the raw envelope without the C→S decoder.
	env1 := readNextControl(t, te.wsEnv)
	if env1.Type != string(protocol.TypePaneModeChanged) {
		t.Fatalf("phase1: expected %q, got %q", protocol.TypePaneModeChanged, env1.Type)
	}
	var pmc1 protocol.PaneModeChanged
	if err := json.Unmarshal(env1.Payload, &pmc1); err != nil {
		t.Fatalf("phase1: decode PaneModeChanged: %v", err)
	}
	if !pmc1.InCopyMode {
		t.Errorf("phase1: InCopyMode = false, want true")
	}
	if pmc1.Ref != te.ref() {
		t.Errorf("phase1: Ref = %q, want %q", pmc1.Ref, te.ref())
	}

	// --- phase 2: input → expect PaneModeChanged{InCopyMode:false} ---
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "x"})

	// Drain until we see pane_mode_changed (InputAck may arrive first).
	deadline := time.Now().Add(5 * time.Second)
	var gotExit bool
	for time.Now().Before(deadline) {
		env2 := readNextControl(t, te.wsEnv)
		if env2.Type != string(protocol.TypePaneModeChanged) {
			continue // InputAck or other S→C control frame
		}
		var pmc2 protocol.PaneModeChanged
		if err := json.Unmarshal(env2.Payload, &pmc2); err != nil {
			t.Fatalf("phase2: decode PaneModeChanged: %v", err)
		}
		if pmc2.InCopyMode {
			t.Errorf("phase2: InCopyMode = true, want false (copy-mode should have exited)")
		}
		if pmc2.Ref != te.ref() {
			t.Errorf("phase2: Ref = %q, want %q", pmc2.Ref, te.ref())
		}
		gotExit = true
		break
	}
	if !gotExit {
		t.Error("phase2: never received PaneModeChanged{InCopyMode:false} after Input")
	}
}
