package bridge

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestKillPaneRunsOnlyKillPaneForExactTarget(t *testing.T) {
	dir := t.TempDir()
	argsFile := filepath.Join(dir, "args")
	script := filepath.Join(dir, "tmux-wrapper")
	if err := os.WriteFile(script, []byte("#!/bin/sh\nprintf '%s\\n' \"$@\" > \"$KILL_PANE_ARGS\"\n"), 0o700); err != nil {
		t.Fatalf("write wrapper: %v", err)
	}
	oldBin := tmuxBin
	tmuxBin = script
	t.Setenv("KILL_PANE_ARGS", argsFile)
	t.Cleanup(func() { tmuxBin = oldBin })

	if err := KillPane("/tmp/tmux-test", "%10"); err != nil {
		t.Fatalf("KillPane: %v", err)
	}
	got, err := os.ReadFile(argsFile)
	if err != nil {
		t.Fatalf("read wrapper args: %v", err)
	}
	if want := []string{"-S", "/tmp/tmux-test", "kill-pane", "-t", "%10"}; !strings.EqualFold(strings.TrimSpace(string(got)), strings.Join(want, "\n")) {
		t.Fatalf("tmux argv=%q, want %q", got, strings.Join(want, "\n"))
	}
	if strings.Contains(string(got), "kill-window") || strings.Contains(string(got), "kill-session") {
		t.Fatalf("KillPane widened target scope: %q", got)
	}
}
