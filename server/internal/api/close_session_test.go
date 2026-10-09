package api

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func TestCloseSessionKillsOnlyTargetPane(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	if out, err := runTmuxCmd(te.env, te.sock, "split-window", "-h", "-t", te.paneID, "cat"); err != nil {
		t.Fatalf("split-window: %v (%s)", err, out)
	}
	before := paneIDs(t, te)
	if len(before) != 2 {
		t.Fatalf("fixture panes=%v, want two panes in one window", before)
	}
	var sibling string
	for _, id := range before {
		if id != te.paneID {
			sibling = id
		}
	}

	te.wsEnv.sendFrame(&protocol.CloseSession{ReqID: 17, Ref: te.ref()})
	deadline := time.Now().Add(5 * time.Second)
	found := false
	for time.Now().Before(deadline) {
		got := readControlWithTimeout(t, te.wsEnv, time.Until(deadline))
		result, ok := got.(protocol.CloseSessionResult)
		if !ok {
			continue // list_delta may be queued by the immediate scan wakeup.
		}
		if !result.OK || result.ReqID != 17 || result.Reason != "" {
			t.Fatalf("close result=%+v, want successful req 17", result)
		}
		found = true
		break
	}
	if !found {
		t.Fatal("close_session_result never arrived")
	}

	after := paneIDs(t, te)
	if len(after) != 1 || after[0] != sibling {
		t.Fatalf("panes after close=%v, want only sibling %s", after, sibling)
	}
	windows, err := runTmuxCmd(te.env, te.sock, "list-windows", "-F", "#{window_id}")
	if err != nil {
		t.Fatalf("list-windows: %v (%s)", err, windows)
	}
	if len(strings.Fields(windows)) != 1 {
		t.Fatalf("windows after closing one pane=%q, want one surviving window", windows)
	}
}

func TestCloseSessionRejectsMalformedRefWithoutTmuxTargetWidening(t *testing.T) {
	te := startTmuxEnv(t, "cat")
	te.wsEnv.sendFrame(&protocol.CloseSession{ReqID: 18, Ref: "not-a-socket:0"})
	got := readControlWithTimeout(t, te.wsEnv, 5*time.Second)
	result, ok := got.(protocol.CloseSessionResult)
	if !ok {
		t.Fatalf("response=%T (%v), want close_session_result", got, got)
	}
	if result.OK || result.ReqID != 18 || result.Reason != "session_not_found" {
		t.Fatalf("close result=%+v, want session_not_found", result)
	}
	if got := paneIDs(t, te); len(got) != 1 {
		t.Fatalf("malformed close changed panes=%v", got)
	}
}

func paneIDs(t *testing.T, te *tmuxEnv) []string {
	t.Helper()
	out, err := runTmuxCmd(te.env, te.sock, "list-panes", "-a", "-F", "#{pane_id}")
	if err != nil {
		t.Fatalf("list-panes: %v (%s)", err, out)
	}
	return strings.Fields(out)
}

func readControlWithTimeout(t *testing.T, e *wsEnv, timeout time.Duration) protocol.Typed {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	typ, data, err := e.conn.Read(ctx)
	if err != nil {
		t.Fatalf("read control: %v", err)
	}
	if typ != websocket.MessageText {
		t.Fatalf("message type=%v, want text", typ)
	}
	got, err := protocol.UnmarshalFrame(data)
	if err != nil {
		t.Fatalf("decode control: %v", err)
	}
	return got
}
