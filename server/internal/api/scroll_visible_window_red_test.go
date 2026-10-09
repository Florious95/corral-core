package api

// scroll_visible_window_red_test.go freezes the real UI failure at the API
// boundary. A mouse=0 pane is put into tmux copy-mode by ScrollWheel; every
// resulting server-visible window must be sent as a snapshot, and returning
// to the bottom must leave copy-mode and restore the normal screen. The
// expected bytes are read independently from tmux's copy-mode coordinates,
// including copy_cursor_x/y metadata.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

type copyWindowMeta struct {
	inMode         bool
	scrollPosition int
	copyCursorX    int
	copyCursorY    int
	paneHeight     int
}

func readCopyWindowMeta(t *testing.T, te *tmuxEnv) copyWindowMeta {
	t.Helper()
	// Select copy-mode coordinates only while in copy-mode. tmux leaves
	// scroll_position/copy_cursor_* empty or stale on some normal-screen paths;
	// ordinary cursor coordinates and offset zero are the independent normal
	// snapshot oracle.
	out, err := runTmuxCmd(te.env, te.sock, "display-message", "-p", "-t", te.paneID,
		"#{pane_in_mode}|#{?pane_in_mode,#{scroll_position},0}|#{?pane_in_mode,#{copy_cursor_x},#{cursor_x}}|#{?pane_in_mode,#{copy_cursor_y},#{cursor_y}}|#{pane_height}")
	if err != nil {
		t.Fatalf("copy-window metadata: %v\n%s", err, out)
	}
	parts := strings.Split(strings.TrimSpace(out), "|")
	if len(parts) != 5 {
		t.Fatalf("copy-window metadata fields=%d, want 5: %q", len(parts), out)
	}
	parse := func(name, value string) int {
		n, err := strconv.Atoi(value)
		if err != nil {
			t.Fatalf("copy-window %s=%q: %v", name, value, err)
		}
		return n
	}
	return copyWindowMeta{
		inMode:         parse("pane_in_mode", parts[0]) == 1,
		scrollPosition: parse("scroll_position", parts[1]),
		copyCursorX:    parse("copy_cursor_x/cursor_x", parts[2]),
		copyCursorY:    parse("copy_cursor_y/cursor_y", parts[3]),
		paneHeight:     parse("pane_height", parts[4]),
	}
}

// readSnapshotAfterScroll drains controls and returns the next snapshot. It
// deliberately does not accept PaneModeChanged as the visual result: a mode
// bit alone cannot update the App's rendered window.
func readSnapshotAfterScroll(t *testing.T, e *wsEnv) protocol.BinaryPayload {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 1*time.Second)
		typ, data, err := e.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("waiting for post-scroll snapshot: %v", err)
		}
		if typ != websocket.MessageBinary {
			// PaneModeChanged and any other control are intentionally drained.
			var env rawEnvelope
			if err := json.Unmarshal(data, &env); err != nil {
				t.Fatalf("decode post-scroll control %q: %v", data, err)
			}
			continue
		}
		got, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode post-scroll binary: %v", err)
		}
		if got.Kind == protocol.KindSnapshot {
			return got
		}
	}
	t.Fatal("post-scroll snapshot did not arrive")
	return protocol.BinaryPayload{}
}

