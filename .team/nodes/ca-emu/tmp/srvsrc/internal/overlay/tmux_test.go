package overlay

import (
	"context"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

func TestChooseTreeStartsWithoutPreview(t *testing.T) {
	args := chooseTreeArgs()
	joined := strings.Join(args, " ")
	if !strings.Contains(joined, "-N") {
		t.Fatalf("choose-tree must start without preview (-N per tmux man), got %q", joined)
	}
	if args[0] != "choose-tree" {
		t.Fatalf("first arg %q", args[0])
	}
}

func TestTmuxScratchIsolated(t *testing.T) {
	if _, err := exec.LookPath("tmux"); err != nil {
		t.Skip("tmux not in PATH")
	}
	dir := "/tmp/ov-dev-server"
	sock := dir + "/sock"
	_ = os.RemoveAll(dir)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = exec.Command("tmux", "-S", sock, "kill-server").Run()
		_ = os.RemoveAll(dir)
	})

	cmd := exec.Command("tmux", "-S", sock, "new-session", "-d", "-s", "ovp", "-n", "p0", "sleep", "3600")
	cmd.Env = overlayChildEnv()
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("new-session: %v %s", err, out)
	}
	list := exec.Command("tmux", "-S", sock, "list-sessions", "-F", "#{session_name}")
	list.Env = overlayChildEnv()
	got, err := list.CombinedOutput()
	if err != nil {
		t.Fatalf("list-sessions: %v %s", err, got)
	}
	if !strings.Contains(string(got), "ovp") {
		t.Fatalf("自检失败：会话不在隔离 socket（got=%q）", got)
	}

	cap := NewTmux(nil, []string{dir})
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	t0 := time.Now()
	if err := cap.Start(ctx, sock); err != nil {
		t.Fatalf("Start: %v", err)
	}
	startDur := time.Since(t0)
	if startDur > 2*time.Second {
		t.Fatalf("Start took %s (>2s); probe window is 6s including first frame", startDur)
	}
	if cap.ClientCount() != 1 {
		t.Fatalf("clients=%d want 1", cap.ClientCount())
	}
	t1 := time.Now()
	a, err := cap.Snapshot(ctx)
	snapDur := time.Since(t1)
	if err != nil || len(strings.TrimSpace(string(a))) == 0 {
		t.Fatalf("first snapshot empty: err=%v n=%d dur=%s", err, len(a), snapDur)
	}
	if snapDur > time.Second {
		t.Fatalf("first Snapshot took %s (>1s); hung refresh-client/select-pane?", snapDur)
	}
	var b []byte
	for i := 0; i < 15; i++ {
		time.Sleep(80 * time.Millisecond)
		b, err = cap.Snapshot(ctx)
		if err != nil {
			t.Fatalf("snapshot %d: %v", i, err)
		}
		if string(b) != string(a) && len(strings.TrimSpace(string(b))) > 0 {
			break
		}
	}
	if string(b) == string(a) {
		t.Fatalf("snapshots did not change\nA=%q\nB=%q", a, b)
	}
	cap.Stop()
	if cap.ClientCount() != 0 {
		t.Fatalf("after Stop clients=%d want 0", cap.ClientCount())
	}
}
