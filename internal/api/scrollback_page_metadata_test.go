package api

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

// TestScrollbackPageUsesBoundedMetadataAndOneCapture is the production-handler
// red test for PERF14. It records tmux arguments and captured bytes while a real
// isolated pane has more history than the requested page. The catalog height
// remains 24 while the subscribed pane is resized to 10 rows, so the oracle
// must use one actual metadata result (history_size + pane_height), then one
// bounded target capture. A full-history MinInt32 capture is the old failure.
func TestScrollbackPageUsesBoundedMetadataAndOneCapture(t *testing.T) {
	realTMUX, err := exec.LookPath("tmux")
	if err != nil {
		t.Fatalf("tmux unavailable: %v", err)
	}
	trace := filepath.Join(t.TempDir(), "tmux-trace.log")
	captureTmp := filepath.Join(t.TempDir(), "capture.out")
	wrapperDir := t.TempDir()
	wrapper := filepath.Join(wrapperDir, "tmux")
	const wrapperScript = `#!/bin/sh
set -eu
trace=${PERF14_TMUX_TRACE:?}
real=${PERF14_REAL_TMUX:?}
capture_tmp=${PERF14_CAPTURE_TMP:?}
if [ "${3-}" = "capture-pane" ]; then
  "$real" "$@" >"$capture_tmp"
  rc=$?
  bytes=$(wc -c <"$capture_tmp" | tr -d ' ')
  printf 'capture-bytes=%s args=%s\n' "$bytes" "$*" >>"$trace"
  cat "$capture_tmp"
  rm -f "$capture_tmp"
  exit "$rc"
fi
if [ "${3-}" = "display-message" ]; then
  out=$("$real" "$@")
  rc=$?
  printf 'metadata-result=%s args=%s\n' "$out" "$*" >>"$trace"
  printf '%s\n' "$out"
  exit "$rc"
fi
printf 'args=%s\n' "$*" >>"$trace"
exec "$real" "$@"
`
	if err := os.WriteFile(wrapper, []byte(wrapperScript), 0o700); err != nil {
		t.Fatalf("write tmux wrapper: %v", err)
	}
	oldPath := os.Getenv("PATH")
	t.Setenv("PATH", wrapperDir+string(os.PathListSeparator)+oldPath)
	t.Setenv("PERF14_REAL_TMUX", realTMUX)
	t.Setenv("PERF14_TMUX_TRACE", trace)
	t.Setenv("PERF14_CAPTURE_TMP", captureTmp)

	te := startTmuxEnv(t, "bash")
	te.wsEnv.sendFrame(&protocol.Subscribe{Ref: te.ref(), Rows: 10, Cols: 40})
	_ = te.readBinaryFrame()
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 1, Ref: te.ref(), Text: "for i in $(seq 1 40); do echo PERF14_$i; done"})
	te.wsEnv.sendFrame(&protocol.Input{ReqID: 2, Ref: te.ref(), Text: ""})
	te.waitForMirror("PERF14_40")
	if err := os.WriteFile(trace, nil, 0o600); err != nil {
		t.Fatalf("clear tmux trace: %v", err)
	}

	te.wsEnv.sendFrame(&protocol.Scrollback{ReqID: 9, Ref: te.ref(), FromLine: -20, Count: 10})
	payload := readScrollbackPayload(t, te.wsEnv, 9)
	if payload.LineCount == 0 || payload.LineCount > 10 {
		t.Fatalf("scrollback line_count=%d, want 1..10", payload.LineCount)
	}
	if len(payload.Data) == 0 {
		t.Fatal("scrollback page data is empty")
	}

	data, err := os.ReadFile(trace)
	if err != nil {
		t.Fatalf("read tmux trace: %v", err)
	}
	var captures, metadata []string
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		switch {
		case strings.HasPrefix(line, "capture-bytes="):
			captures = append(captures, line)
		case strings.HasPrefix(line, "metadata-result="):
			metadata = append(metadata, line)
		}
	}
	if len(metadata) != 1 {
		t.Fatalf("metadata queries=%d, want exactly one; trace=%q", len(metadata), data)
	}
	if !strings.Contains(metadata[0], "#{history_size} #{pane_height}") {
		t.Fatalf("metadata query missing history_size/pane_height: %q", metadata[0])
	}
	if len(captures) != 1 {
		t.Fatalf("capture calls=%d, want exactly one target capture; trace=%q", len(captures), data)
	}
	if strings.Contains(captures[0], strconv.FormatInt(-1<<31, 10)) {
		t.Fatalf("target capture was unbounded: %q", captures[0])
	}
	if !strings.Contains(captures[0], " -S ") || !strings.Contains(captures[0], " -E ") {
		t.Fatalf("target capture lacks bounded range: %q", captures[0])
	}
	bytesCaptured := captureBytes(t, captures[0])
	if bytesCaptured == 0 {
		t.Fatalf("target capture recorded zero bytes: %q", captures[0])
	}
	fmt.Printf("PERF14_EVIDENCE metadata_queries=%d target_captures=%d captured_bytes=%d req_id=%d line_count=%d\n", len(metadata), len(captures), bytesCaptured, payload.ReqID, payload.LineCount)
}

func TestCountLinesTreatsNonEmptyBodyWithoutLFAsOne(t *testing.T) {
	if got := countLines([]byte("PERF14_BODY")); got != 1 {
		t.Fatalf("countLines(non-empty body without LF) = %d, want 1", got)
	}
	if got := countLines([]byte{}); got != 0 {
		t.Fatalf("countLines(empty) = %d, want 0 before H=0 placeholder", got)
	}
}

func readScrollbackPayload(t *testing.T, env *wsEnv, reqID uint32) protocol.BinaryPayload {
	t.Helper()
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		typ, data, err := env.conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("read scrollback response: %v", err)
		}
		if typ != websocket.MessageBinary {
			continue
		}
		payload, err := protocol.DecodeBinary(data)
		if err != nil {
			t.Fatalf("decode scrollback response: %v", err)
		}
		if payload.Kind == protocol.KindScrollback && payload.ReqID == reqID {
			return payload
		}
	}
	t.Fatalf("scrollback req_id=%d did not arrive", reqID)
	return protocol.BinaryPayload{}
}

func captureBytes(t *testing.T, line string) int {
	t.Helper()
	prefix := "capture-bytes="
	start := strings.Index(line, prefix)
	if start < 0 {
		t.Fatalf("capture trace lacks byte count: %q", line)
	}
	value := strings.TrimPrefix(strings.Fields(line[start:])[0], prefix)
	bytes, err := strconv.Atoi(value)
	if err != nil {
		t.Fatalf("capture byte count %q: %v", value, err)
	}
	return bytes
}
