package api

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// The source emits only deterministic rows and then stays alive. No prompt or
// capture-derived data is used to construct the expected page.
func strongStaticPane(t *testing.T) *tmuxEnv {
	t.Helper()
	dir := t.TempDir()
	source := filepath.Join(dir, "emit.sh")
	ready := filepath.Join(dir, "ready")
	stop := filepath.Join(dir, "stop")
	body := "#!/bin/sh\n"
	body += "while [ ! -e \"$PERF14_STRONG_READY\" ]; do sleep 0.01; done\n"
	body += "i=1\n"
	body += "while [ $i -le 60 ]; do printf 'A%03d....................................\\n' $i; i=$((i+1)); done\n"
	body += "while [ ! -e \"$PERF14_STRONG_STOP\" ]; do sleep 0.05; done\n"
	if err := os.WriteFile(source, []byte(body), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PERF14_STRONG_READY", ready)
	t.Setenv("PERF14_STRONG_STOP", stop)
	quote := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "'\\''") + "'" }
	te := startTmuxEnv(t, "exec "+quote(source))
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 10, Cols: 40})
	_ = te.readBinaryFrame()
	// Owner signal starts emission only after the subscribed 40x10 geometry is set.
	if err := os.WriteFile(ready, []byte("go\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.WriteFile(stop, []byte("stop\n"), 0o600) })
	return te
}

func strongRunTmux(t *testing.T, te *tmuxEnv, args ...string) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "tmux", append([]string{"-S", te.sock}, args...)...)
	cmd.Env = te.env
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("tmux %q: %v output=%q", args, err, out)
	}
	return strings.TrimSpace(string(out))
}

func strongMetadata(t *testing.T, te *tmuxEnv) (history, height int) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	var got string
	for time.Now().Before(deadline) {
		got = strongRunTmux(t, te, "display-message", "-p", "-t", te.paneID, "#{history_size} #{pane_height}")
		if _, err := fmt.Sscanf(got, "%d %d", &history, &height); err == nil && history == 51 && height == 10 {
			return history, height
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("static fixture metadata=%q, want history=51 height=10", got)
	return 0, 0
}

func strongRows(prefix string, first, last int) []string {
	rows := make([]string, 0, last-first+1)
	for i := first; i <= last; i++ {
		rows = append(rows, fmt.Sprintf("%s%03d....................................", prefix, i))
	}
	return rows
}

func strongReadScrollback(t *testing.T, te *tmuxEnv, reqID uint32, from int32, count uint32) (protocol.BinaryPayload, []byte) {
	t.Helper()
	te.wsEnv.sendFrame(&protocol.Scrollback{ReqID: reqID, Ref: te.ref(), FromLine: from, Count: count})
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		typ, data, err := te.wsEnv.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read req=%d: %v", reqID, err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode req=%d: %v", reqID, err)
		}
		if payload.Kind == protocol.KindScrollback && payload.Ref == te.ref() && payload.ReqID == reqID {
			return payload, data
		}
	}
	t.Fatalf("timed out waiting for scrollback req=%d ref=%q", reqID, te.ref())
	return protocol.BinaryPayload{}, nil
}

func testScrollbackConvergedRangeStrong(t *testing.T) {
	te := strongStaticPane(t)
	history, height := strongMetadata(t, te)
	if history != 51 || height != 10 {
		t.Fatalf("fixture geometry history=%d height=%d, want 51/10", history, height)
	}
	payload, _ := strongReadScrollback(t, te, 0x0e140001, -500, 100)
	if payload.Ref != te.ref() || payload.ReqID != 0x0e140001 {
		t.Fatalf("reply identity ref=%q req=%x, want ref=%q req=%x", payload.Ref, payload.ReqID, te.ref(), 0x0e140001)
	}
	if payload.FromLine != -51 || payload.LineCount != 51 {
		t.Fatalf("converged header from=%d lines=%d, want -51/51", payload.FromLine, payload.LineCount)
	}
	want := strings.Join(strongRows("A", 1, 51), "\n")
	if string(payload.Data) != want {
		t.Fatalf("converged content=%q, want deterministic oldest rows %q", payload.Data, want)
	}
}

func testScrollbackExactHeaderBytesStrong(t *testing.T) {
	te := strongStaticPane(t)
	history, height := strongMetadata(t, te)
	if history != 51 || height != 10 {
		t.Fatalf("fixture geometry history=%d height=%d, want 51/10", history, height)
	}
	reqID := uint32(0x0e140002)
	payload, frame := strongReadScrollback(t, te, reqID, -5, 3)
	if payload.Ref != te.ref() || payload.ReqID != reqID {
		t.Fatalf("reply identity ref=%q req=%x, want ref=%q req=%x", payload.Ref, payload.ReqID, te.ref(), reqID)
	}
	want := strings.Join(strongRows("A", 47, 49), "\n")
	if payload.FromLine != -5 || payload.LineCount != 3 || string(payload.Data) != want {
		t.Fatalf("decoded page from=%d lines=%d data=%q, want -5/3/%q", payload.FromLine, payload.LineCount, payload.Data, want)
	}
	if len(frame) < 5+len(payload.Ref)+12 {
		t.Fatalf("frame too short: %d", len(frame))
	}
	if !bytes.Equal(frame[:4], []byte{'R', 'A', 1, 3}) || int(frame[4]) != len(payload.Ref) {
		t.Fatalf("raw outer header=% x, want magic/version/kind/ref length", frame[:5])
	}
	off := 5 + len(payload.Ref)
	if got := binary.BigEndian.Uint32(frame[off : off+4]); got != reqID {
		t.Fatalf("raw req_id=%x, want %x", got, reqID)
	}
	if got := int32(binary.BigEndian.Uint32(frame[off+4 : off+8])); got != -5 {
		t.Fatalf("raw from_line=%d, want -5", got)
	}
	if got := binary.BigEndian.Uint32(frame[off+8 : off+12]); got != 3 {
		t.Fatalf("raw line_count=%d, want 3", got)
	}
	if !bytes.Equal(frame[off+12:], payload.Data) {
		t.Fatalf("raw body differs from decoded body: %q vs %q", frame[off+12:], payload.Data)
	}
}
