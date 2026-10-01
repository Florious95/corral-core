package bridge

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func strongBridgePane(t *testing.T) (*testTMUX, *Pane) {
	t.Helper()
	dir := t.TempDir()
	source := filepath.Join(dir, "emit.sh")
	ready := filepath.Join(dir, "ready")
	stop := filepath.Join(dir, "stop")
	body := "#!/bin/sh\n"
	body += "while [ ! -e \"$PERF14_BRIDGE_READY\" ]; do sleep 0.01; done\n"
	body += "i=1\n"
	body += "while [ $i -le 60 ]; do printf 'B%03d....................................\\n' $i; i=$((i+1)); done\n"
	body += "while [ ! -e \"$PERF14_BRIDGE_STOP\" ]; do sleep 0.05; done\n"
	if err := os.WriteFile(source, []byte(body), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PERF14_BRIDGE_READY", ready)
	t.Setenv("PERF14_BRIDGE_STOP", stop)
	tm := newTestTMUX(t)
	p := tm.newPane(t, "exec "+shellQuote(source))
	strongBridgeRunTMUX(t, tm, "resize-window", "-t", p.target, "-x", "40", "-y", "10")
	// Owner signal starts emission only after the real 40x10 geometry is set.
	if err := os.WriteFile(ready, []byte("go\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.WriteFile(stop, []byte("stop\n"), 0o600) })
	return tm, p
}

func strongBridgeRunTMUX(t *testing.T, tm *testTMUX, args ...string) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "tmux", append([]string{"-S", tm.sock}, args...)...)
	cmd.Env = tm.env
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("tmux %q: %v output=%q", args, err, out)
	}
	return strings.TrimSpace(string(out))
}

func strongBridgeMetadata(t *testing.T, p *Pane) ScrollbackMetadata {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		metadata, err := p.ScrollbackMetadata(ctx)
		cancel()
		if err == nil && metadata.HistorySize == 51 && metadata.PaneHeight == 10 {
			return metadata
		}
	}
	t.Fatal("static bridge fixture did not converge to history=51 height=10")
	return ScrollbackMetadata{}
}

func testScrollbackPagingRangeStrong(t *testing.T) {
	_, p := strongBridgePane(t)
	metadata := strongBridgeMetadata(t, p)
	if metadata.HistorySize != 51 || metadata.PaneHeight != 10 {
		t.Fatalf("metadata=%+v, want history=51 height=10", metadata)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	page, err := p.Scrollback(ctx, -30, -21)
	cancel()
	if err != nil {
		t.Fatalf("scrollback page: %v", err)
	}
	wantRows := make([]string, 0, 10)
	for i := 22; i <= 31; i++ {
		wantRows = append(wantRows, fmt.Sprintf("B%03d....................................", i))
	}
	want := strings.Join(wantRows, "\n") + "\n"
	if string(page) != want {
		t.Fatalf("range [-30,-21] content=%q, want exact rows %q", page, want)
	}
}