func expectedSnapshot(t *testing.T, te *tmuxEnv, m copyWindowMeta) []byte {
	t.Helper()
	if m.paneHeight <= 0 || m.copyCursorX < 0 || m.copyCursorY < 0 || m.copyCursorY >= m.paneHeight {
		t.Fatalf("invalid cursor/height metadata: %+v", m)
	}
	args := []string{"capture-pane", "-e", "-p", "-t", te.paneID}
	if m.inMode && m.scrollPosition <= 0 {
		t.Fatalf("expected positive scroll_position after scroll, got %+v", m)
	}
	// tmux's capture range is top-relative: start=-offset and end is
	// height-1-offset. Offset zero is the independent normal-screen range.
	args = append(args, "-S", strconv.Itoa(-m.scrollPosition), "-E",
		strconv.Itoa(m.paneHeight-1-m.scrollPosition))
	out, err := runTmuxCmd(te.env, te.sock, args...)
	if err != nil {
		t.Fatalf("snapshot capture (meta=%+v): %v\n%s", m, err, out)
	}
	data := bytes.TrimRight([]byte(out), "\n")
	// SnapshotAfterScroll preserves the existing snapshot cursor convention.
	return append(data, []byte(fmt.Sprintf("\x1b[%d;%dH", m.copyCursorY+1, m.copyCursorX+1))...)
}

type scrollFixture struct {
	command string
	ready   string
	done    string
	stop    string
}

func newScrollFixture(t *testing.T) scrollFixture {
	t.Helper()
	dir := t.TempDir()
	source := filepath.Join(dir, "emit.sh")
	ready := filepath.Join(dir, "ready")
	done := filepath.Join(dir, "done")
	stop := filepath.Join(dir, "stop")
	body := "#!/bin/sh\n"
	body += "while [ ! -e \"$PERF14_SCROLL_READY\" ]; do sleep 0.01; done\n"
	body += "stty -echo 2>/dev/null || true\n"
	body += "i=0\n"
	body += "while [ \"$i\" -lt 160 ]; do printf \"I14ROW%03d\\n\" \"$i\"; i=$((i+1)); done\n"
	body += "touch \"$PERF14_SCROLL_DONE\"\n"
	body += "while [ ! -e \"$PERF14_SCROLL_STOP\" ]; do sleep 0.05; done\n"
	if err := os.WriteFile(source, []byte(body), 0o700); err != nil {
		t.Fatalf("write scroll fixture: %v", err)
	}
	t.Setenv("PERF14_SCROLL_READY", ready)
	t.Setenv("PERF14_SCROLL_DONE", done)
	t.Setenv("PERF14_SCROLL_STOP", stop)
	t.Cleanup(func() { _ = os.WriteFile(stop, []byte("stop\n"), 0o600) })
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
	return scrollFixture{command: "exec " + quote(source), ready: ready, done: done, stop: stop}
}

