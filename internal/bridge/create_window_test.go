package bridge

import (
	"context"
	"strings"
	"testing"
)

func TestCreateWindowPinsNameAndDisablesAutomaticRename(t *testing.T) {
	tt := newTestTMUX(t)
	session := "create-agent-test"
	cwd := t.TempDir()
	if _, err := tt.run("new-session", "-d", "-s", session, "-c", cwd, "sh"); err != nil {
		t.Fatalf("new-session: %v", err)
	}
	paneID, err := CreateWindow(context.Background(), tt.sock, session, cwd, "named child", []string{"sh", "-c", "sleep 30"})
	if err != nil {
		t.Fatalf("CreateWindow: %v", err)
	}
	if !strings.HasPrefix(paneID, "%") {
		t.Fatalf("pane id=%q, want tmux pane id", paneID)
	}
	out, err := tt.run("display-message", "-p", "-t", paneID, "#{window_name}|#{pane_title}|#{automatic-rename}|#{allow-rename}")
	if err != nil {
		t.Fatalf("display-message: %v", err)
	}
	if got, want := strings.TrimSpace(out), "named child|named child|0|0"; got != want {
		t.Fatalf("window metadata=%q, want %q", got, want)
	}
}
