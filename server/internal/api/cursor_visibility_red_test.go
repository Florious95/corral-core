package api

// cursor_visibility_red_test.go pins the real delivery contract for a TUI
// which hides its hardware caret (for example Cursor-like clients): a tmux
// cursor_flag is not present in capture-pane text, so the server must restore
// DECTCEM in the ANSI snapshot sent to the native client.

import (
	"bytes"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
)

func TestSubscribeSnapshotRestoresHiddenHardwareCursor(t *testing.T) {
	te := startTmuxEnv(t, `sh -c "printf '\\033[?25l\\033]0;cursor-hidden-ready\\007'; sleep 300"`)
	time.Sleep(100 * time.Millisecond)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	snapshot := te.readBinaryFrame()
	if snapshot.Kind != protocol.KindSnapshot {
		t.Fatalf("first frame kind = %d, want snapshot", snapshot.Kind)
	}
	if !bytes.Contains(snapshot.Data, []byte("\x1b[?25l")) {
		t.Fatalf("hidden cursor state was dropped from the snapshot ANSI: %q", snapshot.Data)
	}
}

func TestSubscribeSnapshotRestoresVisibleHardwareCursor(t *testing.T) {
	te := startTmuxEnv(t, `sh -c "printf '\\033[?25h\\033]0;cursor-visible-ready\\007'; sleep 300"`)
	time.Sleep(100 * time.Millisecond)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 24, Cols: 80})
	snapshot := te.readBinaryFrame()
	if snapshot.Kind != protocol.KindSnapshot {
		t.Fatalf("first frame kind = %d, want snapshot", snapshot.Kind)
	}
	if !bytes.Contains(snapshot.Data, []byte("\x1b[?25h")) {
		t.Fatalf("visible cursor state was dropped from the snapshot ANSI: %q", snapshot.Data)
	}
}