func startScrollEnv(t *testing.T) *tmuxEnv {
	t.Helper()
	fixture := newScrollFixture(t)
	te := startTmuxEnv(t, fixture.command)
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 10, Cols: 40})
	initial := te.readBinaryFrame()
	if initial.Kind != protocol.KindSnapshot {
		t.Fatalf("initial frame kind=%d, want snapshot", initial.Kind)
	}
	if err := os.WriteFile(fixture.ready, []byte("go\n"), 0o600); err != nil {
		t.Fatalf("start scroll fixture: %v", err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(fixture.done); err == nil {
			waitForStableFixtureRows(t, te)
			return te
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("scroll fixture did not finish emitting rows")
	return nil
}

func waitForStableFixtureRows(t *testing.T, te *tmuxEnv) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	var previous string
	stable := 0
	for time.Now().Before(deadline) {
		out, err := runTmuxCmd(te.env, te.sock, "capture-pane", "-p", "-t", te.paneID)
		if err == nil && strings.Contains(out, "I14ROW159") {
			if out == previous {
				stable++
				if stable >= 2 {
					return
				}
			} else {
				previous = out
				stable = 1
			}
		} else {
			stable = 0
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("scroll fixture rows did not stabilize with terminal marker")
}

// TestScrollWheelPushesExactCopyModeWindow is red on the frozen 9ba baseline:
// handleScrollWheel emits only PaneModeChanged after InjectScroll, while the
// App needs the exact copy-mode window as a fresh snapshot.
func TestScrollWheelPushesExactCopyModeWindow(t *testing.T) {
	te := startScrollEnv(t)

	for _, delta := range []int32{-1, -3} {
		te.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: te.ref(), Delta: delta})
		got := readSnapshotAfterScroll(t, te.wsEnv)
		if got.Ref != te.ref() {
			t.Fatalf("post-scroll ref=%q, want %q", got.Ref, te.ref())
		}
		meta := readCopyWindowMeta(t, te)
		want := expectedSnapshot(t, te, meta)
		if !bytes.Equal(got.Data, want) {
			t.Fatalf("delta=%d snapshot does not equal copy-mode visible window (meta=%+v)\n got=%q\nwant=%q", delta, meta, got.Data, want)
		}
	}
}

// TestScrollWheelDownRestoresNormalScreen is the independent down/bottom
// regression. It requires a returned normal-screen snapshot, not merely a
// PaneModeChanged bit, so the client cannot remain visually stale.
func TestScrollWheelDownRestoresNormalScreen(t *testing.T) {
	te := startScrollEnv(t)
	if _, err := runTmuxCmd(te.env, te.sock, "copy-mode", "-e", "-t", te.paneID); err != nil {
		t.Fatalf("enter copy-mode: %v", err)
	}
	if meta := readCopyWindowMeta(t, te); !meta.inMode {
		t.Fatalf("fixture did not enter copy-mode: %+v", meta)
	}
	if _, err := runTmuxCmd(te.env, te.sock, "send-keys", "-X", "-N", "5", "-t", te.paneID, "scroll-up"); err != nil {
		t.Fatalf("position copy-mode history: %v", err)
	}
	if meta := readCopyWindowMeta(t, te); !meta.inMode || meta.scrollPosition <= 0 {
		t.Fatalf("fixture did not reach non-zero copy-mode history: %+v", meta)
	}

	te.wsEnv.sendFrame(&protocol.ScrollWheel{Ref: te.ref(), Delta: 100})
	got := readSnapshotAfterScroll(t, te.wsEnv)
	meta := readCopyWindowMeta(t, te)
	if meta.inMode {
		t.Fatalf("down-to-bottom left pane in copy-mode: %+v", meta)
	}
	want := expectedSnapshot(t, te, meta)
	if !bytes.Equal(got.Data, want) {
		t.Fatalf("down-to-bottom snapshot does not equal normal screen (meta=%+v)\n got=%q\nwant=%q", meta, got.Data, want)
	}
}

// TestInputExitCopyModePushesNormalScreen requires the input safety bailout to
// publish the restored normal screen, not only PaneModeChanged{false}.
func TestInputExitCopyModePushesNormalScreen(t *testing.T) {
	te := startScrollEnv(t)
	if _, err := runTmuxCmd(te.env, te.sock, "copy-mode", "-e", "-t", te.paneID); err != nil {
		t.Fatalf("enter copy-mode: %v", err)
	}
	if meta := readCopyWindowMeta(t, te); !meta.inMode {
		t.Fatalf("fixture did not enter copy-mode: %+v", meta)
	}
	if _, err := runTmuxCmd(te.env, te.sock, "send-keys", "-X", "-N", "5", "-t", te.paneID, "scroll-up"); err != nil {
		t.Fatalf("position copy-mode history: %v", err)
	}
	if meta := readCopyWindowMeta(t, te); !meta.inMode || meta.scrollPosition <= 0 {
		t.Fatalf("fixture did not reach non-zero copy-mode history: %+v", meta)
	}

	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "x"})
	got := readSnapshotAfterScroll(t, te.wsEnv)
	meta := readCopyWindowMeta(t, te)
	if meta.inMode {
		t.Fatalf("input left pane in copy-mode: %+v", meta)
	}
	want := expectedSnapshot(t, te, meta)
	if !bytes.Equal(got.Data, want) {
		t.Fatalf("input exit snapshot does not equal normal screen (meta=%+v)\n got=%q\nwant=%q", meta, got.Data, want)
	}
}
