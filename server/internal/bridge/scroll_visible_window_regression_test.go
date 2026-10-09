package bridge

import (
	"context"
	"os"
	"strings"
	"testing"
)

// TestMouseTrackingScrollNeverCapturesViewport freezes the mouse=1 branch:
// SGR bytes are the pane's own input protocol, so a copy-mode/history capture
// must not be added to that path.
func TestMouseTrackingScrollNeverCapturesViewport(t *testing.T) {
	script, logPath := fakeTmuxScrollScript(t, "1")
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)

	pane := NewPane("/sock/x", "%0")
	entered, err := pane.InjectScroll(context.Background(), -2)
	if err != nil {
		t.Fatalf("InjectScroll: %v", err)
	}
	if entered {
		t.Fatal("mouse-tracking scroll must not enter copy-mode")
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read tmux argv log: %v", err)
	}
	calls := string(data)
	if strings.Contains(calls, "copy-mode") || strings.Contains(calls, "capture-pane") {
		t.Fatalf("mouse-tracking path must not enter/capture copy-mode, calls=%q", calls)
	}
	one := sgrWheelHexLine(64)
	want := "send-keys -H -t %0 " + one + " " + one
	if got := strings.TrimSpace(calls); got != want {
		t.Fatalf("mouse-tracking SGR argv=%q, want %q", got, want)
	}
}

// TestMouseTrackingSnapshotAfterScrollNeverCapturesViewport is a candidate-
// only guard: the 9ba baseline has no SnapshotAfterScroll method, so that
// baseline run is explicitly skipped while c187 must exercise the method.
func TestMouseTrackingSnapshotAfterScrollNeverCapturesViewport(t *testing.T) {
	script, logPath := fakeTmuxScrollScript(t, "1")
	old := tmuxBin
	tmuxBin = script
	defer func() { tmuxBin = old }()
	t.Setenv("ARGS_LOG", logPath)
	if err := os.WriteFile(logPath, nil, 0o600); err != nil {
		t.Fatalf("initialize argv log: %v", err)
	}

	pane := NewPane("/sock/x", "%0")
	snapshotter, ok := interface{}(pane).(interface {
		SnapshotAfterScroll(context.Context) ([]byte, bool, error)
	})
	if !ok {
		t.Skip("9ba baseline has no candidate SnapshotAfterScroll API")
	}
	snap, inMode, err := snapshotter.SnapshotAfterScroll(context.Background())
	if err != nil {
		t.Fatalf("SnapshotAfterScroll: %v", err)
	}
	if snap != nil || inMode {
		t.Fatalf("mouse-tracking SnapshotAfterScroll=(%q,%v), want (nil,false)", snap, inMode)
	}
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read argv log: %v", err)
	}
	if strings.TrimSpace(string(data)) != "" {
		t.Fatalf("mouse-tracking SnapshotAfterScroll dispatched tmux action: %q", data)
	}
}
