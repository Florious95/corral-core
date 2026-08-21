package api

import (
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

// TestCloseSessionKillsPaneAndProcess is A-088-close on an isolated tmux:
// after close_session ack ok, the pane id is gone and pane_pid is dead.
func TestCloseSessionKillsPaneAndProcess(t *testing.T) {
	te := startTmuxEnv(t, "sleep 3600")
	pidOut, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID, "#{pane_pid}")
	if err != nil {
		t.Fatalf("pane_pid: %v\n%s", err, pidOut)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(pidOut))
	if err != nil || pid <= 0 {
		t.Fatalf("pane_pid parse %q: %v", pidOut, err)
	}
	if err := syscall.Kill(pid, 0); err != nil {
		t.Fatalf("fixture pane_pid=%d not alive before close: %v", pid, err)
	}

	te.wsEnv.sendFrame(&protocol.CloseSession{ReqID: 21, Ref: te.ref()})
	got := te.wsEnv.readControlDraining()
	ack, ok := got.(protocol.CloseSessionAck)
	if !ok {
		t.Fatalf("got %T %v, want CloseSessionAck", got, got)
	}
	if ack.ReqID != 21 {
		t.Errorf("req_id=%d want 21", ack.ReqID)
	}
	if !ack.OK {
		t.Fatalf("close_session_ack ok=false reason=%s", ack.Reason)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if !tmuxPaneExists(te.sock, te.paneID) && syscall.Kill(pid, 0) != nil {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if tmuxPaneExists(te.sock, te.paneID) {
		t.Fatalf("pane %s still in list-panes after close", te.paneID)
	}
	if err := syscall.Kill(pid, 0); err == nil {
		t.Fatalf("pane_pid=%d still alive after close", pid)
	}

	// Idempotent: already gone → ok=true.
	te.wsEnv.sendFrame(&protocol.CloseSession{ReqID: 22, Ref: te.ref()})
	again := te.wsEnv.readControlDraining()
	ack2, ok := again.(protocol.CloseSessionAck)
	if !ok {
		t.Fatalf("second close got %T", again)
	}
	if !ack2.OK {
		t.Fatalf("second close not idempotent: reason=%s", ack2.Reason)
	}
}

func TestCloseSessionMissingRefIsOK(t *testing.T) {
	te := startTmuxEnv(t, "sleep 5")
	te.wsEnv.sendFrame(&protocol.CloseSession{ReqID: 7, Ref: "no-such-ref"})
	got := te.wsEnv.readControlDraining()
	ack, ok := got.(protocol.CloseSessionAck)
	if !ok {
		t.Fatalf("got %T, want CloseSessionAck", got)
	}
	if !ack.OK {
		t.Fatalf("missing ref should be idempotent ok, got reason=%s", ack.Reason)
	}
}


